import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { http } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it } from 'vitest';
import type { DriverProfile } from '@/shared/api/types';
import { apiUrl, fail, ok, server } from '@/test/server';
import RequireApproved from './RequireApproved';

const PROFILE = {
  driverId: 'd1',
  fullName: 'Trần Văn B',
  serviceTypes: ['RIDE'],
  profileVersion: 1,
  availability: 'OFFLINE',
  vehicles: [],
  documents: [],
  missingRequirements: [],
} as unknown as DriverProfile;

function renderAt(answer: () => Response) {
  server.use(http.get(apiUrl('/api/v1/drivers/me/profile'), answer));
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(
    [
      {
        path: '/driver',
        element: <RequireApproved />,
        children: [{ index: true, element: <p>Trang chủ tài xế</p> }],
      },
      { path: '/driver/onboarding', element: <p>Onboarding</p> },
      { path: '/driver/review', element: <p>Xét duyệt</p> },
    ],
    { initialEntries: ['/driver'] },
  );
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

describe('RequireApproved', () => {
  it('sends a driver without a profile to onboarding', async () => {
    renderAt(() => fail(404, 'RESOURCE_NOT_FOUND'));
    expect(await screen.findByText('Onboarding')).toBeInTheDocument();
  });

  it('sends a profile sent back for changes to onboarding', async () => {
    renderAt(() => ok({ ...PROFILE, reviewStatus: 'DRAFT' }));
    expect(await screen.findByText('Onboarding')).toBeInTheDocument();
  });

  it.each(['PENDING_REVIEW', 'REJECTED', 'SUSPENDED'])(
    'sends a %s profile to the review page',
    async (status) => {
      renderAt(() => ok({ ...PROFILE, reviewStatus: status }));
      expect(await screen.findByText('Xét duyệt')).toBeInTheDocument();
    },
  );

  it('lets an approved driver in', async () => {
    renderAt(() => ok({ ...PROFILE, reviewStatus: 'APPROVED' }));
    expect(await screen.findByText('Trang chủ tài xế')).toBeInTheDocument();
  });
});
