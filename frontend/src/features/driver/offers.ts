import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { create } from 'zustand';
import { api } from '@/shared/api/client';
import type { Offer, Trip } from '@/shared/api/types';
import type { OfferCreated } from '@/shared/realtime/protocol';
import { useRealtimeMessages, useRealtimeResync } from '@/shared/realtime/realtime';
import { tripKey } from '@/shared/trips/trips';
import { rememberActiveTrip } from './profile';

/*
 * The offer waiting for the driver's answer. It arrives as DRIVER_OFFER_CREATED; GET /offers fills
 * it in after a page load or a reconnect, since events sent while the socket was down are lost.
 */

export interface PendingOffer {
  id: string;
  tripId: string;
  serviceType: Offer['serviceType'];
  pickup: Offer['pickup'];
  estimatedPickupDistanceMeters: number;
  expiresAt: string;
}

interface OfferState {
  offer: PendingOffer | null;
  /** Shown once the overlay closes without the driver answering (expired, taken, cancelled). */
  notice: string | null;
  show: (offer: PendingOffer) => void;
  /** Drops the offer if it is still the one shown. */
  drop: (offerId: string, notice?: string) => void;
  clearNotice: () => void;
}

export const useOffers = create<OfferState>((set, get) => ({
  offer: null,
  notice: null,
  show: (offer) => {
    if (Date.parse(offer.expiresAt) <= Date.now()) return;
    set({ offer, notice: null });
  },
  drop: (offerId, notice) => {
    if (get().offer?.id !== offerId) return;
    set({ offer: null, notice: notice ?? null });
  },
  clearNotice: () => set({ notice: null }),
}));

const ENDED_NOTICE: Record<string, string> = {
  DRIVER_OFFER_EXPIRED: 'Offer đã hết hạn.',
  DRIVER_OFFER_CANCELLED: 'Khách đã huỷ chuyến.',
};

function fromView(view: Offer): PendingOffer {
  return {
    id: view.id,
    tripId: view.tripId,
    serviceType: view.serviceType,
    pickup: view.pickup,
    estimatedPickupDistanceMeters: view.estimatedPickupDistanceMeters,
    expiresAt: view.expiresAt,
  };
}

async function loadPending(): Promise<void> {
  try {
    const offers = await api<Offer[]>('/api/v1/offers');
    const pending = offers
      .filter((offer) => offer.status === 'PENDING')
      .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))[0];
    if (pending) useOffers.getState().show(fromView(pending));
  } catch {
    // The socket still delivers new offers; nothing to show for a failed catch-up.
  }
}

/** Mounted once by the driver layout while the driver is online. */
export function useOfferFeed(online: boolean): void {
  useEffect(() => {
    if (online) void loadPending();
  }, [online]);

  useRealtimeResync(() => {
    if (online) void loadPending();
  });

  useRealtimeMessages((message) => {
    switch (message.type) {
      case 'DRIVER_OFFER_CREATED': {
        const data = message.data as unknown as OfferCreated;
        useOffers.getState().show({
          id: data.offerId,
          tripId: data.tripId,
          serviceType: data.serviceType,
          pickup: data.pickup,
          estimatedPickupDistanceMeters: data.estimatedPickupDistanceMeters,
          expiresAt: data.expiresAt,
        });
        break;
      }
      case 'DRIVER_OFFER_EXPIRED':
      case 'DRIVER_OFFER_DECLINED':
      case 'DRIVER_OFFER_CANCELLED': {
        const { offerId } = message.data as { offerId: string };
        useOffers.getState().drop(offerId, ENDED_NOTICE[message.type]);
        break;
      }
    }
  });
}

export function useAcceptOffer() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (offerId: string) =>
      api<Trip>(`/api/v1/offers/${encodeURIComponent(offerId)}/accept`, { method: 'POST' }),
    onSuccess: (trip) => {
      queryClient.setQueryData(tripKey(trip.id), trip);
      rememberActiveTrip(queryClient, trip);
      // The overlay closes itself after navigating: dropping here would unmount it first.
    },
  });
}

export function useDeclineOffer() {
  return useMutation({
    mutationFn: (offerId: string) =>
      api<Offer>(`/api/v1/offers/${encodeURIComponent(offerId)}/decline`, { method: 'POST' }),
    onSettled: (_data, _error, offerId) => useOffers.getState().drop(offerId),
  });
}
