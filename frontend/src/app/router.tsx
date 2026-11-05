import type { ComponentType } from 'react';
import { createBrowserRouter } from 'react-router';
import { CustomerLayout } from './CustomerLayout';
import { NotFound } from './NotFound';
import { HomeRedirect, RequireArea } from './RequireArea';

/*
 * One app, three areas by role, each lazy-loaded into its own bundle (frontend-design.md §3).
 * Features export their pages as default.
 */
const page = (load: () => Promise<{ default: ComponentType }>) => async () => ({
  Component: (await load()).default,
});

export const router = createBrowserRouter([
  { path: '/', element: <HomeRedirect /> },
  { path: '/login', lazy: page(() => import('@/features/auth/LoginPage')) },
  { path: '/register', lazy: page(() => import('@/features/auth/RegisterPage')) },
  {
    path: '/app',
    element: <RequireArea area="customer" />,
    children: [
      {
        element: <CustomerLayout />,
        children: [
          { index: true, lazy: page(() => import('@/features/customer/BookingPage')) },
          { path: 'quote/:quoteId', lazy: page(() => import('@/features/customer/QuotePage')) },
          { path: 'trips/:tripId', lazy: page(() => import('@/features/customer/TripPage')) },
          { path: 'trips/:tripId/receipt', lazy: page(() => import('@/features/customer/ReceiptPage')) },
          { path: 'activity', lazy: page(() => import('@/features/customer/ActivityPage')) },
          { path: 'activity/:tripId', lazy: page(() => import('@/features/customer/ActivityDetailPage')) },
          { path: 'account', lazy: page(() => import('@/features/customer/AccountPage')) },
        ],
      },
    ],
  },
  {
    path: '/driver',
    element: <RequireArea area="driver" />,
    children: [
      {
        lazy: page(() => import('@/features/driver/DriverLayout')),
        children: [
          { path: 'onboarding', lazy: page(() => import('@/features/driver/OnboardingPage')) },
          { path: 'review', lazy: page(() => import('@/features/driver/ReviewPage')) },
          {
            lazy: page(() => import('@/features/driver/RequireApproved')),
            children: [
              { index: true, lazy: page(() => import('@/features/driver/DriverHomePage')) },
              { path: 'trips/:tripId', lazy: page(() => import('@/features/driver/DriverTripPage')) },
              { path: 'earnings', lazy: page(() => import('@/features/driver/EarningsPage')) },
              { path: 'activity', lazy: page(() => import('@/features/driver/DriverActivityPage')) },
              { path: 'profile', lazy: page(() => import('@/features/driver/DriverProfilePage')) },
            ],
          },
        ],
      },
    ],
  },
  {
    path: '/admin',
    element: <RequireArea area="admin" />,
    children: [
      {
        lazy: page(() => import('@/features/admin/AdminLayout')),
        children: [
          { index: true, lazy: page(() => import('@/features/admin/AdminHomePage')) },
          // handle.section: AdminLayout shows the page only to roles that may use the section.
          {
            handle: { section: 'drivers' },
            children: [
              { path: 'drivers', lazy: page(() => import('@/features/admin/DriverQueuePage')) },
              { path: 'drivers/:driverId', lazy: page(() => import('@/features/admin/DriverReviewPage')) },
            ],
          },
          {
            handle: { section: 'trips' },
            children: [
              { path: 'trips', lazy: page(() => import('@/features/admin/TripSearchPage')) },
              { path: 'trips/:tripId', lazy: page(() => import('@/features/admin/TripDetailPage')) },
            ],
          },
        ],
      },
    ],
  },
  { path: '*', element: <NotFound /> },
]);
