import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { RealtimeMessage } from '@/shared/realtime/protocol';
import { apiUrl, fail, ok, server } from '@/test/server';
import { OfferOverlay } from './OfferOverlay';
import { useOfferFeed, useOffers } from './offers';

const bus = vi.hoisted(() => {
  const listeners = new Set<(message: unknown) => void>();
  return {
    listeners,
    push(message: unknown) {
      listeners.forEach((listener) => listener(message));
    },
  };
});

vi.mock('@/shared/realtime/realtime', async () => {
  const { useEffect, useRef } = await import('react');
  return {
    realtime: { getStatus: () => 'open' },
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

function message(type: string, data: Record<string, unknown>): RealtimeMessage {
  return {
    messageId: crypto.randomUUID(),
    type,
    version: 1,
    sentAt: new Date().toISOString(),
    sequence: 1,
    data,
  };
}

function offerCreated(seconds = 15) {
  return message('DRIVER_OFFER_CREATED', {
    offerId: 'offer-1',
    tripId: 'trip-1',
    driverId: 'driver-1',
    serviceType: 'RIDE',
    pickup: { latitude: 10.77, longitude: 106.7, address: 'Chợ Bến Thành, Quận 1' },
    estimatedPickupDistanceMeters: 850,
    createdAt: new Date().toISOString(),
    expiresAt: new Date(Date.now() + seconds * 1000).toISOString(),
  });
}

function Feed() {
  useOfferFeed(true);
  return <OfferOverlay />;
}

function renderOverlay() {
  server.use(http.get(apiUrl('/api/v1/offers'), () => ok([])));
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(
    [
      { path: '/driver', element: <Feed /> },
      { path: '/driver/trips/:tripId', element: <p>Màn chuyến</p> },
    ],
    { initialEntries: ['/driver'] },
  );
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

describe('OfferOverlay', () => {
  afterEach(() => {
    useOffers.setState({ offer: null, notice: null });
    vi.useRealTimers();
  });

  it('shows a new offer and opens the trip after accepting it', async () => {
    let accepted = false;
    server.use(
      http.post(apiUrl('/api/v1/offers/offer-1/accept'), () => {
        accepted = true;
        return ok({ id: 'trip-1', status: 'ACCEPTED', version: 2 });
      }),
    );
    renderOverlay();
    act(() => bus.push(offerCreated()));

    const dialog = await screen.findByRole('alertdialog', { name: /Chuyến mới · Chở khách/ });
    expect(dialog).toHaveTextContent('Chợ Bến Thành, Quận 1');
    expect(dialog).toHaveTextContent('850 m');

    await userEvent.click(screen.getByRole('button', { name: 'Nhận chuyến' }));
    expect(await screen.findByText('Màn chuyến')).toBeInTheDocument();
    expect(accepted).toBe(true);
    expect(useOffers.getState().offer).toBeNull();
  });

  it('closes with a notice when the offer is already gone', async () => {
    server.use(http.post(apiUrl('/api/v1/offers/offer-1/accept'), () => fail(409, 'OFFER_EXPIRED')));
    renderOverlay();
    act(() => bus.push(offerCreated()));
    await userEvent.click(await screen.findByRole('button', { name: 'Nhận chuyến' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Offer đã hết hạn');
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
  });

  it('closes when the server expires or cancels the offer', async () => {
    renderOverlay();
    act(() => bus.push(offerCreated()));
    await screen.findByRole('alertdialog');

    act(() => bus.push(message('DRIVER_OFFER_CANCELLED', { offerId: 'other', reason: 'TRIP_CANCELLED' })));
    expect(screen.getByRole('alertdialog')).toBeInTheDocument();

    act(() => bus.push(message('DRIVER_OFFER_CANCELLED', { offerId: 'offer-1', reason: 'TRIP_CANCELLED' })));
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Khách đã huỷ chuyến');
  });

  it('declines the offer', async () => {
    let declined = false;
    server.use(
      http.post(apiUrl('/api/v1/offers/offer-1/decline'), () => {
        declined = true;
        return ok({ id: 'offer-1', status: 'DECLINED' });
      }),
    );
    renderOverlay();
    act(() => bus.push(offerCreated()));
    await userEvent.click(await screen.findByRole('button', { name: 'Từ chối' }));

    await vi.waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument());
    expect(declined).toBe(true);
  });

  it('closes by itself when the countdown runs out', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderOverlay();
    act(() => bus.push(offerCreated(3)));
    await screen.findByRole('alertdialog');

    await act(() => vi.advanceTimersByTimeAsync(4000));
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Offer đã hết hạn');
  });
});
