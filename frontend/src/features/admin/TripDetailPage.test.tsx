import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { beforeEach, describe, expect, it } from 'vitest';
import type { CurrentUser, StaffTripDetail, TripStatus } from '@/shared/api/types';
import { useSession } from '@/shared/auth/session';
import { apiUrl, ok, server } from '@/test/server';
import TripDetailPage from './TripDetailPage';

const SUPPORT = {
  id: 'staff-1',
  fullName: 'Ngô Hỗ Trợ',
  roles: ['SUPPORT_STAFF'],
  status: 'ACTIVE',
  emailVerified: true,
  phoneVerified: false,
} as CurrentUser;

const TRIP_ID = '0b9c1e5a-3f2a-4c1e-9d7b-2a1f3e4d5c6b';

function detail(status: TripStatus, failures = 0): StaffTripDetail {
  return {
    trip: {
      id: TRIP_ID,
      customerId: 'customer-1',
      driverId: 'driver-1',
      serviceType: 'DELIVERY',
      status,
      pickup: { latitude: 10.77, longitude: 106.7, address: '1 Lê Lợi, Quận 1' },
      dropoff: { latitude: 10.78, longitude: 106.69, address: '200 Pasteur, Quận 3' },
      createdAt: '2026-11-05T02:00:00Z',
      version: 4,
      delivery: {
        recipientName: 'Phạm C',
        recipientPhone: '0901234567',
        packageDescription: 'Tài liệu',
        packageSize: 'SMALL',
        packageWeightGrams: 500,
      },
      ...(status === 'CANCELLED'
        ? { cancelledAt: '2026-11-05T02:30:00Z', cancelledBy: 'STAFF' as const }
        : {}),
    },
    history: [
      { toStatus: 'CREATED', actorType: 'CUSTOMER', occurredAt: '2026-11-05T02:00:00Z' },
      {
        fromStatus: 'CREATED',
        toStatus: 'MATCHING',
        actorType: 'SYSTEM',
        occurredAt: '2026-11-05T02:00:01Z',
      },
    ],
    offers: [
      {
        id: 'offer-1',
        driverId: 'driver-1',
        status: 'ACCEPTED',
        pickupDistanceMeters: 850,
        createdAt: '2026-11-05T02:00:02Z',
        expiresAt: '2026-11-05T02:00:17Z',
        respondedAt: '2026-11-05T02:00:06Z',
      },
    ],
    pickupCodeFailures: failures,
    deliveryCodeFailures: 0,
  };
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter([{ path: '/admin/trips/:tripId', element: <TripDetailPage /> }], {
    initialEntries: [`/admin/trips/${TRIP_ID}`],
  });
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

beforeEach(() => useSession.setState({ status: 'authenticated', user: SUPPORT }));

describe('TripDetailPage', () => {
  it('shows the route, recipient, offers and a locked code', async () => {
    server.use(http.get(apiUrl(`/api/v1/admin/trips/${TRIP_ID}`), () => ok(detail('ARRIVED', 5))));
    renderPage();

    expect(await screen.findByText('1 Lê Lợi, Quận 1')).toBeInTheDocument();
    expect(screen.getByText(/Phạm C/)).toBeInTheDocument();
    expect(screen.getByText('850 m')).toBeInTheDocument();
    expect(screen.getByText(/mã đã bị khoá/)).toBeInTheDocument();
    // Support without the reviewer role sees driver ids, not links to the review pages.
    expect(screen.queryByRole('link', { name: 'driver-1' })).not.toBeInTheDocument();
  });

  it('cancels by exception only with a note of at least 10 characters', async () => {
    let status: TripStatus = 'ARRIVED';
    let sent: unknown;
    server.use(
      http.get(apiUrl(`/api/v1/admin/trips/${TRIP_ID}`), () => ok(detail(status))),
      http.post(apiUrl(`/api/v1/trips/${TRIP_ID}/cancel`), async ({ request }) => {
        sent = await request.json();
        status = 'CANCELLED';
        return ok(detail('CANCELLED').trip);
      }),
    );
    renderPage();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Huỷ chuyến (ngoại lệ)' }));
    const dialog = screen.getByRole('dialog');
    await user.selectOptions(within(dialog).getByLabelText('Nguyên nhân'), 'SAFETY_CONCERN');
    await user.type(within(dialog).getByLabelText('Lý do'), 'quá ngắn');
    const confirm = within(dialog).getByRole('button', { name: 'Huỷ chuyến' });
    expect(confirm).toBeDisabled();
    await user.type(within(dialog).getByLabelText('Lý do'), ' rồi, khách báo nguy hiểm');
    await user.click(confirm);

    expect(await screen.findByText(/Đã huỷ chuyến/)).toBeInTheDocument();
    expect(sent).toEqual({ reason: 'SAFETY_CONCERN', note: 'quá ngắn rồi, khách báo nguy hiểm' });
    expect(await screen.findByText('Đã huỷ')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Huỷ chuyến (ngoại lệ)' })).not.toBeInTheDocument();
  });

  it('says where a completed trip ended, and flags one ended far from the drop-off', async () => {
    const completed = {
      ...detail('COMPLETED' as TripStatus),
      trip: { ...detail('COMPLETED' as TripStatus).trip, completedAt: '2026-11-05T02:30:00Z' },
    };
    server.use(
      http.get(apiUrl(`/api/v1/admin/trips/${TRIP_ID}`), () =>
        ok({
          ...completed,
          completion: {
            latitude: 10.77,
            longitude: 106.69,
            distanceToDropoffMeters: 1250,
            awayFromDropoff: true,
          },
        }),
      ),
    );
    renderPage();
    expect(
      await screen.findByText(/Cách điểm đến 1,3 km: dừng sớm hoặc hoàn tất trước khi tới/),
    ).toBeInTheDocument();
  });

  it('shows a completion at the drop-off plainly, and an unknown one as unknown', async () => {
    const completed = {
      ...detail('COMPLETED' as TripStatus),
      trip: { ...detail('COMPLETED' as TripStatus).trip, completedAt: '2026-11-05T02:30:00Z' },
    };
    server.use(
      http.get(apiUrl(`/api/v1/admin/trips/${TRIP_ID}`), () =>
        ok({
          ...completed,
          completion: {
            latitude: 10.78,
            longitude: 106.69,
            distanceToDropoffMeters: 40,
            awayFromDropoff: false,
          },
        }),
      ),
    );
    renderPage();
    expect(await screen.findByText('Tại điểm đến (cách 40 m)')).toBeInTheDocument();
  });

  it('says so when no position was known at completion', async () => {
    const completed = {
      ...detail('COMPLETED' as TripStatus),
      trip: { ...detail('COMPLETED' as TripStatus).trip, completedAt: '2026-11-05T02:30:00Z' },
    };
    server.use(http.get(apiUrl(`/api/v1/admin/trips/${TRIP_ID}`), () => ok(completed)));
    renderPage();
    expect(await screen.findByText(/Không rõ: lúc đó chưa có vị trí của tài xế/)).toBeInTheDocument();
  });
});
