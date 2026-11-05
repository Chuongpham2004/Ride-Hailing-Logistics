import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import type { ReactElement } from 'react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { beforeEach, describe, expect, it } from 'vitest';
import type { CurrentUser, Payment } from '@/shared/api/types';
import { useSession } from '@/shared/auth/session';
import { apiUrl, fail, ok, server } from '@/test/server';
import PaymentDetailPage from './PaymentDetailPage';
import WalletPage from './WalletPage';

const FINANCE = {
  id: 'finance-1',
  fullName: 'Vũ Tài Chính',
  roles: ['FINANCE_STAFF'],
  status: 'ACTIVE',
  emailVerified: true,
  phoneVerified: false,
} as CurrentUser;

const PAYMENT_ID = '01a1aae3-1111-7000-8000-000000000001';
const DRIVER_ID = '01a1aae3-2222-7000-8000-000000000002';

const payment = (overrides: Partial<Payment> = {}): Payment => ({
  id: PAYMENT_ID,
  tripId: 'trip-1',
  purpose: 'TRIP_FARE',
  customerId: 'customer-1',
  driverId: DRIVER_ID,
  amount: 38000,
  refundedAmount: 8000,
  currency: 'VND',
  status: 'PARTIALLY_REFUNDED',
  attemptCount: 1,
  provider: 'SANDBOX',
  createdAt: '2026-11-05T02:00:00Z',
  succeededAt: '2026-11-05T02:00:01Z',
  ...overrides,
});

