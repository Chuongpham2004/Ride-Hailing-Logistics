import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { create } from 'zustand';
import { api } from '@/shared/api/client';
import type { Quote, ServiceType, Stop } from '@/shared/api/types';
import { decodePolyline, type LngLat } from '@/shared/map/polyline';

export type StopKind = 'pickup' | 'dropoff';

interface BookingState {
  service: ServiceType;
  pickup: Stop | null;
  dropoff: Stop | null;
  setService: (service: ServiceType) => void;
  setStop: (kind: StopKind, stop: Stop | null) => void;
}

/** What the customer is putting together before asking for a price. */
export const useBooking = create<BookingState>((set) => ({
  service: 'RIDE',
  pickup: null,
  dropoff: null,
  setService: (service) => set({ service }),
  setStop: (kind, stop) => set(kind === 'pickup' ? { pickup: stop } : { dropoff: stop }),
}));

export const SERVICE_NAMES: Record<ServiceType, string> = {
  RIDE: 'Chở khách',
  DELIVERY: 'Giao hàng',
};

/** The server limits an address to 300 characters. */
export function toStop(latitude: number, longitude: number, address: string): Stop {
  return { latitude, longitude, address: address.slice(0, 300) };
}

export interface QuoteRequest {
  serviceType: ServiceType;
  pickup: Stop;
  dropoff: Stop;
}

const quoteKey = (id: string) => ['quote', id] as const;

/** POST /quotes; the result is cached so the quote screen opens without a second request. */
export function useCreateQuote() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: QuoteRequest) => api<Quote>('/api/v1/quotes', { method: 'POST', body: request }),
    onSuccess: (quote) => queryClient.setQueryData(quoteKey(quote.id), quote),
  });
}

export function useQuote(id: string, enabled = true) {
  return useQuery({
    queryKey: quoteKey(id),
    enabled: enabled && id !== '',
    queryFn: ({ signal }) => api<Quote>(`/api/v1/quotes/${encodeURIComponent(id)}`, { signal }),
    // A quote never changes; only its validity runs out, which the screen counts down itself.
    staleTime: Infinity,
  });
}

/** The quote's road route, or null for a straight-line estimate or an unreadable polyline. */
export function decodeRoute(quote: Quote | undefined): LngLat[] | null {
  if (!quote?.routePolyline) return null;
  try {
    return decodePolyline(quote.routePolyline);
  } catch {
    return null;
  }
}
