import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { beforeEach, describe, expect, it } from 'vitest';
import { tokens } from '@/shared/api/tokens';
import { useSession } from '@/shared/auth/session';
import { apiUrl, fail, ok, server } from '@/test/server';
import RegisterPage from './RegisterPage';

function renderRegister() {
  const router = createMemoryRouter(
    [
      { path: '/register', element: <RegisterPage /> },
      { path: '/login', element: <p>Trang đăng nhập</p> },
      { path: '/app', element: <p>Khu khách hàng</p> },
      { path: '/driver', element: <p>Khu tài xế</p> },
    ],
    { initialEntries: ['/register'] },
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

beforeEach(() => {
  tokens.clear();
  useSession.setState({ status: 'anonymous', user: null });
});

async function fill(fields: Record<string, string>) {
  for (const [label, value] of Object.entries(fields)) {
    await userEvent.type(screen.getByLabelText(label), value);
  }
}

describe('RegisterPage', () => {
  it('needs an email or a phone and matching passwords before calling the server', async () => {
    let called = false;
    server.use(
      http.post(apiUrl('/api/v1/auth/register'), () => {
        called = true;
        return ok({});
      }),
    );
    renderRegister();
    await fill({ 'Họ và tên': 'Nguyễn Văn A', 'Mật khẩu': 'Passw0rd!x', 'Nhập lại mật khẩu': 'other-pass' });
    await userEvent.click(screen.getByRole('button', { name: 'Đăng ký' }));

    expect(await screen.findByText('Nhập email hoặc số điện thoại (ít nhất một).')).toBeInTheDocument();
    expect(screen.getByText('Hai lần nhập mật khẩu chưa khớp.')).toBeInTheDocument();
    expect(called).toBe(false);
  });

  it('signs up a driver, signs in and opens the driver area', async () => {
    let body: unknown = null;
    server.use(
      http.post(apiUrl('/api/v1/auth/register'), async ({ request }) => {
        body = await request.json();
        return ok({ id: 'u1' }, 201);
      }),
      http.post(apiUrl('/api/v1/auth/login'), () => ok(tokenPair)),
      http.get(apiUrl('/api/v1/users/me'), () =>
        ok({
          id: 'u1',
          fullName: 'Nguyễn Văn A',
          roles: ['DRIVER'],
          status: 'ACTIVE',
          emailVerified: false,
          phoneVerified: false,
        }),
      ),
    );
    renderRegister();
    await userEvent.click(screen.getByText('Chạy xe, giao hàng'));
    await fill({
      'Họ và tên': 'Nguyễn Văn A',
      'Số điện thoại': '0912 345 678',
      'Mật khẩu': 'Passw0rd!x',
      'Nhập lại mật khẩu': 'Passw0rd!x',
    });
    await userEvent.click(screen.getByRole('button', { name: 'Đăng ký' }));

    expect(await screen.findByText('Khu tài xế')).toBeInTheDocument();
    expect(body).toEqual({
      role: 'DRIVER',
      fullName: 'Nguyễn Văn A',
      phone: '0912345678',
      password: 'Passw0rd!x',
    });
  });

  it('points to the login page when the account already exists', async () => {
    server.use(http.post(apiUrl('/api/v1/auth/register'), () => fail(409, 'CONFLICT')));
    renderRegister();
    await fill({
      'Họ và tên': 'Nguyễn Văn A',
      Email: 'a@example.com',
      'Mật khẩu': 'Passw0rd!x',
      'Nhập lại mật khẩu': 'Passw0rd!x',
    });
    await userEvent.click(screen.getByRole('button', { name: 'Đăng ký' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('đã có tài khoản');
    expect(screen.getByRole('link', { name: 'Đến trang đăng nhập' })).toBeInTheDocument();
  });
});
