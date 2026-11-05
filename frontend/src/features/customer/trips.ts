import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { api } from '@/shared/api/client';
import type { DeliveryInput, Trip, TripStatus } from '@/shared/api/types';
import type { TripDriverLocation } from '@/shared/realtime/protocol';
import { realtime, useRealtimeMessages } from '@/shared/realtime/realtime';
import { tripKey, useTripSnapshotSync } from '@/shared/trips/trips';

export {
  ENDED,
  tripKey,
  useCancellationFee,
  useCancelTrip,
  useMyTrips,
  useTrip,
  useTripHistory,
} from '@/shared/trips/trips';

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

export interface DriverPosition {
  latitude: number;
  longitude: number;
  headingDegrees?: number;
  sequence: number;
}

/**
 * Keeps the trip snapshot current from realtime events and follows the driver's position, which
 * the gateway only sends to a customer who subscribed to the trip.
 */
export function useLiveTrip(tripId: string, trip: Trip | undefined): DriverPosition | null {
  const [driver, setDriver] = useState<DriverPosition | null>(null);
  const followed = trip !== undefined && DRIVER_ASSIGNED.has(trip.status);

  useEffect(() => {
    if (!followed) return;
    realtime.followTrip(tripId);
    return () => realtime.unfollowTrip(tripId);
  }, [tripId, followed]);

  useTripSnapshotSync(tripId);

  useRealtimeMessages((message) => {
    if (message.type !== 'TRIP_DRIVER_LOCATION') return;
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
  });

  return followed ? driver : null;
}
