import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it } from 'vitest';
import type { Payment, Trip } from '@/shared/api/types';
import { apiUrl, ok, server } from '@/test/server';
import ReceiptPage from './ReceiptPage';

const TRIP: Trip = {
  id: 'trip-1',
  customerId: 'customer-1',
  serviceType: 'RIDE',
  status: 'COMPLETED',
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
  acceptedAt: '2026-10-30T02:00:20Z',
  version: 7,
};

const PAYMENT: Payment = {
  id: 'payment-1',
  tripId: 'trip-1',
  purpose: 'TRIP_FARE',
  customerId: 'customer-1',
  amount: 45000,
  refundedAmount: 0,
  currency: 'VND',
  status: 'SUCCEEDED',
  attemptCount: 1,
  createdAt: '2026-10-30T02:20:00Z',
  succeededAt: '2026-10-30T02:20:01Z',
};

function renderReceipt(trip: Trip, payments: Payment[]) {
  server.use(
    http.get(apiUrl('/api/v1/trips/trip-1'), () => ok(trip)),
    http.get(apiUrl('/api/v1/payments'), () => ok(payments)),
    http.get(apiUrl('/api/v1/quotes/quote-1'), () =>
      ok({
        id: 'quote-1',
        distanceMeters: 4200,
        durationSeconds: 900,
        breakdown: {
          baseFare: 12000,
          distanceFare: 25000,
          timeFare: 8000,
          minimumFareAdjustment: 0,
          surgeAmount: 0,
          roundingAdjustment: 0,
          total: 45000,
          surgeMultiplier: 1,
        },
      }),
    ),
  );
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter([{ path: '/app/trips/:tripId/receipt', element: <ReceiptPage /> }], {
    initialEntries: ['/app/trips/trip-1/receipt'],
  });
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

describe('ReceiptPage', () => {
  it('shows the fare, its parts and the paid status', async () => {
    renderReceipt(TRIP, [PAYMENT]);
    expect(await screen.findByText('Đã thanh toán')).toBeInTheDocument();
    expect(screen.getByText('Cước chuyến', { selector: 'span' })).toBeInTheDocument();
    expect(await screen.findByText('Giá mở cửa')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Thanh toán lại' })).not.toBeInTheDocument();
  });

  it('lets the customer pay a failed charge again', async () => {
    let retried = false;
    server.use(
      http.post(apiUrl('/api/v1/payments/payment-1/retry'), () => {
        retried = true;
        return ok({ ...PAYMENT, status: 'PENDING', attemptCount: 2, succeededAt: undefined });
      }),
    );
    renderReceipt(TRIP, [
      { ...PAYMENT, status: 'FAILED', succeededAt: undefined, failureCode: 'CARD_DECLINED' },
    ]);

    await userEvent.click(await screen.findByRole('button', { name: 'Thanh toán lại' }));
    expect(await screen.findByText('Đang xử lý')).toBeInTheDocument();
    expect(retried).toBe(true);
  });

  it('lists refunds with their reason', async () => {
    server.use(
      http.get(apiUrl('/api/v1/payments/payment-1/refunds'), () =>
        ok([
          {
            id: 'refund-1',
            paymentId: 'payment-1',
            tripId: 'trip-1',
            amount: 15000,
            currency: 'VND',
            reason: 'OVERCHARGE',
            status: 'SUCCEEDED',
            createdAt: '2026-10-30T03:00:00Z',
            completedAt: '2026-10-30T03:00:05Z',
          },
        ]),
      ),
    );
    renderReceipt(TRIP, [{ ...PAYMENT, status: 'PARTIALLY_REFUNDED', refundedAmount: 15000 }]);
    expect(await screen.findByText('Thu dư')).toBeInTheDocument();
    expect(screen.getByText('Đã hoàn một phần')).toBeInTheDocument();
  });

  it('says a cancellation before any driver costs nothing', async () => {
    renderReceipt({ ...TRIP, status: 'CANCELLED', cancelledBy: 'CUSTOMER', acceptedAt: undefined }, []);
    expect(await screen.findByText('Chuyến này không phát sinh khoản thu nào.')).toBeInTheDocument();
  });
});
