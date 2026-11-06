import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Trip } from '@/shared/api/types';
import { apiUrl, ok, server } from '@/test/server';
import DriverTripPage from './DriverTripPage';
import { useDriverLocation } from './location';

vi.mock('@/shared/map/MapView', () => ({ MapView: () => <div data-testid="map" /> }));

vi.mock('@/shared/realtime/realtime', () => ({
  realtime: { getStatus: () => 'open' },
  useRealtimeResync: () => {},
  useRealtimeMessages: () => {},
}));

const TRIP: Trip = {
  id: 'trip-1',
  customerId: 'customer-1',
  driverId: 'driver-1',
  serviceType: 'RIDE',
  status: 'ACCEPTED',
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
  createdAt: '2026-11-05T02:00:00Z',
  version: 2,
};

const DELIVERY: Partial<Trip> = {
  serviceType: 'DELIVERY',
  delivery: {
    recipientName: 'Chị Lan',
    recipientPhone: '0901234567',
    packageDescription: 'Tài liệu',
    packageSize: 'SMALL',
    packageWeightGrams: 500,
  },
};

const AT_DROPOFF = { latitude: 10.79, longitude: 106.72 };

function renderTrip(initial: Trip) {
  let current = initial;
  const steps: { step: string; body: unknown }[] = [];
  server.use(
    http.get(apiUrl('/api/v1/trips/trip-1'), () => ok(current)),
    http.post(apiUrl('/api/v1/trips/trip-1/:step'), async ({ params, request }) => {
      const text = await request.text();
      steps.push({ step: String(params.step), body: text ? JSON.parse(text) : null });
      return HttpResponse.json(null, { status: 500 });
    }),
  );
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(
    [
      { path: '/driver/trips/:tripId', element: <DriverTripPage /> },
      { path: '/driver', element: <p>Trang chủ tài xế</p> },
    ],
    { initialEntries: ['/driver/trips/trip-1'] },
  );
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return {
    steps,
    /** Answers the next step with this trip (or error). */
    answer(next: Trip | Response) {
      server.use(
        http.post(
          apiUrl('/api/v1/trips/trip-1/:step'),
          async ({ params, request }) => {
            const text = await request.text();
            steps.push({ step: String(params.step), body: text ? JSON.parse(text) : null });
            if (next instanceof Response) return next;
            current = next;
            return ok(next);
          },
          { once: true },
        ),
      );
    },
  };
}

