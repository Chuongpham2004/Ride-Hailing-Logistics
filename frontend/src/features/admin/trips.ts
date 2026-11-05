import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/shared/api/client';
import type {
  CancelReason,
  ServiceType,
  StaffTripDetail,
  Trip,
  TripPage,
  TripStatus,
} from '@/shared/api/types';

/* Trip lookup for support staff (README §3, frontend-design.md §4.4). */

const PAGE_SIZE = 20;

/** The server's minimum for a staff cancellation note (trip-service TripService.MIN_STAFF_NOTE). */
export const MIN_CANCEL_NOTE = 10;

export interface TripFilter {
  status?: TripStatus;
  serviceType?: ServiceType;
  customerId?: string;
  driverId?: string;
  /** Local calendar days, "yyyy-mm-dd"; both included. */
  from?: string;
  to?: string;
}

export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Start of a local calendar day as an instant; `nextDay` gives the exclusive end of a range. */
export function dayStart(isoDate: string, nextDay = false): string {
  const [y, m, d] = isoDate.split('-').map(Number);
  return new Date(y!, (m ?? 1) - 1, (d ?? 1) + (nextDay ? 1 : 0)).toISOString();
}

export function searchQuery(filter: TripFilter): string {
  const params = new URLSearchParams();
  if (filter.status) params.set('status', filter.status);
  if (filter.serviceType) params.set('serviceType', filter.serviceType);
  if (filter.customerId) params.set('customerId', filter.customerId);
  if (filter.driverId) params.set('driverId', filter.driverId);
  if (filter.from) params.set('createdFrom', dayStart(filter.from));
  if (filter.to) params.set('createdTo', dayStart(filter.to, true));
  params.set('limit', String(PAGE_SIZE));
  return params.toString();
}

/** Any trip, newest first; every filter optional. */
export function useTripSearch(filter: TripFilter) {
  return useInfiniteQuery({
    queryKey: ['admin', 'trips', filter],
    queryFn: ({ pageParam, signal }) =>
      api<TripPage>(
        `/api/v1/admin/trips?${searchQuery(filter)}${pageParam ? `&before=${encodeURIComponent(pageParam)}` : ''}`,
        { signal },
      ),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextBefore ?? null,
  });
}

export const staffTripKey = (tripId: string) => ['admin', 'trip', tripId] as const;

/** The whole trip with history, offers and proof; each opening is audited (TRIP_VIEWED). */
export function useStaffTrip(tripId: string) {
  return useQuery({
    queryKey: staffTripKey(tripId),
    queryFn: ({ signal }) =>
      api<StaffTripDetail>(`/api/v1/admin/trips/${encodeURIComponent(tripId)}`, { signal }),
    staleTime: Infinity,
  });
}

/** Cancellation by exception (README §6): needs a note and is audited; the trip is loaded again after. */
export function useStaffCancel(tripId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ reason, note }: { reason: CancelReason; note: string }) =>
      api<Trip>(`/api/v1/trips/${encodeURIComponent(tripId)}/cancel`, {
        method: 'POST',
        body: { reason, note },
      }),
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: staffTripKey(tripId) });
      await queryClient.invalidateQueries({ queryKey: ['admin', 'trips'] });
    },
  });
}
