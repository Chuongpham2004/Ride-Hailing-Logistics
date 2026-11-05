import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { CurrentUser, DriverProfile } from '@/shared/api/types';
import { useSession } from '@/shared/auth/session';
import { apiUrl, fail, ok, server } from '@/test/server';
import OnboardingPage from './OnboardingPage';

const USER: CurrentUser = {
  id: 'd1',
  email: 'tai.xe@example.com',
  fullName: 'Trần Văn B',
  roles: ['DRIVER'],
  status: 'ACTIVE',
  emailVerified: false,
  phoneVerified: false,
};

const PROFILE: DriverProfile = {
  driverId: 'd1',
  fullName: 'Trần Văn B',
  serviceTypes: ['RIDE', 'DELIVERY'],
  reviewStatus: 'DRAFT',
  profileVersion: 0,
  availability: 'OFFLINE',
  vehicles: [
    {
      id: 'v1',
      type: 'MOTORBIKE',
      plateNumber: '59X123456',
      brand: 'Honda',
      model: 'Vision',
      color: 'Đỏ',
      manufactureYear: 2022,
      status: 'ACTIVE',
    },
  ],
  documents: [],
  missingRequirements: [
    'DRIVER_LICENSE is missing',
    'NATIONAL_ID is missing',
    'VEHICLE_INSURANCE of 59X123456 is missing',
    'VEHICLE_REGISTRATION of 59X123456 is missing',
    'Verify your email or phone number first',
  ],
};

function renderOnboarding() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(
    [
      { path: '/driver/onboarding', element: <OnboardingPage /> },
      { path: '/driver/review', element: <p>Trang xét duyệt</p> },
      { path: '/login', element: <p>Đăng nhập</p> },
    ],
    { initialEntries: ['/driver/onboarding'] },
  );
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

beforeEach(() => useSession.setState({ status: 'authenticated', user: USER }));