function renderAt(path: string, route: string, element: ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter([{ path: route, element }], { initialEntries: [path] });
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

beforeEach(() => useSession.setState({ status: 'authenticated', user: FINANCE }));

describe('PaymentDetailPage', () => {
  function serve(current: () => Payment) {
    server.use(
      http.get(apiUrl(`/api/v1/payments/${PAYMENT_ID}`), () => ok(current())),
      http.get(apiUrl(`/api/v1/admin/payments/${PAYMENT_ID}/attempts`), () =>
        ok([{ attemptNo: 1, status: 'SUCCEEDED', provider: 'SANDBOX', createdAt: '2026-11-05T02:00:00Z' }]),
      ),
      http.get(apiUrl(`/api/v1/payments/${PAYMENT_ID}/refunds`), () => ok([])),
    );
  }

  it('refunds at most what is left, with an idempotency key, and needs a note for OTHER', async () => {
    let current = payment();
    serve(() => current);
    const requests: { key: string | null; body: unknown }[] = [];
    server.use(
      http.post(apiUrl(`/api/v1/admin/payments/${PAYMENT_ID}/refunds`), async ({ request }) => {
        requests.push({ key: request.headers.get('Idempotency-Key'), body: await request.json() });
        if (requests.length === 1) return fail(503, 'DEPENDENCY_UNAVAILABLE');
        current = payment({ status: 'REFUND_PENDING' });
        return ok({
          id: 'refund-1',
          paymentId: PAYMENT_ID,
          tripId: 'trip-1',
          amount: 30000,
          currency: 'VND',
          reason: 'OTHER',
          status: 'PENDING',
          createdAt: '2026-11-05T03:00:00Z',
        });
      }),
    );
    renderAt(`/admin/payments/${PAYMENT_ID}`, '/admin/payments/:paymentId', <PaymentDetailPage />);
    const user = userEvent.setup();

    expect(await screen.findByText(/Còn hoàn được tối đa 30\.000/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Hoàn tiền' }));
    const dialog = screen.getByRole('dialog');
    const amount = within(dialog).getByLabelText('Số tiền (₫)');
    expect(amount).toHaveValue('30000');

    await user.clear(amount);
    await user.type(amount, '30.001');
    expect(within(dialog).getByText(/Tối đa 30\.000/)).toBeInTheDocument();
    await user.clear(amount);
    await user.type(amount, '30.000');

    await user.selectOptions(within(dialog).getByLabelText('Lý do'), 'OTHER');
    const submit = within(dialog).getByRole('button', { name: /^Hoàn 30\.000/ });
    expect(submit).toBeDisabled();
    await user.type(within(dialog).getByLabelText(/Ghi chú nội bộ/), 'Khách khiếu nại qua tổng đài');

    // First try fails on the network side; the retry must reuse the same key.
    await user.click(submit);
    expect(await within(dialog).findByRole('alert')).toBeInTheDocument();
    await user.click(submit);

    expect(await screen.findByText(/Đã gửi yêu cầu hoàn 30\.000/)).toBeInTheDocument();
    expect(requests).toHaveLength(2);
    expect(requests[0]!.key).toMatch(/^[A-Za-z0-9_-]{8,100}$/);
    expect(requests[1]!.key).toBe(requests[0]!.key);
    expect(requests[1]!.body).toEqual({
      amount: 30000,
      reason: 'OTHER',
      note: 'Khách khiếu nại qua tổng đài',
    });
    expect(await screen.findByText(/Đang chờ nhà cung cấp/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Hoàn tiền' })).toBeDisabled();
  });
});

describe('WalletPage', () => {
  function serveWallet(balance: number) {
    server.use(
      http.get(apiUrl(`/api/v1/admin/wallets/${DRIVER_ID}`), () =>
        ok({
          walletId: 'w1',
          driverId: DRIVER_ID,
          balance,
          currency: 'VND',
          entries: [
            {
              id: 'e1',
              type: 'EARNING',
              amount: balance,
              balanceAfter: balance,
              referenceType: 'PAYMENT',
              referenceId: PAYMENT_ID,
              tripId: 'trip-1',
              createdAt: '2026-11-05T02:00:01Z',
            },
          ],
        }),
      ),
    );
  }

  it('debits with a negative amount and cannot take more than the balance', async () => {
    serveWallet(30000);
    let sent: unknown;
    server.use(
      http.post(apiUrl(`/api/v1/admin/wallets/${DRIVER_ID}/adjustments`), async ({ request }) => {
        sent = await request.json();
        return ok({
          id: 'a1',
          walletId: 'w1',
          driverId: DRIVER_ID,
          amount: -5000,
          currency: 'VND',
          reason: 'EARNING_CORRECTION',
          balanceAfter: 25000,
          requestedBy: 'finance-1',
          createdAt: '2026-11-05T03:00:00Z',
        });
      }),
    );
    renderAt(`/admin/wallets/${DRIVER_ID}`, '/admin/wallets/:driverId', <WalletPage />);
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Tạo điều chỉnh' }));
    const dialog = screen.getByRole('dialog');
    await user.selectOptions(within(dialog).getByLabelText('Lý do'), 'EARNING_CORRECTION');
    await user.click(within(dialog).getByLabelText('Trừ khỏi ví'));
    await user.type(within(dialog).getByLabelText('Số tiền (₫)'), '40000');
    expect(within(dialog).getByText(/Số dư chỉ còn 30\.000/)).toBeInTheDocument();
    await user.clear(within(dialog).getByLabelText('Số tiền (₫)'));
    await user.type(within(dialog).getByLabelText('Số tiền (₫)'), '5000');
    await user.click(within(dialog).getByRole('button', { name: /^Trừ 5\.000/ }));

    expect(await screen.findByText(/Đã trừ 5\.000.*Số dư mới 25\.000/)).toBeInTheDocument();
    expect(sent).toEqual({ amount: -5000, reason: 'EARNING_CORRECTION' });
  });

  it('asks for the refund id on a clawback', async () => {
    serveWallet(30000);
    renderAt(`/admin/wallets/${DRIVER_ID}`, '/admin/wallets/:driverId', <WalletPage />);
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Tạo điều chỉnh' }));
    const dialog = screen.getByRole('dialog');
    await user.selectOptions(within(dialog).getByLabelText('Lý do'), 'REFUND_CLAWBACK');
    expect(within(dialog).queryByLabelText('Cộng vào ví')).not.toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Số tiền (₫)'), '5000');
    expect(within(dialog).getByRole('button', { name: /^Trừ 5\.000/ })).toBeDisabled();
    await user.type(
      within(dialog).getByLabelText('Mã lần hoàn tiền'),
      '01a1aae3-3333-7000-8000-000000000003',
    );
    expect(within(dialog).getByRole('button', { name: /^Trừ 5\.000/ })).toBeEnabled();
  });
});
