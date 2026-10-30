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
    children: [{ index: true, lazy: page(() => import('@/features/driver/DriverHomePage')) }],
  },
  {
    path: '/admin',
    element: <RequireArea area="admin" />,
    children: [{ index: true, lazy: page(() => import('@/features/admin/AdminHomePage')) }],
  },
  { path: '*', element: <NotFound /> },
]);
