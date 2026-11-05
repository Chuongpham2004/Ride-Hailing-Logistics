import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { beforeEach, describe, expect, it } from 'vitest';
import type { CurrentUser } from '@/shared/api/types';
import { tokens } from '@/shared/api/tokens';
import { useSession } from '@/shared/auth/session';
import { apiUrl, fail, ok, server } from '@/test/server';
import VerifyPage from './VerifyPage';

const CUSTOMER: CurrentUser = {
  id: 'u1',
  email: 'khach@rhl.local',
  fullName: 'Khách',
  roles: ['CUSTOMER'],
  status: 'ACTIVE',
  emailVerified: false,
  phoneVerified: false,
};

function renderAt(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(
    [
      { path: '/verify/:channel', element: <VerifyPage /> },
      { path: '/login', element: <p>Trang đăng nhập</p> },
      { path: '/app', element: <p>Khu khách hàng</p> },
    ],
    { initialEntries: [path] },
  );
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

beforeEach(() => {
  tokens.clear();
  useSession.setState({ status: 'authenticated', user: CUSTOMER });
});

describe('VerifyPage', () => {
  it('sends a code, confirms it, and offers the way back', async () => {
    let verified = false;
    let confirmed: unknown;
    let attempts = 0;
    server.use(
      http.post(apiUrl('/api/v1/users/me/contacts/email/verification'), () =>
        ok(
          { channel: 'EMAIL', destination: 'k***@rhl.local', expiresInSeconds: 600, resendAfterSeconds: 60 },
          202,
        ),
      ),
      http.post(apiUrl('/api/v1/users/me/contacts/email/verification/confirm'), async ({ request }) => {
        attempts += 1;
        if (attempts === 1) return fail(400, 'VALIDATION_ERROR');
        confirmed = await request.json();
        verified = true;
        return new HttpResponse(null, { status: 204 });
      }),
      http.get(apiUrl('/api/v1/users/me'), () => ok({ ...CUSTOMER, emailVerified: verified })),
    );
    renderAt('/verify/email?next=/app/account');
    const user = userEvent.setup();

    expect(screen.getByRole('heading', { name: 'Xác minh email' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Gửi mã xác minh' }));
    const code = await screen.findByLabelText(/Mã 6 số đã gửi tới k\*\*\*@rhl\.local/);
    expect(screen.getByRole('button', { name: /Gửi lại mã sau/ })).toBeDisabled();

    await user.type(code, '111111');
    await user.click(screen.getByRole('button', { name: 'Xác nhận' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Mã chưa đúng hoặc đã hết hạn.');

    await user.clear(code);
    await user.type(code, '123456');
    await user.click(screen.getByRole('button', { name: 'Xác nhận' }));
    expect(await screen.findByText('Email đã được xác minh.')).toBeInTheDocument();
    expect(confirmed).toEqual({ code: '123456' });
    expect(screen.getByRole('link', { name: 'Tiếp tục' })).toHaveAttribute('href', '/app/account');
  });

  it('says so when the account has no such contact', () => {
    renderAt('/verify/phone');
    expect(screen.getByRole('alert')).toHaveTextContent('Tài khoản chưa có số điện thoại');
    expect(screen.getByRole('link', { name: 'Để sau' })).toHaveAttribute('href', '/app');
  });

  it('sends a signed-out visitor to the login page and back', () => {
    useSession.setState({ status: 'anonymous', user: null });
    const router = renderAt('/verify/email?next=/app/account');
    expect(screen.getByText('Trang đăng nhập')).toBeInTheDocument();
    expect(router.state.location.search).toBe(
      `?next=${encodeURIComponent('/verify/email?next=/app/account')}`,
    );
  });

  it('ignores a next address that leaves the site', () => {
    useSession.setState({ status: 'authenticated', user: { ...CUSTOMER, emailVerified: true } });
    renderAt('/verify/email?next=//evil.example');
    expect(screen.getByRole('link', { name: 'Tiếp tục' })).toHaveAttribute('href', '/app');
  });
});
