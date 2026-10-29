import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { beforeEach, describe, expect, it } from 'vitest';
import { tokens } from '@/shared/api/tokens';
import { useSession } from '@/shared/auth/session';
import { apiUrl, fail, ok, server } from '@/test/server';
import LoginPage from './LoginPage';

function renderAt(path: string) {
  const router = createMemoryRouter(
    [
      { path: '/login', element: <LoginPage /> },
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

  it('sends a driver to the driver area after signing in', async () => {
    server.use(
      http.post(apiUrl('/api/v1/auth/login'), () => ok(tokenPair)),
      http.get(apiUrl('/api/v1/users/me'), () =>
        ok({
          id: 'd1',
          fullName: 'Tài xế',
          roles: ['DRIVER'],
          status: 'ACTIVE',
          emailVerified: true,
          phoneVerified: false,
        }),
      ),
    );
    renderAt('/login');

    await userEvent.type(screen.getByLabelText('Email hoặc số điện thoại'), 'taixe@rhl.local');
    await userEvent.type(screen.getByLabelText('Mật khẩu'), 'mat-khau-dung');
    await userEvent.click(screen.getByRole('button', { name: 'Đăng nhập' }));

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
