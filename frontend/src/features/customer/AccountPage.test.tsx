import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it } from 'vitest';
import { useSession } from '@/shared/auth/session';
import AccountPage from './AccountPage';

describe('AccountPage', () => {
  it('links an unverified contact to the verify page and back', () => {
    useSession.setState({
      status: 'authenticated',
      user: {
        id: 'u1',
        email: 'khach@rhl.local',
        phone: '+84912345678',
        fullName: 'Khách',
        roles: ['CUSTOMER'],
        status: 'ACTIVE',
        emailVerified: true,
        phoneVerified: false,
      },
    });
    const router = createMemoryRouter([{ path: '/app/account', element: <AccountPage /> }], {
      initialEntries: ['/app/account'],
    });
    render(<RouterProvider router={router} />);

    expect(screen.getByText('Đã xác minh')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Xác minh' })).toHaveAttribute(
      'href',
      '/verify/phone?next=/app/account',
    );
  });
});
