import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { beforeEach, describe, expect, it } from 'vitest';
import { tokens } from '@/shared/api/tokens';
import { useSession } from '@/shared/auth/session';
import { apiUrl, fail, ok, server } from '@/test/server';
import AdminLoginPage from './AdminLoginPage';
import DriverLoginPage from './DriverLoginPage';
import LoginPage from './LoginPage';

function renderAt(path: string) {
  const router = createMemoryRouter(
    [
      { path: '/login', element: <LoginPage /> },
      { path: '/driver/login', element: <DriverLoginPage /> },
      { path: '/admin/login', element: <AdminLoginPage /> },
      { path: '/admin', element: <p>Khu nhân viên</p> },
      { path: '/app', element: <p>Khu khách hàng</p> },
      { path: '/driver', element: <p>Khu tài xế</p> },
      { path: '/app/activity', element: <p>Hoạt động</p> },
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

beforeEach(() => {
  tokens.clear();
  useSession.setState({ status: 'anonymous', user: null });
});

describe('LoginPage', () => {
  it('asks for both fields before calling the server', async () => {
    renderAt('/login');
    await userEvent.click(screen.getByRole('button', { name: 'Đăng nhập' }));

    expect(await screen.findByText('Nhập email hoặc số điện thoại.')).toBeInTheDocument();
    expect(screen.getByText('Nhập mật khẩu.')).toBeInTheDocument();
  });

  it('shows its own wording for wrong credentials, not the server message', async () => {
    server.use(http.post(apiUrl('/api/v1/auth/login'), () => fail(401, 'INVALID_CREDENTIALS')));
    renderAt('/login');

    await userEvent.type(screen.getByLabelText('Email hoặc số điện thoại'), 'khach@rhl.local');
    await userEvent.type(screen.getByLabelText('Mật khẩu'), 'sai-mat-khau');
    await userEvent.click(screen.getByRole('button', { name: 'Đăng nhập' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Email, số điện thoại hoặc mật khẩu chưa đúng.',
    );
    expect(screen.queryByText(/server says/)).not.toBeInTheDocument();
  });

  const account = (roles: string[]) => ({
    id: 'u1',
    fullName: 'Người dùng',
    roles,
    status: 'ACTIVE',
    emailVerified: true,
    phoneVerified: false,
  });

  async function signIn(roles: string[]) {
    let revoked = false;
    server.use(
      http.post(apiUrl('/api/v1/auth/login'), () => ok(tokenPair)),
      http.get(apiUrl('/api/v1/users/me'), () => ok(account(roles))),
      http.post(apiUrl('/api/v1/auth/logout'), () => {
        revoked = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    await userEvent.type(screen.getByLabelText('Email hoặc số điện thoại'), 'nguoidung@rhl.local');
    await userEvent.type(screen.getByLabelText('Mật khẩu'), 'mat-khau-dung');
    await userEvent.click(screen.getByRole('button', { name: 'Đăng nhập' }));
    return () => revoked;
  }

  it('lets a driver in through the driver sign-in page', async () => {
    renderAt('/driver/login');
    expect(screen.getByRole('heading', { name: 'Đăng nhập tài xế' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Đăng ký tài xế' })).toHaveAttribute(
      'href',
      '/register?role=DRIVER',
    );
    await signIn(['DRIVER']);
    expect(await screen.findByText('Khu tài xế')).toBeInTheDocument();
  });

  it('turns a driver away from the customer sign-in page and points to the right one', async () => {
    renderAt('/login');
    const revoked = await signIn(['DRIVER']);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Đây không phải tài khoản khách hàng');
    expect(screen.getByRole('link', { name: 'đăng nhập tài xế' })).toHaveAttribute('href', '/driver/login');
    await waitFor(() => expect(revoked()).toBe(true));
    expect(useSession.getState().user).toBeNull();
  });

  it('lets staff in only through the staff page, which has no sign-up', async () => {
    renderAt('/admin/login');
    expect(screen.getByRole('heading', { name: 'Đăng nhập nhân viên' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Đăng ký/ })).not.toBeInTheDocument();
    await signIn(['REVIEWER']);
    expect(await screen.findByText('Khu nhân viên')).toBeInTheDocument();
  });

  it('lets an account with several roles choose its area by the page it uses', async () => {
    renderAt('/admin/login');
    await signIn(['CUSTOMER', 'ADMINISTRATOR']);
    expect(await screen.findByText('Khu nhân viên')).toBeInTheDocument();
  });

  it('does not follow a next address into another area', async () => {
    renderAt('/driver/login?next=%2Fadmin%2Fpricing');
    await signIn(['DRIVER', 'ADMINISTRATOR']);
    expect(await screen.findByText('Khu tài xế')).toBeInTheDocument();
  });

  it('returns to the page that asked for a login, but never to another site', async () => {
    const customer = {
      id: 'c1',
      fullName: 'Khách',
      roles: ['CUSTOMER'],
      status: 'ACTIVE',
      emailVerified: true,
      phoneVerified: true,
    };
    server.use(
      http.post(apiUrl('/api/v1/auth/login'), () => ok(tokenPair)),
      http.get(apiUrl('/api/v1/users/me'), () => ok(customer)),
    );
    renderAt('/login?next=%2F%2Fevil.example');

    await userEvent.type(screen.getByLabelText('Email hoặc số điện thoại'), 'khach@rhl.local');
    await userEvent.type(screen.getByLabelText('Mật khẩu'), 'mat-khau-dung');
    await userEvent.click(screen.getByRole('button', { name: 'Đăng nhập' }));

    expect(await screen.findByText('Khu khách hàng')).toBeInTheDocument();
  });
});