describe('OnboardingPage', () => {
  it('creates the profile when there is none yet', async () => {
    let body: unknown = null;
    let current: DriverProfile | null = null;
    server.use(
      http.get(apiUrl('/api/v1/drivers/me/profile'), () =>
        current ? ok(current) : fail(404, 'RESOURCE_NOT_FOUND'),
      ),
      http.post(apiUrl('/api/v1/drivers/me/profile'), async ({ request }) => {
        body = await request.json();
        current = { ...PROFILE, vehicles: [] };
        return ok(current, 201);
      }),
    );
    renderOnboarding();

    const name = await screen.findByLabelText('Họ và tên (như trên CCCD)');
    expect(name).toHaveValue('Trần Văn B');
    await userEvent.click(screen.getByRole('checkbox', { name: 'Giao hàng' }));
    await userEvent.click(screen.getByRole('button', { name: 'Tạo hồ sơ' }));

    // The next step to do opens by itself.
    expect(await screen.findByLabelText('Biển số')).toBeInTheDocument();
    expect(body).toEqual({ fullName: 'Trần Văn B', serviceTypes: ['RIDE', 'DELIVERY'] });
  });

  it('records a document with an optional file uploaded first', async () => {
    let upload: string | null = null;
    let document: unknown = null;
    server.use(
      http.get(apiUrl('/api/v1/drivers/me/profile'), () => ok(PROFILE)),
      http.post(apiUrl('/api/v1/drivers/me/documents/files'), async ({ request }) => {
        upload = request.headers.get('Authorization') ?? 'sent';
        return ok({ fileId: 'file-1', contentType: 'image/png', sizeBytes: 3, sha256: 'x' }, 201);
      }),
      http.post(apiUrl('/api/v1/drivers/me/documents'), async ({ request }) => {
        document = await request.json();
        return ok({ id: 'doc-1' }, 201);
      }),
    );
    renderOnboarding();

    // Profile and vehicle are done, so the papers step is the one open.
    const row = (await screen.findByText('Bảo hiểm xe', { selector: 'span.font-semibold' })).closest('li')!;
    await userEvent.click(within(row).getByRole('button', { name: /Nhập/ }));
    await userEvent.type(within(row).getByLabelText('Số Bảo hiểm xe'), 'BH-778899');
    fireEvent.change(within(row).getByLabelText('Ngày hết hạn'), { target: { value: '2030-12-31' } });
    await userEvent.upload(
      within(row).getByLabelText(/Ảnh hoặc PDF/),
      new File(['png'], 'bao-hiem.png', { type: 'image/png' }),
    );
    await userEvent.click(within(row).getByRole('button', { name: 'Lưu giấy tờ' }));

    await vi.waitFor(() => expect(document).not.toBeNull());
    expect(upload).not.toBeNull();
    expect(document).toEqual({
      type: 'VEHICLE_INSURANCE',
      vehicleId: 'v1',
      documentNumber: 'BH-778899',
      expiresOn: '2030-12-31',
      fileId: 'file-1',
    });
  });

  it('verifies the email with the code sent', async () => {
    let confirmed: unknown = null;
    const done = {
      ...PROFILE,
      missingRequirements: ['Verify your email or phone number first'],
      documents: [],
    };
    server.use(
      http.get(apiUrl('/api/v1/drivers/me/profile'), () => ok(done)),
      http.post(apiUrl('/api/v1/users/me/contacts/email/verification'), () =>
        ok(
          {
            channel: 'EMAIL',
            destination: 't***@example.com',
            expiresInSeconds: 600,
            resendAfterSeconds: 60,
          },
          202,
        ),
      ),
      http.post(apiUrl('/api/v1/users/me/contacts/email/verification/confirm'), async ({ request }) => {
        confirmed = await request.json();
        return ok({ ...USER, emailVerified: true });
      }),
      http.get(apiUrl('/api/v1/users/me'), () => ok({ ...USER, emailVerified: true })),
    );
    renderOnboarding();
    // The contact is the only step left, so it is the one open.
    await userEvent.click(await screen.findByRole('button', { name: 'Gửi mã xác minh' }));
    await userEvent.type(await screen.findByLabelText(/Mã 6 số đã gửi tới t\*\*\*@example.com/), '123456');
    await userEvent.click(screen.getByRole('button', { name: 'Xác nhận' }));

    // Done: the step is ticked off and the page moves on to the submission.
    expect(
      await screen.findByRole('button', { name: /Xác minh email hoặc số điện thoại.*Xong/ }),
    ).toBeInTheDocument();
    expect(confirmed).toEqual({ code: '123456' });
  });

  it('lists what is still missing and keeps the submit button off', async () => {
    let current: DriverProfile = PROFILE;
    server.use(
      http.get(apiUrl('/api/v1/drivers/me/profile'), () => ok(current)),
      http.post(apiUrl('/api/v1/drivers/me/profile/submit'), () => {
        current = { ...current, reviewStatus: 'PENDING_REVIEW', profileVersion: 1, missingRequirements: [] };
        return ok(current);
      }),
    );
    renderOnboarding();
    await userEvent.click(await screen.findByText(/Bước 5:/));
    const panel = within(document.getElementById('step-5')!);
    expect(panel.getByText('Giấy phép lái xe còn thiếu')).toBeInTheDocument();
    expect(panel.getByRole('button', { name: 'Gửi duyệt' })).toBeDisabled();
  });
  it('sends a submitted profile to the review page', async () => {
    server.use(
      http.get(apiUrl('/api/v1/drivers/me/profile'), () =>
        ok({ ...PROFILE, reviewStatus: 'PENDING_REVIEW', missingRequirements: [] }),
      ),
    );
    renderOnboarding();
    expect(await screen.findByText('Trang xét duyệt')).toBeInTheDocument();
  });
  it('submits a complete profile and opens the review page', async () => {
    let current: DriverProfile = {
      ...PROFILE,
      missingRequirements: [],
      documents: [],
    };
    let submitted = false;
    useSession.setState({ status: 'authenticated', user: { ...USER, emailVerified: true } });
    server.use(
      http.get(apiUrl('/api/v1/drivers/me/profile'), () => ok(current)),
      http.post(apiUrl('/api/v1/drivers/me/profile/submit'), () => {
        submitted = true;
        current = { ...current, reviewStatus: 'PENDING_REVIEW', profileVersion: 1 };
        return ok(current);
      }),
    );
    renderOnboarding();
    // Everything else is done, so the submission step is the one open.
    await screen.findByText(/Hồ sơ đã đủ/);
    const panel = within(document.getElementById('step-5')!);
    await userEvent.click(panel.getByRole('button', { name: 'Gửi duyệt' }));

    expect(await screen.findByText('Trang xét duyệt')).toBeInTheDocument();
    expect(submitted).toBe(true);
  });
});
