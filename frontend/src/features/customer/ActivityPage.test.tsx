import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it } from 'vitest';
import type { Trip } from '@/shared/api/types';
import { apiUrl, ok, server } from '@/test/server';
import ActivityDetailPage from './ActivityDetailPage';
import ActivityPage from './ActivityPage';

function trip(id: string, status: Trip['status'], address: string): Trip {
  return {
    id,
    customerId: 'customer-1',
    serviceType: 'RIDE',
    status,
    pickup: { latitude: 10.77, longitude: 106.7, address: '1 Lê Lợi' },
    dropoff: { latitude: 10.79, longitude: 106.72, address },
    createdAt: '2026-10-30T02:00:00Z',
    version: 1,
  };
}

function renderAt(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(
    [
      { path: '/app/activity', element: <ActivityPage /> },
      { path: '/app/activity/:tripId', element: <ActivityDetailPage /> },
      { path: '/app/trips/:tripId', element: <p>Màn chuyến</p> },
      { path: '/app/trips/:tripId/receipt', element: <p>Màn hoá đơn</p> },
    ],
    { initialEntries: [path] },
  );
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

describe('ActivityPage', () => {
  it('pages through the trips and opens a running one on the trip screen', async () => {
    server.use(
      http.get(apiUrl('/api/v1/trips'), ({ request }) => {
        const before = new URL(request.url).searchParams.get('before');
        return before === 'trip-2'
          ? ok({ items: [trip('trip-3', 'CANCELLED', 'Chợ Bến Thành')] })
          : ok({
              items: [
                trip('trip-1', 'IN_TRIP', 'Thảo Cầm Viên'),
                trip('trip-2', 'COMPLETED', 'Nhà thờ Đức Bà'),
              ],
              nextBefore: 'trip-2',
            });
      }),
    );
    renderAt('/app/activity');

    expect(await screen.findByText('Thảo Cầm Viên')).toBeInTheDocument();
    expect(screen.queryByText('Chợ Bến Thành')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Xem thêm' }));
    expect(await screen.findByText('Chợ Bến Thành')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Xem thêm' })).not.toBeInTheDocument();

    await userEvent.click(screen.getByText('Thảo Cầm Viên'));
    expect(await screen.findByText('Màn chuyến')).toBeInTheDocument();
  });

  it('shows a past trip with its timeline and a link to the receipt', async () => {
    server.use(
      http.get(apiUrl('/api/v1/trips/trip-2'), () => ok(trip('trip-2', 'COMPLETED', 'Nhà thờ Đức Bà'))),
      http.get(apiUrl('/api/v1/trips/trip-2/history'), () =>
        ok([
          { toStatus: 'MATCHING', actorType: 'SYSTEM', occurredAt: '2026-10-30T02:00:00Z' },
          {
            fromStatus: 'MATCHING',
            toStatus: 'ACCEPTED',
            actorType: 'DRIVER',
            occurredAt: '2026-10-30T02:00:20Z',
          },
          {
            fromStatus: 'IN_TRIP',
            toStatus: 'COMPLETED',
            actorType: 'DRIVER',
            occurredAt: '2026-10-30T02:15:00Z',
          },
        ]),
      ),
    );
    renderAt('/app/activity/trip-2');

    expect(await screen.findByText('Tài xế đã nhận')).toBeInTheDocument();
    expect(screen.getAllByText('bởi tài xế')).toHaveLength(2);
    await userEvent.click(screen.getByRole('link', { name: 'Xem hoá đơn' }));
    expect(await screen.findByText('Màn hoá đơn')).toBeInTheDocument();
  });
});
