import type {
  ActorType,
  CancelReason,
  PaymentPurpose,
  PaymentStatus,
  RefundReason,
  TripStatus,
} from '@/shared/api/types';

/* How trips and payments are named on screen, shared by the trip, receipt and activity screens. */

export const REASON_NAMES: Record<CancelReason, string> = {
  CHANGED_MIND: 'đổi ý',
  WAIT_TOO_LONG: 'chờ quá lâu',
  DRIVER_NOT_MOVING: 'tài xế không di chuyển',
  CUSTOMER_NO_SHOW: 'khách không ra điểm đón',
  WRONG_PICKUP: 'sai điểm đón',
  VEHICLE_ISSUE: 'xe gặp sự cố',
  SAFETY_CONCERN: 'lo ngại an toàn',
  OTHER: 'lý do khác',
};

export const CANCELLED_BY: Record<ActorType, string> = {
  CUSTOMER: 'Bạn đã huỷ chuyến',
  DRIVER: 'Tài xế đã huỷ chuyến',
  STAFF: 'Tổng đài đã huỷ chuyến',
  SYSTEM: 'Chuyến đã bị huỷ',
};

/** Short status for lists and the timeline. */
export const STATUS_NAMES: Record<TripStatus, string> = {
  CREATED: 'Đã đặt',
  MATCHING: 'Đang tìm tài xế',
  ACCEPTED: 'Tài xế đã nhận',
  PICKING_UP: 'Tài xế đang đến',
  ARRIVED: 'Tài xế đã đến',
  IN_TRIP: 'Đang chở',
  COMPLETED: 'Hoàn tất',
  CANCELLED: 'Đã huỷ',
  NO_DRIVER: 'Không có tài xế',
};

export const ACTOR_NAMES: Record<ActorType, string> = {
  CUSTOMER: 'bạn',
  DRIVER: 'tài xế',
  STAFF: 'tổng đài',
  SYSTEM: 'hệ thống',
};

export const PAYMENT_STATUS_NAMES: Record<PaymentStatus, string> = {
  PENDING: 'Đang xử lý',
  SUCCEEDED: 'Đã thanh toán',
  FAILED: 'Chưa thanh toán được',
  REFUND_PENDING: 'Đang hoàn tiền',
  PARTIALLY_REFUNDED: 'Đã hoàn một phần',
  REFUNDED: 'Đã hoàn tiền',
};

export const PURPOSE_NAMES: Record<PaymentPurpose, string> = {
  TRIP_FARE: 'Cước chuyến',
  CANCELLATION_FEE: 'Phí huỷ chuyến',
};

export const REFUND_REASON_NAMES: Record<RefundReason, string> = {
  SERVICE_NOT_PROVIDED: 'Chuyến không diễn ra như đã đặt',
  OVERCHARGE: 'Thu dư',
  DUPLICATE_CHARGE: 'Thu trùng',
  SERVICE_COMPLAINT: 'Giải quyết khiếu nại',
  GOODWILL: 'Hỗ trợ khách hàng',
  OTHER: 'Lý do khác',
};

const dateTime = new Intl.DateTimeFormat('vi-VN', {
  hour: '2-digit',
  minute: '2-digit',
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
});
const time = new Intl.DateTimeFormat('vi-VN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });

/** "14:05 30/10/2026". */
export function formatDateTime(iso: string): string {
  return dateTime.format(new Date(iso));
}

/** "14:05:12". */
export function formatTime(iso: string): string {
  return time.format(new Date(iso));
}
