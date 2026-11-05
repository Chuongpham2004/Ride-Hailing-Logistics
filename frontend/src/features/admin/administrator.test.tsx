import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import type { ReactElement } from 'react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { beforeEach, describe, expect, it } from 'vitest';
import type { CurrentUser } from '@/shared/api/types';
import { useSession } from '@/shared/auth/session';
import { apiUrl, fail, ok, server } from '@/test/server';
import PricingPage from './PricingPage';
import UserDetailPage from './UserDetailPage';
import UsersPage from './UsersPage';

const ADMIN = {
  id: '01a1aae3-9999-7000-8000-000000000009',
  fullName: 'Administrator',
  roles: ['ADMINISTRATOR'],
  status: 'ACTIVE',
  emailVerified: true,
  phoneVerified: false,
} as CurrentUser;

const USER_ID = '01a1aae3-4444-7000-8000-000000000004';

function renderAt(path: string, route: string, element: ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter([{ path: route, element }], { initialEntries: [path] });
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

beforeEach(() => useSession.setState({ status: 'authenticated', user: ADMIN }));

describe('PricingPage', () => {
  it('schedules the next version from the latest tariff', async () => {
    let sent: Record<string, unknown> | undefined;
    server.use(
      http.get(apiUrl('/api/v1/admin/pricing/rules'), () =>
        ok([
          {
            id: 'r1',
            serviceType: 'RIDE',
            regionCode: 'HCM',
            version: 1,
            baseFare: 10000,
            perKm: 5000,
            perMinute: 300,
            minimumFare: 15000,
            currency: 'VND',
            effectiveFrom: '2026-01-01T00:00:00Z',
            createdAt: '2026-01-01T00:00:00Z',
          },
        ]),
      ),
      http.post(apiUrl('/api/v1/admin/pricing/rules'), async ({ request }) => {
        sent = (await request.json()) as Record<string, unknown>;
        return ok({ ...sent, id: 'r2', version: 2, currency: 'VND', createdAt: '2026-11-05T00:00:00Z' }, 201);
      }),
    );
    renderAt('/admin/pricing', '/admin/pricing', <PricingPage />);
    const user = userEvent.setup();

    expect(await screen.findByText('Đang áp dụng')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Thêm phiên bản' }));
    const dialog = screen.getByRole('dialog', { name: /Phiên bản 2/ });
    const perKm = within(dialog).getByLabelText('Mỗi km (₫)');
    expect(perKm).toHaveValue('5000');
    await user.clear(perKm);
    await user.type(perKm, '5.500');
    await user.click(within(dialog).getByRole('button', { name: 'Lên lịch' }));

    expect(await screen.findByText(/Đã lên lịch phiên bản 2/)).toBeInTheDocument();
    expect(sent).toMatchObject({
      serviceType: 'RIDE',
      regionCode: 'HCM',
      baseFare: 10000,
      perKm: 5500,
      perMinute: 300,
      minimumFare: 15000,
    });
    expect(Date.parse(sent!.effectiveFrom as string)).toBeGreaterThan(Date.now());
  });
});

const ACCOUNT = {
  id: USER_ID,
  email: 'nv@rhl.local',
  fullName: 'Nhân Viên Mới',
  roles: ['CUSTOMER'],
  status: 'ACTIVE',
  emailVerified: true,
  phoneVerified: false,
  createdAt: '2026-11-01T02:00:00Z',
  updatedAt: '2026-11-01T02:00:00Z',
};

describe('UsersPage', () => {
  it('searches by text, role and status and links to each account', async () => {
    const asked: string[] = [];
    server.use(
      http.get(apiUrl('/api/v1/admin/users'), ({ request }) => {
        asked.push(new URL(request.url).search);
        return ok({ items: [ACCOUNT] });
      }),
    );
    renderAt('/admin/users', '/admin/users', <UsersPage />);
    const user = userEvent.setup();

    expect(await screen.findByRole('link', { name: 'Nhân Viên Mới' })).toHaveAttribute(
      'href',
      `/admin/users/${USER_ID}`,
    );
    await user.type(screen.getByLabelText(/Tên, email/), 'n');
    await user.click(screen.getByRole('button', { name: 'Tìm' }));
    expect(screen.getByText('Nhập ít nhất 2 ký tự')).toBeInTheDocument();

    await user.type(screen.getByLabelText(/Tên, email/), 'v');
    await user.selectOptions(screen.getByLabelText('Vai trò'), 'SUPPORT_STAFF');
    await user.selectOptions(screen.getByLabelText('Trạng thái'), 'LOCKED');
    await user.click(screen.getByRole('button', { name: 'Tìm' }));
    await screen.findByRole('link', { name: 'Nhân Viên Mới' });
    const last = new URLSearchParams(asked[asked.length - 1]);
    expect([last.get('q'), last.get('role'), last.get('status')]).toEqual(['nv', 'SUPPORT_STAFF', 'LOCKED']);
  });
});

describe('UserDetailPage', () => {
  it('starts from the current roles and saves the changed set', async () => {
    let sent: unknown;
    server.use(
      http.get(apiUrl(`/api/v1/admin/users/${USER_ID}`), () => ok(ACCOUNT)),
      http.put(apiUrl(`/api/v1/admin/users/${USER_ID}/roles`), async ({ request }) => {
        sent = await request.json();
        return ok({ ...ACCOUNT, roles: ['CUSTOMER', 'SUPPORT_STAFF'] });
      }),
    );
    renderAt(`/admin/users/${USER_ID}`, '/admin/users/:userId', <UserDetailPage />);
    const user = userEvent.setup();

    expect(await screen.findByLabelText('Khách hàng')).toBeChecked();
    expect(screen.getByRole('button', { name: 'Lưu vai trò' })).toBeDisabled();
    await user.click(screen.getByLabelText('Hỗ trợ'));
    await user.click(screen.getByRole('button', { name: 'Lưu vai trò' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Thay vai trò' }));

    expect(sent).toEqual({ roles: ['CUSTOMER', 'SUPPORT_STAFF'] });
    expect(await screen.findByText('Khách hàng, Hỗ trợ')).toBeInTheDocument();
  });

  it('locks an account only with a reason, and shows a refusal in the dialog', async () => {
    server.use(
      http.get(apiUrl(`/api/v1/admin/users/${USER_ID}`), () => ok(ACCOUNT)),
      http.put(apiUrl(`/api/v1/admin/users/${USER_ID}/status`), () => fail(422, 'BUSINESS_RULE_VIOLATION')),
    );
    renderAt(`/admin/users/${USER_ID}`, '/admin/users/:userId', <UserDetailPage />);
    const user = userEvent.setup();

    expect(await screen.findByLabelText('Chuyển sang')).toHaveValue('LOCKED');
    await user.click(screen.getByRole('button', { name: 'Đổi trạng thái' }));
    const dialog = screen.getByRole('dialog');
    const confirm = within(dialog).getByRole('button', { name: 'Đổi trạng thái' });
    expect(confirm).toBeDisabled();
    await user.type(within(dialog).getByLabelText('Lý do'), 'Nghi gian lận');
    await user.click(confirm);
    expect(await within(dialog).findByRole('alert')).toBeInTheDocument();
  });

  it('hides the status form on the administrator’s own account', async () => {
    server.use(http.get(apiUrl(`/api/v1/admin/users/${ADMIN.id}`), () => ok({ ...ACCOUNT, id: ADMIN.id })));
    renderAt(`/admin/users/${ADMIN.id}`, '/admin/users/:userId', <UserDetailPage />);
    expect(await screen.findByText(/Đây là tài khoản của bạn/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Đổi trạng thái' })).not.toBeInTheDocument();
  });

  it('refuses an id that is not a UUID', async () => {
    renderAt('/admin/users/abc', '/admin/users/:userId', <UserDetailPage />);
    expect(await screen.findByText('Mã người dùng không hợp lệ.')).toBeInTheDocument();
  });
});
