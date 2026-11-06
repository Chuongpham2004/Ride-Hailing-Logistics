import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { beforeEach, describe, expect, it } from 'vitest';
import { tokens } from '@/shared/api/tokens';
import { useSession } from '@/shared/auth/session';
import { apiUrl, fail, ok, server } from '@/test/server';
import ForgotPasswordPage from './ForgotPasswordPage';
import LoginPage from './LoginPage';

function renderAt(path: string) {
  const router = createMemoryRouter(
    [
      { path: '/forgot-password', element: <ForgotPasswordPage /> },
      { path: '/login', element: <LoginPage /> },
      { path: '/app', element: <p>Khu khách hàng</p> },
    ],
    { initialEntries: [path] },
  );
  render(<RouterProvider router={router} />);
}

const tokenPair = {
  tokenType: 'Bearer',
  accessToken: 'a',
  expiresIn: 900,
  refreshToken: 'r',
  refreshExpiresIn: 1000,
};

const CUSTOMER = {
  id: 'u1',
  email: 'khach@rhl.local',
  fullName: 'Khách',
  roles: ['CUSTOMER'],
  status: 'ACTIVE',
  emailVerified: true,
  phoneVerified: false,
};

beforeEach(() => {
  tokens.clear();
  useSession.setState({ status: 'anonymous', user: null });
});

describe('ForgotPasswordPage', () => {
  it('sends a code, then sets the new password and signs in with it', async () => {
    const requests: unknown[] = [];
    let confirmed: unknown;
    let attempts = 0;
    server.use(
      http.post(apiUrl('/api/v1/auth/password-reset'), async ({ request }) => {
        requests.push(await request.json());
        return ok(
          { message: 'If an account uses this email or phone number, a reset code has been sent to it' },
          202,
        );
      }),
      http.post(apiUrl('/api/v1/auth/password-reset/confirm'), async ({ request }) => {
        attempts += 1;
        if (attempts === 1) return fail(400, 'VALIDATION_ERROR');
        confirmed = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
      http.post(apiUrl('/api/v1/auth/login'), async ({ request }) => {
        const body = (await request.json()) as { password: string };
        return body.password === 'mat-khau-moi-123' ? ok(tokenPair) : fail(401, 'INVALID_CREDENTIALS');
      }),
      http.get(apiUrl('/api/v1/users/me'), () => ok(CUSTOMER)),
    );
    renderAt('/forgot-password');
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Email hoặc số điện thoại'), 'khach@rhl.local');
    await user.click(screen.getByRole('button', { name: 'Gửi mã' }));

    // The same answer whether or not the account exists.
    expect(await screen.findByText(/thuộc một tài khoản đang hoạt động/)).toBeInTheDocument();
    expect(requests).toEqual([{ identifier: 'khach@rhl.local' }]);
    expect(screen.getByRole('button', { name: /Gửi lại mã sau/ })).toBeDisabled();

    await user.type(screen.getByLabelText('Mã xác nhận'), '123456');
    await user.type(screen.getByLabelText('Mật khẩu mới'), 'mat-khau-moi-123');
    await user.type(screen.getByLabelText('Nhập lại mật khẩu mới'), 'mat-khau-moi-123');
    await user.click(screen.getByRole('button', { name: 'Đặt mật khẩu mới' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Mã chưa đúng hoặc đã hết hạn');

    await user.click(screen.getByRole('button', { name: 'Đặt mật khẩu mới' }));
    expect(await screen.findByText('Khu khách hàng')).toBeInTheDocument();
    expect(confirmed).toEqual({
      identifier: 'khach@rhl.local',
      code: '123456',
      newPassword: 'mat-khau-moi-123',
    });
  });

  it('checks the code and the new password before sending', async () => {
    server.use(http.post(apiUrl('/api/v1/auth/password-reset'), () => ok({ message: 'sent' }, 202)));
    renderAt('/forgot-password?identifier=0912345678');
    const user = userEvent.setup();

    expect(screen.getByLabelText('Email hoặc số điện thoại')).toHaveValue('0912345678');
    await user.click(screen.getByRole('button', { name: 'Gửi mã' }));
    await user.type(await screen.findByLabelText('Mã xác nhận'), '12a');
    await user.type(screen.getByLabelText('Mật khẩu mới'), '0912345678');
    await user.type(screen.getByLabelText('Nhập lại mật khẩu mới'), 'khac-nhau');
    await user.click(screen.getByRole('button', { name: 'Đặt mật khẩu mới' }));

    expect(await screen.findByText('Mã gồm 6 chữ số.')).toBeInTheDocument();
    expect(screen.getByText('Mật khẩu không được trùng email hoặc số điện thoại.')).toBeInTheDocument();
    expect(screen.getByText('Hai lần nhập mật khẩu chưa khớp.')).toBeInTheDocument();
  });

  it('lets the person go back and type another address', async () => {
    server.use(http.post(apiUrl('/api/v1/auth/password-reset'), () => ok({ message: 'sent' }, 202)));
    renderAt('/forgot-password');
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Email hoặc số điện thoại'), 'sai@rhl.local');
    await user.click(screen.getByRole('button', { name: 'Gửi mã' }));
    await user.click(await screen.findByRole('button', { name: 'Đổi địa chỉ' }));
    expect(screen.getByLabelText('Email hoặc số điện thoại')).toBeInTheDocument();
  });

  it('is linked from the login page, which shows a notice after a reset', async () => {
    renderAt('/login?identifier=khach%40rhl.local&reset=1');
    expect(screen.getByLabelText('Email hoặc số điện thoại')).toHaveValue('khach@rhl.local');
    expect(screen.getByRole('status')).toHaveTextContent('Đã đặt mật khẩu mới');
    expect(screen.getByRole('link', { name: 'Quên mật khẩu?' })).toHaveAttribute(
      'href',
      '/forgot-password?portal=customer',
    );
  });
});
