import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { apiUrl, ok, server } from '@/test/server';
import EarningsPage from './EarningsPage';

const bus = vi.hoisted(() => {
  const listeners = new Set<(message: unknown) => void>();
  return { listeners, push: (m: unknown) => listeners.forEach((l) => l(m)) };
});

vi.mock('@/shared/realtime/realtime', async () => {
  const { useEffect, useRef } = await import('react');
  return {
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

const entry = (id: string, type: string, amount: number, balanceAfter: number) => ({
  id,
  type,
  amount,
  balanceAfter,
  referenceType: 'PAYMENT',
  referenceId: 'p1',
  tripId: 'trip-1',
  createdAt: '2026-11-05T03:00:00Z',
});

function renderEarnings() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter([{ path: '/driver/earnings', element: <EarningsPage /> }], {
    initialEntries: ['/driver/earnings'],
  });
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

describe('EarningsPage', () => {
  it('shows the balance, signed lines, the next page and reloads on a wallet event', async () => {
    let balance = 36000;
    server.use(
      http.get(apiUrl('/api/v1/wallets/me'), ({ request }) => {
        const before = new URL(request.url).searchParams.get('before');
        if (before === 'cursor-1') {
          return ok({
            driverId: 'd1',
            balance,
            currency: 'VND',
            entries: [entry('e3', 'ADJUSTMENT', 5000, 5000)],
          });
        }
        return ok({
          walletId: 'w1',
          driverId: 'd1',
          balance,
          currency: 'VND',
          entries: [entry('e2', 'COMMISSION', -9000, balance), entry('e1', 'EARNING', 45000, 45000)],
          nextBefore: 'cursor-1',
        });
      }),
    );
    renderEarnings();

    const balanceCard = (await screen.findByText('Số dư ví')).parentElement!;
    expect(balanceCard).toHaveTextContent('36.000');
    expect(screen.getByText('Phí nền tảng').closest('div')!.parentElement).toHaveTextContent('−9.000');
    expect(screen.getByText('Tiền chuyến').closest('div')!.parentElement).toHaveTextContent('+45.000');

    await userEvent.click(screen.getByRole('button', { name: 'Xem thêm' }));
    expect(await screen.findByText('Điều chỉnh')).toBeInTheDocument();

    balance = 72000;
    act(() =>
      bus.push({
        messageId: 'm1',
        type: 'DRIVER_EARNING_POSTED',
        version: 1,
        sentAt: '',
        sequence: 1,
        data: {},
      }),
    );
    await vi.waitFor(() => expect(screen.getByText('Số dư ví').parentElement).toHaveTextContent('72.000'));
  });
});
