import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/shared/api/client';
import type {
  CancelReason,
  CancellationFee,
  StatusChange,
  Trip,
  TripPage,
  TripStatus,
} from '@/shared/api/types';
import { TRIP_EVENT_TYPES } from '@/shared/realtime/protocol';
import { realtime, useRealtimeMessages, useRealtimeResync } from '@/shared/realtime/realtime';

/*
 * Trip queries both the customer and the driver area use. The server decides what each side sees
 * (only the customer gets the codes); these hooks just load and refresh it.
 */

export const tripKey = (id: string) => ['trip', id] as const;

/** Over: nothing will change any more (COMPLETED, CANCELLED, NO_DRIVER). */
export const ENDED: ReadonlySet<TripStatus> = new Set(['COMPLETED', 'CANCELLED', 'NO_DRIVER']);

export function useTrip(id: string) {
  return useQuery({
    queryKey: tripKey(id),
    queryFn: ({ signal }) => api<Trip>(`/api/v1/trips/${encodeURIComponent(id)}`, { signal }),
    // Realtime events trigger reloads; this is the fallback while the socket is down.
    refetchInterval: (query) => {
      const trip = query.state.data;
      if (trip && ENDED.has(trip.status)) return false;
      return realtime.getStatus() === 'open' ? 60_000 : 10_000;
    },
  });
}

/**
 * Keeps a trip snapshot current: a trip event newer than the snapshot (aggregateVersion > version)
 * reloads it, and so does every reconnect, since events sent while the socket was down are not
 * replayed (frontend-design.md §6).
 */
export function useTripSnapshotSync(tripId: string): void {
  const queryClient = useQueryClient();

  useRealtimeResync(() => {
    void queryClient.invalidateQueries({ queryKey: tripKey(tripId), exact: true });
  });

  useRealtimeMessages((message) => {
    if (!TRIP_EVENT_TYPES.has(message.type)) return;
    if ((message.data as { tripId?: string }).tripId !== tripId) return;
    const current = queryClient.getQueryData<Trip>(tripKey(tripId));
    if (current && message.aggregateVersion !== undefined && message.aggregateVersion <= current.version) {
      return;
    }
    void queryClient.invalidateQueries({ queryKey: tripKey(tripId), exact: true });
  });
}

const PAGE_SIZE = 20;

/** The signed-in user's trips (as customer or as driver), newest first, a page at a time. */
export function useMyTrips() {
  return useInfiniteQuery({
    queryKey: ['trips', 'mine'],
    queryFn: ({ pageParam, signal }) =>
      api<TripPage>(
        `/api/v1/trips?limit=${PAGE_SIZE}${pageParam ? `&before=${encodeURIComponent(pageParam)}` : ''}`,
        { signal },
      ),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextBefore ?? null,
  });
}

/** Every status change of a trip, oldest first. */
export function useTripHistory(id: string) {
  return useQuery({
    queryKey: ['trip', id, 'history'],
    queryFn: ({ signal }) =>
      api<StatusChange[]>(`/api/v1/trips/${encodeURIComponent(id)}/history`, { signal }),
  });
}

export function useCancellationFee(tripId: string, reason: CancelReason, enabled: boolean) {
  return useQuery({
    queryKey: ['trip', tripId, 'cancellation-fee', reason],
    queryFn: ({ signal }) =>
      api<CancellationFee>(
        `/api/v1/trips/${encodeURIComponent(tripId)}/cancellation-fee?reason=${encodeURIComponent(reason)}`,
        { signal },
      ),
    enabled,
    // The fee changes as time passes (free window), so ask again each time the dialog opens.
    staleTime: 0,
    gcTime: 0,
  });
}

export function useCancelTrip(tripId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (reason: CancelReason) =>
      api<Trip>(`/api/v1/trips/${encodeURIComponent(tripId)}/cancel`, {
        method: 'POST',
        body: { reason },
      }),
    onSuccess: (trip) => queryClient.setQueryData(tripKey(trip.id), trip),
  });
}
