import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { api } from '@/shared/api/client';
import type {
  CancelReason,
  CancellationFee,
  DeliveryInput,
  StatusChange,
  Trip,
  TripPage,
  TripStatus,
} from '@/shared/api/types';
import { TRIP_EVENT_TYPES, type TripDriverLocation } from '@/shared/realtime/protocol';
import { realtime, useRealtimeMessages, useRealtimeResync } from '@/shared/realtime/realtime';

export const tripKey = (id: string) => ['trip', id] as const;

/** Over: nothing will change any more (COMPLETED, CANCELLED, NO_DRIVER). */
export const ENDED: ReadonlySet<TripStatus> = new Set(['COMPLETED', 'CANCELLED', 'NO_DRIVER']);

/** The customer may cancel until the trip starts (trip-service TripStateMachine). */
export const CUSTOMER_CANCELLABLE: ReadonlySet<TripStatus> = new Set([
  'CREATED',
  'MATCHING',
  'ACCEPTED',
  'PICKING_UP',
  'ARRIVED',
]);

/** A driver is on the way or there, so the customer follows the car on the map. */
export const DRIVER_ASSIGNED: ReadonlySet<TripStatus> = new Set([
  'ACCEPTED',
  'PICKING_UP',
  'ARRIVED',
  'IN_TRIP',
]);

export interface BookRequest {
  quoteId: string;
  /** Required when the quote has a surge, and must equal it (BR-006). */
  acceptedSurgeMultiplier?: number;
  delivery?: DeliveryInput;
}

/**
 * POST /trips. The caller keeps one idempotency key per booking attempt, so a retry after a lost
 * response returns the same trip instead of booking twice (COM-008).
 */
export function useBookTrip() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ key, request }: { key: string; request: BookRequest }) =>
      api<Trip>('/api/v1/trips', { method: 'POST', body: request, headers: { 'Idempotency-Key': key } }),
    onSuccess: (trip) => queryClient.setQueryData(tripKey(trip.id), trip),
  });
}

/** A fresh Idempotency-Key: 8–100 characters of [A-Za-z0-9_-]. */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

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

const PAGE_SIZE = 20;

/** The customer's trips, newest first, a page at a time (cursor `before`). */
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

export interface DriverPosition {
  latitude: number;
  longitude: number;
  headingDegrees?: number;
  sequence: number;
}

/**
 * Keeps the trip snapshot current from realtime events and follows the driver's position.
 * A trip event newer than the snapshot (aggregateVersion > version) reloads it; so does every
 * reconnect, since events sent while the socket was down are not replayed (frontend-design.md §6).
 */
export function useLiveTrip(tripId: string, trip: Trip | undefined): DriverPosition | null {
  const queryClient = useQueryClient();
  const [driver, setDriver] = useState<DriverPosition | null>(null);
  const followed = trip !== undefined && DRIVER_ASSIGNED.has(trip.status);

  useEffect(() => {
    if (!followed) return;
    realtime.followTrip(tripId);
    return () => realtime.unfollowTrip(tripId);
  }, [tripId, followed]);

  useRealtimeResync(() => {
    void queryClient.invalidateQueries({ queryKey: tripKey(tripId), exact: true });
  });

  useRealtimeMessages((message) => {
    if (TRIP_EVENT_TYPES.has(message.type)) {
      if ((message.data as { tripId?: string }).tripId !== tripId) return;
      const current = queryClient.getQueryData<Trip>(tripKey(tripId));
      if (current && message.aggregateVersion !== undefined && message.aggregateVersion <= current.version) {
        return;
      }
      void queryClient.invalidateQueries({ queryKey: tripKey(tripId), exact: true });
      return;
    }
    if (message.type === 'TRIP_DRIVER_LOCATION') {
      const data = message.data as unknown as TripDriverLocation;
      if (data.tripId !== tripId) return;
      setDriver((previous) =>
        previous && previous.sequence >= data.sequence
          ? previous
          : {
              latitude: data.latitude,
              longitude: data.longitude,
              headingDegrees: data.headingDegrees,
              sequence: data.sequence,
            },
      );
    }
  });

  return followed ? driver : null;
}
