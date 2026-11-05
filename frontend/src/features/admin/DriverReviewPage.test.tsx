import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { beforeEach, describe, expect, it } from 'vitest';
import type { CurrentUser, DriverReview } from '@/shared/api/types';
import { useSession } from '@/shared/auth/session';
import { apiUrl, fail, ok, server } from '@/test/server';
import DriverReviewPage from './DriverReviewPage';

const REVIEWER = {
  id: 'reviewer-1',
  fullName: 'Lê Duyệt',
  roles: ['REVIEWER'],
  status: 'ACTIVE',
  emailVerified: true,
  phoneVerified: false,
} as CurrentUser;

function review(overrides: Partial<DriverReview['profile']> = {}): DriverReview {
  return {
    profile: {
      driverId: 'driver-1',
      fullName: 'Trần Văn B',
      serviceTypes: ['RIDE'],
      reviewStatus: 'PENDING_REVIEW',
      profileVersion: 3,
      submittedAt: '2026-11-05T02:00:00Z',
      availability: 'OFFLINE',
      vehicles: [
        {
          id: 'v1',
          type: 'MOTORBIKE',
          plateNumber: '59X312345',
          brand: 'Honda',
          model: 'Vision',
          color: 'Đỏ',
          manufactureYear: 2022,
          status: 'ACTIVE',
        },
      ],
      documents: [
        {
          id: 'doc-1',
          type: 'NATIONAL_ID',
          documentNumber: '079912345678',
          expiresOn: '2040-01-01',
          hasFile: true,
          status: 'ACTIVE',
        },
      ],
      missingRequirements: [],
      ...overrides,
    },
    decisions: [
      {
        id: 'dec-1',
        profileVersion: 2,
        decision: 'CHANGES_REQUESTED',
        reason: 'Ảnh CCCD bị mờ',
        reviewerId: 'reviewer-9',
        decidedAt: '2026-11-04T02:00:00Z',
      },
    ],
  };
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter([{ path: '/admin/drivers/:driverId', element: <DriverReviewPage /> }], {
    initialEntries: ['/admin/drivers/driver-1'],
  });
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

beforeEach(() => useSession.setState({ status: 'authenticated', user: REVIEWER }));

describe('DriverReviewPage', () => {
  it('shows the full profile, papers and past decisions', async () => {
    server.use(http.get(apiUrl('/api/v1/admin/drivers/driver-1'), () => ok(review())));
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Trần Văn B' })).toBeInTheDocument();
    expect(screen.getByText('Chờ duyệt')).toBeInTheDocument();
    expect(screen.getByText('079912345678')).toBeInTheDocument();
    expect(screen.getByText('59X312345')).toBeInTheDocument();
    expect(screen.getByText('Ảnh CCCD bị mờ')).toBeInTheDocument();
  });

  it('cannot approve while something is missing', async () => {
    server.use(
      http.get(apiUrl('/api/v1/admin/drivers/driver-1'), () =>
        ok(review({ missingRequirements: ['DRIVER_LICENSE is missing'] })),
      ),
    );
    renderPage();

    expect(await screen.findByRole('button', { name: 'Duyệt' })).toBeDisabled();
    expect(screen.getByText('Giấy phép lái xe còn thiếu')).toBeInTheDocument();
  });

  it('rejects with a reason and the profile version that was reviewed', async () => {
    let status: DriverReview['profile']['reviewStatus'] = 'PENDING_REVIEW';
    let sent: unknown;
    server.use(
      http.get(apiUrl('/api/v1/admin/drivers/driver-1'), () => ok(review({ reviewStatus: status }))),
      http.post(apiUrl('/api/v1/admin/drivers/driver-1/decisions'), async ({ request }) => {
        sent = await request.json();
        status = 'REJECTED';
        return ok({
          id: 'dec-2',
          profileVersion: 3,
          decision: 'REJECTED',
          reason: 'Giấy tờ giả',
          reviewerId: 'reviewer-1',
          decidedAt: '2026-11-05T03:00:00Z',
        });
      }),
    );
    renderPage();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Từ chối' }));
    const dialog = screen.getByRole('dialog', { name: 'Từ chối hồ sơ' });
    const confirm = within(dialog).getByRole('button', { name: 'Từ chối' });
    expect(confirm).toBeDisabled();
    await user.type(within(dialog).getByLabelText('Lý do'), 'Giấy tờ giả');
    await user.click(confirm);

    expect(await screen.findByText('Đã ghi nhận: Từ chối.')).toBeInTheDocument();
    expect(sent).toEqual({ verdict: 'REJECT', profileVersion: 3, reason: 'Giấy tờ giả' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(await screen.findByText('Bị từ chối')).toBeInTheDocument();
  });

  it('says the profile changed when the server refuses a stale version', async () => {
    server.use(
      http.get(apiUrl('/api/v1/admin/drivers/driver-1'), () => ok(review())),
      http.post(apiUrl('/api/v1/admin/drivers/driver-1/decisions'), () => fail(409, 'INVALID_STATE')),
    );
    renderPage();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Duyệt' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Duyệt' }));

    expect(await screen.findByText(/Hồ sơ vừa thay đổi/)).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('offers suspension for an approved driver, and no actions on their own profile', async () => {
    server.use(
      http.get(apiUrl('/api/v1/admin/drivers/driver-1'), () => ok(review({ reviewStatus: 'APPROVED' }))),
    );
    renderPage();
    expect(await screen.findByRole('button', { name: 'Tạm khoá' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Duyệt' })).not.toBeInTheDocument();
  });

  it('hides the decisions from a reviewer looking at their own driver profile', async () => {
    useSession.setState({ status: 'authenticated', user: { ...REVIEWER, id: 'driver-1' } });
    server.use(http.get(apiUrl('/api/v1/admin/drivers/driver-1'), () => ok(review())));
    renderPage();
    expect(await screen.findByText(/hồ sơ của chính bạn/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Duyệt' })).not.toBeInTheDocument();
  });
});
