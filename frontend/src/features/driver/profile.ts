import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { api } from '@/shared/api/client';
import type { DriverProfile, ServiceType, Trip, TripPage, VehicleType } from '@/shared/api/types';
import { OFFER_EVENT_TYPES, TRIP_EVENT_TYPES } from '@/shared/realtime/protocol';
import { useRealtimeMessages, useRealtimeResync } from '@/shared/realtime/realtime';
import { ENDED } from '@/shared/trips/trips';

export const profileKey = ['driver', 'profile'] as const;
export const activeTripKey = ['driver', 'active-trip'] as const;

/** Which services each vehicle type can serve (user-service VehicleType.supports). */
export const VEHICLE_SERVES: Record<VehicleType, ServiceType[]> = {
  MOTORBIKE: ['RIDE', 'DELIVERY'],
  CAR_4_SEAT: ['RIDE'],
  CAR_7_SEAT: ['RIDE'],
  VAN: ['DELIVERY'],
};

export const VEHICLE_NAMES: Record<VehicleType, string> = {
  MOTORBIKE: 'Xe máy',
  CAR_4_SEAT: 'Ô tô 4 chỗ',
  CAR_7_SEAT: 'Ô tô 7 chỗ',
  VAN: 'Xe tải van',
};

export const SERVICE_NAMES: Record<ServiceType, string> = {
  RIDE: 'Chở khách',
  DELIVERY: 'Giao hàng',
};

/** GET /drivers/me/profile; 404 until the driver has created a profile. */
export function useDriverProfile(refetchInterval?: number) {
  return useQuery({
    queryKey: profileKey,
    queryFn: ({ signal }) => api<DriverProfile>('/api/v1/drivers/me/profile', { signal }),
    refetchInterval,
  });
}

export function useGoOnline() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: { vehicleId: string; serviceTypes: ServiceType[] }) =>
      api<DriverProfile>('/api/v1/drivers/me/availability/online', { method: 'POST', body: request }),
    onSuccess: (profile) => queryClient.setQueryData(profileKey, profile),
    // 409 "already online" or a profile changed elsewhere: show what the server holds now.
    onError: () => void queryClient.invalidateQueries({ queryKey: profileKey }),
  });
}

export function useGoOffline() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api<DriverProfile>('/api/v1/drivers/me/availability/offline', { method: 'POST' }),
    onSuccess: (profile) => queryClient.setQueryData(profileKey, profile),
    onError: () => void queryClient.invalidateQueries({ queryKey: profileKey }),
  });
}

/**
 * The driver's trip in progress, if any: there is no endpoint for it, so take the newest trip
 * and keep it when it has not ended (a driver has at most one at a time).
 */
export function useActiveTrip(enabled: boolean) {
  return useQuery({
    queryKey: activeTripKey,
    queryFn: async ({ signal }) => {
      const page = await api<TripPage>('/api/v1/trips?limit=5', { signal });
      return page.items.find((trip) => !ENDED.has(trip.status)) ?? null;
    },
    enabled,
  });
}

/** user-service learns about offers and trips from Kafka, a moment after the driver sees them. */
const AVAILABILITY_LAG_MS = 1500;

/**
 * Availability (AVAILABLE / OFFERED / BUSY) is not pushed over the socket, so reload the profile
 * shortly after every offer or trip event, and the active trip after every trip event.
 */
export function useDriverStateSync(): void {
  const queryClient = useQueryClient();
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
    },
    [],
  );

  const reloadSoon = () => {
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => {
      timer.current = null;
      void queryClient.invalidateQueries({ queryKey: profileKey });
    }, AVAILABILITY_LAG_MS);
  };

  useRealtimeResync(() => {
    void queryClient.invalidateQueries({ queryKey: profileKey });
    void queryClient.invalidateQueries({ queryKey: activeTripKey });
  });

  useRealtimeMessages((message) => {
    if (OFFER_EVENT_TYPES.has(message.type)) {
      reloadSoon();
      return;
    }
    if (TRIP_EVENT_TYPES.has(message.type)) {
      reloadSoon();
      void queryClient.invalidateQueries({ queryKey: activeTripKey });
    }
  });
}

/** After accepting, the trip is known before the trip list catches up. */
export function rememberActiveTrip(queryClient: ReturnType<typeof useQueryClient>, trip: Trip): void {
  queryClient.setQueryData(activeTripKey, ENDED.has(trip.status) ? null : trip);
}
