import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it } from 'vitest';
import type { Quote } from '@/shared/api/types';
import { apiUrl, ok, server } from '@/test/server';
import { BookPanel } from './BookPanel';

const QUOTE = {
  id: 'quote-1',
  serviceType: 'RIDE',
  surgeMultiplier: 1,
  surgeConfirmationRequired: false,
} as Quote;

function renderPanel(quote: Quote) {
  const queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  const router = createMemoryRouter(
    [
      { path: '/app/quote/:quoteId', element: <BookPanel quote={quote} /> },
      { path: '/app/trips/:tripId', element: <p>Màn chuyến</p> },
    ],
    { initialEntries: [`/app/quote/${quote.id}`] },
  );
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

function captureBooking() {
  const seen: { key: string | null; body: unknown }[] = [];
  server.use(
    http.post(apiUrl('/api/v1/trips'), async ({ request }) => {
      seen.push({ key: request.headers.get('Idempotency-Key'), body: await request.json() });
      return ok({ id: 'trip-1' }, 201);
    }),
  );
  return seen;
}

describe('BookPanel', () => {
  it('books a ride with an idempotency key and opens the trip', async () => {
    const seen = captureBooking();
    renderPanel(QUOTE);
    await userEvent.click(screen.getByRole('button', { name: 'Đặt chuyến' }));

    expect(await screen.findByText('Màn chuyến')).toBeInTheDocument();
    expect(seen).toHaveLength(1);
    expect(seen[0]!.key).toMatch(/^[A-Za-z0-9_-]{8,100}$/);
    expect(seen[0]!.body).toEqual({ quoteId: 'quote-1' });
  });

  it('needs the surge accepted and sends the multiplier', async () => {
    const seen = captureBooking();
    renderPanel({ ...QUOTE, surgeMultiplier: 1.5, surgeConfirmationRequired: true });
    const book = screen.getByRole('button', { name: 'Đặt chuyến' });
    expect(book).toBeDisabled();

    await userEvent.click(screen.getByRole('checkbox', { name: /giá cao điểm x1,5/ }));
    await userEvent.click(book);

    expect(await screen.findByText('Màn chuyến')).toBeInTheDocument();
    expect(seen[0]!.body).toEqual({ quoteId: 'quote-1', acceptedSurgeMultiplier: 1.5 });
  });

  it('collects the recipient and package for a delivery', async () => {
    const seen = captureBooking();
    renderPanel({ ...QUOTE, serviceType: 'DELIVERY' });

    await userEvent.click(screen.getByRole('button', { name: 'Đặt giao hàng' }));
    expect(screen.getByText('Nhập tên người nhận.')).toBeInTheDocument();
    expect(seen).toHaveLength(0);

    await userEvent.type(screen.getByLabelText('Tên người nhận'), 'Trần Thị B');
    await userEvent.type(screen.getByLabelText('Số điện thoại người nhận'), '0901 234 567');
    await userEvent.type(screen.getByLabelText('Món hàng'), 'Hộp bánh');
    await userEvent.click(screen.getByRole('button', { name: /Vừa/ }));
    await userEvent.type(screen.getByLabelText('Khối lượng (kg)'), '1.5');
    await userEvent.click(screen.getByRole('button', { name: 'Đặt giao hàng' }));

    expect(await screen.findByText('Màn chuyến')).toBeInTheDocument();
    expect(seen[0]!.body).toEqual({
      quoteId: 'quote-1',
      delivery: {
        recipientName: 'Trần Thị B',
        recipientPhone: '0901234567',
        packageDescription: 'Hộp bánh',
        packageSize: 'MEDIUM',
        packageWeightGrams: 1500,
      },
    });
  });
});
