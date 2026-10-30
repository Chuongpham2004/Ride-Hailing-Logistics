import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import type { Trip } from '@/shared/api/types';
import type { RealtimeMessage } from '@/shared/realtime/protocol';
import { apiUrl, ok, server } from '@/test/server';
import TripPage from './TripPage';

// MapLibre needs WebGL, which jsdom lacks; the map is not what these tests are about.
vi.mock('@/shared/map/MapView', () => ({ MapView: () => <div data-testid="map" /> }));

// A stand-in for the tab's realtime connection that the test pushes messages through.
const bus = vi.hoisted(() => {
  const listeners = new Set<(message: unknown) => void>();
  return {
    listeners,
    followed: new Set<string>(),
    push(message: unknown) {
      listeners.forEach((listener) => listener(message));
    },
  };
});

vi.mock('@/shared/realtime/realtime', async () => {
  const { useEffect, useRef } = await import('react');
  return {
    realtime: {
      getStatus: () => 'open',
      followTrip: (id: string) => bus.followed.add(id),
      unfollowTrip: (id: string) => bus.followed.delete(id),
    },
    useRealtimeResync: () => {},
    useRealtimeMessages: (handler: (message: unknown) => void) => {
      const latest = useRef(handler);
      useEffect(() => {
        latest.current = handler;
      });
      useEffect(() => {
        const listener = (message: unknown) => latest.current(message);
        bus.listeners.add(listener);
        return () => {
          bus.listeners.delete(listener);
        };
      }, []);
    },
  };
});

const TRIP: Trip = {
  id: 'trip-1',
  customerId: 'customer-1',
  serviceType: 'RIDE',
  status: 'MATCHING',
  pickup: { latitude: 10.77, longitude: 106.7, address: '1 Lê Lợi, Quận 1' },
  dropoff: { latitude: 10.79, longitude: 106.72, address: '2 Điện Biên Phủ, Bình Thạnh' },
  fare: {
    quoteId: 'quote-1',
    quotedFare: 45000,
    currency: 'VND',
    surgeMultiplier: 1,
    pricingRuleVersion: 1,
    distanceMeters: 4200,
    durationSeconds: 900,
  },
  createdAt: '2026-10-30T02:00:00Z',
  version: 1,
  pickupCode: '482913',
};

function event(type: string, aggregateVersion: number): RealtimeMessage {
  return {
    messageId: crypto.randomUUID(),
    type,
    version: 1,
    sentAt: new Date().toISOString(),
    sequence: 1,
    aggregateVersion,
    data: { tripId: 'trip-1' },
  };
}

function renderTrip() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(
    [
      { path: '/app/trips/:tripId', element: <TripPage /> },
      { path: '/app', element: <p>Đặt xe</p> },
    ],
    { initialEntries: ['/app/trips/trip-1'] },
  );
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

describe('TripPage', () => {
  it('reloads the trip on a newer realtime event and shows the pickup code', async () => {
    let current = TRIP;
    let loads = 0;
    server.use(
      http.get(apiUrl('/api/v1/trips/trip-1'), () => {
        loads += 1;
        return ok(current);
      }),
      http.get(apiUrl('/api/v1/quotes/quote-1'), () => ok({ id: 'quote-1' })),
    );
    renderTrip();
    expect(await screen.findByText('Đang tìm tài xế gần bạn')).toBeInTheDocument();
    expect(loads).toBe(1);

    // Same version as the snapshot: nothing new, no reload.
    act(() => bus.push(event('TRIP_REQUESTED', 1)));
    expect(loads).toBe(1);

    current = { ...TRIP, status: 'ACCEPTED', driverId: 'driver-1', version: 2 };
    act(() => bus.push(event('TRIP_ACCEPTED', 2)));

    expect(await screen.findByText('Tài xế đang đến đón')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'Mã đón 4 8 2 9 1 3' })).toBeInTheDocument();
    expect(loads).toBe(2);
    expect(bus.followed.has('trip-1')).toBe(true);
  });

  it('shows the cancellation fee before cancelling and sends the reason', async () => {
    let cancelBody: unknown = null;
    server.use(
      http.get(apiUrl('/api/v1/trips/trip-1'), () => ok({ ...TRIP, status: 'ARRIVED', version: 4 })),
      http.get(apiUrl('/api/v1/trips/trip-1/cancellation-fee'), ({ request }) => {
        const reason = new URL(request.url).searchParams.get('reason');
        return ok({
          decision: 'LATE_CANCELLATION',
          fee: reason ? 10000 : 0,
          currency: 'VND',
          ruleVersion: 1,
        });
      }),
      http.post(apiUrl('/api/v1/trips/trip-1/cancel'), async ({ request }) => {
        cancelBody = await request.json();
        return ok({
          ...TRIP,
          status: 'CANCELLED',
          cancelledBy: 'CUSTOMER',
          cancelReason: 'WAIT_TOO_LONG',
          version: 5,
        });
      }),
    );
    renderTrip();
    await userEvent.click(await screen.findByRole('button', { name: 'Huỷ chuyến' }));
    await userEvent.click(screen.getByRole('radio', { name: 'Chờ lâu quá' }));

    expect(await screen.findByText(/Phí huỷ/)).toHaveTextContent('10.000');
    await userEvent.click(screen.getByRole('button', { name: 'Huỷ và trả phí' }));

    expect(await screen.findByText('Bạn đã huỷ chuyến')).toBeInTheDocument();
    expect(cancelBody).toEqual({ reason: 'WAIT_TOO_LONG' });
  });
});