describe('DriverTripPage', () => {
  afterEach(() => useDriverLocation.setState({ position: null, mode: 'gps', target: null }));

  it('walks a ride from accepted to completed, one button per status', async () => {
    // At the pickup already, so "arrived" does not ask again.
    useDriverLocation.setState({ position: { latitude: 10.77, longitude: 106.7, accuracyMeters: 5 } });
    const page = renderTrip(TRIP);

    page.answer({ ...TRIP, status: 'PICKING_UP', version: 3 });
    await userEvent.click(await screen.findByRole('button', { name: 'Bắt đầu đi đón' }));

    page.answer({ ...TRIP, status: 'ARRIVED', version: 4 });
    await userEvent.click(await screen.findByRole('button', { name: 'Đã đến điểm đón' }));

    page.answer({ ...TRIP, status: 'IN_TRIP', version: 5 });
    await userEvent.type(await screen.findByLabelText(/mã đón/), '48a2913');
    await userEvent.click(screen.getByRole('button', { name: 'Bắt đầu chuyến' }));

    // Driven to the drop-off, so "complete" does not ask again either.
    act(() => useDriverLocation.setState({ position: { ...AT_DROPOFF, accuracyMeters: 5 } }));
    page.answer({ ...TRIP, status: 'COMPLETED', version: 6 });
    await userEvent.click(await screen.findByRole('button', { name: 'Hoàn tất chuyến' }));

    expect(await screen.findByText('Chuyến đi đã hoàn tất')).toBeInTheDocument();
    expect(page.steps).toEqual([
      { step: 'start-pickup', body: null },
      { step: 'arrive', body: null },
      { step: 'start', body: { code: '482913' } },
      { step: 'complete', body: null },
    ]);
  });

  it('asks again before "arrived" when the car is far from the pickup', async () => {
    useDriverLocation.setState({ position: { latitude: 10.8, longitude: 106.7, accuracyMeters: 5 } });
    const page = renderTrip({ ...TRIP, status: 'PICKING_UP', version: 3 });
    await userEvent.click(await screen.findByRole('button', { name: 'Đã đến điểm đón' }));

    expect(screen.getByRole('alert')).toHaveTextContent(/Bạn còn cách điểm đón khoảng 3,3 km/);
    expect(page.steps).toEqual([]);

    page.answer({ ...TRIP, status: 'ARRIVED', version: 4 });
    await userEvent.click(screen.getByRole('button', { name: 'Vẫn báo đã đến' }));
    expect(await screen.findByLabelText(/mã đón/)).toBeInTheDocument();
    expect(page.steps).toEqual([{ step: 'arrive', body: null }]);
  });

  it('says a wrong code is wrong, and when the code is locked', async () => {
    const page = renderTrip({ ...TRIP, status: 'ARRIVED', version: 4 });
    page.answer(
      HttpResponse.json(
        { code: 'BUSINESS_RULE_VIOLATION', message: 'The pickup code is wrong' },
        { status: 422 },
      ),
    );
    await userEvent.type(await screen.findByLabelText(/mã đón/), '111111');
    await userEvent.click(screen.getByRole('button', { name: 'Bắt đầu chuyến' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Mã đón chưa đúng');

    page.answer(
      HttpResponse.json(
        { code: 'BUSINESS_RULE_VIOLATION', message: 'Too many wrong pickup codes; contact support' },
        { status: 422 },
      ),
    );
    await userEvent.click(screen.getByRole('button', { name: 'Bắt đầu chuyến' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('mã đã bị khoá');
  });

  it('asks again before completing far from the drop-off, then completes on request', async () => {
    useDriverLocation.setState({ position: { latitude: 10.77, longitude: 106.7, accuracyMeters: 5 } });
    const page = renderTrip({ ...TRIP, status: 'IN_TRIP', version: 5 });
    await userEvent.click(await screen.findByRole('button', { name: 'Hoàn tất chuyến' }));

    expect(screen.getByRole('alert')).toHaveTextContent(/Bạn còn cách điểm đến khoảng 3,1 km/);
    expect(page.steps).toEqual([]);
    await userEvent.click(screen.getByRole('button', { name: 'Chưa, đi tiếp' }));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Hoàn tất chuyến' }));
    page.answer({ ...TRIP, status: 'COMPLETED', version: 6 });
    await userEvent.click(screen.getByRole('button', { name: 'Vẫn hoàn tất chuyến' }));
    expect(await screen.findByText('Chuyến đi đã hoàn tất')).toBeInTheDocument();
    expect(page.steps).toEqual([{ step: 'complete', body: null }]);
  });

  it('needs the delivery code and shows the recipient for a delivery', async () => {
    useDriverLocation.setState({ position: { ...AT_DROPOFF, accuracyMeters: 5 } });
    const page = renderTrip({ ...TRIP, ...DELIVERY, status: 'IN_TRIP', version: 5 });
    expect(await screen.findByText('Chị Lan')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '0901234567' })).toHaveAttribute('href', 'tel:0901234567');

    page.answer({ ...TRIP, ...DELIVERY, status: 'COMPLETED', version: 6 });
    await userEvent.type(screen.getByLabelText(/mã giao/), '7351');
    await userEvent.click(screen.getByRole('button', { name: 'Hoàn tất giao hàng' }));

    expect(await screen.findByText('Đã giao hàng')).toBeInTheDocument();
    expect(page.steps).toEqual([{ step: 'complete', body: { code: '7351' } }]);
  });

  it('cancels with the no-show reason at the pickup', async () => {
    let body: unknown = null;
    renderTrip({ ...TRIP, status: 'ARRIVED', version: 4 });
    server.use(
      http.post(apiUrl('/api/v1/trips/trip-1/cancel'), async ({ request }) => {
        body = await request.json();
        return ok({
          ...TRIP,
          status: 'CANCELLED',
          cancelledBy: 'DRIVER',
          cancelReason: 'CUSTOMER_NO_SHOW',
          version: 5,
        });
      }),
    );
    await userEvent.click(await screen.findByRole('button', { name: 'Huỷ chuyến' }));
    expect(screen.getByRole('radio', { name: 'Khách không ra điểm đón' })).toBeChecked();
    await userEvent.click(screen.getByRole('button', { name: 'Huỷ chuyến' }));

    expect(await screen.findByText('Bạn đã huỷ chuyến')).toBeInTheDocument();
    expect(screen.getByText(/khách không ra điểm đón/)).toBeInTheDocument();
    expect(body).toEqual({ reason: 'CUSTOMER_NO_SHOW' });
  });
});
