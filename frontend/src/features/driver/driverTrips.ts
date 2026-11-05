import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '@/shared/api/ApiError';
import { api } from '@/shared/api/client';
import { messageFor } from '@/shared/api/messages';
import type { CancelReason, Trip, TripStatus } from '@/shared/api/types';
import { tripKey } from '@/shared/trips/trips';
import { rememberActiveTrip } from './profile';

/** The driver's next move in each trip status (frontend-design.md §4.3). */
export type TripStep = 'start-pickup' | 'arrive' | 'start' | 'complete';

export const NEXT_STEP: Partial<Record<TripStatus, TripStep>> = {
  ACCEPTED: 'start-pickup',
  PICKING_UP: 'arrive',
  ARRIVED: 'start',
  IN_TRIP: 'complete',
};

/** The driver may cancel before the passenger or parcel is on board (TripStateMachine). */
export const DRIVER_CANCELLABLE: ReadonlySet<TripStatus> = new Set(['ACCEPTED', 'PICKING_UP', 'ARRIVED']);

export const DRIVER_REASONS: { value: CancelReason; label: string }[] = [
  { value: 'CUSTOMER_NO_SHOW', label: 'Khách không ra điểm đón' },
  { value: 'VEHICLE_ISSUE', label: 'Xe gặp sự cố' },
  { value: 'SAFETY_CONCERN', label: 'Lo ngại an toàn' },
  { value: 'OTHER', label: 'Lý do khác' },
];

export const CANCEL_REASON_NAMES: Record<CancelReason, string> = {
  CHANGED_MIND: 'khách đổi ý',
  WAIT_TOO_LONG: 'khách chờ quá lâu',
  DRIVER_NOT_MOVING: 'tài xế không di chuyển',
  CUSTOMER_NO_SHOW: 'khách không ra điểm đón',
  WRONG_PICKUP: 'sai điểm đón',
  VEHICLE_ISSUE: 'xe gặp sự cố',
  SAFETY_CONCERN: 'lo ngại an toàn',
  OTHER: 'lý do khác',
};

/** Short status for the driver's trip list. */
export const STATUS_NAMES: Record<TripStatus, string> = {
  CREATED: 'Đã đặt',
  MATCHING: 'Đang ghép',
  ACCEPTED: 'Đã nhận',
  PICKING_UP: 'Đang đi đón',
  ARRIVED: 'Đã đến điểm đón',
  IN_TRIP: 'Đang chạy',
  COMPLETED: 'Hoàn tất',
  CANCELLED: 'Đã huỷ',
  NO_DRIVER: 'Không có tài xế',
};

export function useTripStep(tripId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ step, code }: { step: TripStep; code?: string }) =>
      api<Trip>(`/api/v1/trips/${encodeURIComponent(tripId)}/${step}`, {
        method: 'POST',
        body: code ? { code } : undefined,
      }),
    onSuccess: (trip) => {
      queryClient.setQueryData(tripKey(trip.id), trip);
      rememberActiveTrip(queryClient, trip);
    },
    // Someone else moved the trip (the customer cancelled): show where it is now.
    onError: (error) => {
      if (error instanceof ApiError && error.code === 'INVALID_TRIP_STATE') {
        void queryClient.invalidateQueries({ queryKey: tripKey(tripId), exact: true });
      }
    },
  });
}

/**
 * Wording for a failed start / complete. The server only says BUSINESS_RULE_VIOLATION for every
 * code problem, so tell them apart by its (English, stable) message.
 */
export function stepErrorText(error: unknown, codeName: string): string {
  if (error instanceof ApiError && error.code === 'BUSINESS_RULE_VIOLATION') {
    if (/too many wrong/i.test(error.message)) {
      return `Nhập sai ${codeName} quá nhiều lần, mã đã bị khoá. Liên hệ tổng đài để được hỗ trợ.`;
    }
    if (/is required/i.test(error.message)) return `Cần nhập ${codeName}.`;
    if (/is wrong/i.test(error.message)) return `${capitalise(codeName)} chưa đúng, hỏi lại rồi nhập lại.`;
  }
  return messageFor(error);
}

function capitalise(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}
