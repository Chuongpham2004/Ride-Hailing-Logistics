import { useMutation } from '@tanstack/react-query';
import { api } from '@/shared/api/client';
import type { CurrentUser, Role, UserStatus } from '@/shared/api/types';

/* Roles and account status (Administrator); each change answers with the account as it is now. */

/** Roles an administrator can hand out (user-service Role). */
export const ROLES: Role[] = [
  'CUSTOMER',
  'DRIVER',
  'REVIEWER',
  'SUPPORT_STAFF',
  'FINANCE_STAFF',
  'ADMINISTRATOR',
];

export function useReplaceRoles(userId: string) {
  return useMutation({
    mutationFn: (roles: Role[]) =>
      api<CurrentUser>(`/api/v1/admin/users/${encodeURIComponent(userId)}/roles`, {
        method: 'PUT',
        body: { roles },
      }),
  });
}

export function useChangeStatus(userId: string) {
  return useMutation({
    mutationFn: ({ status, reason }: { status: UserStatus; reason: string }) =>
      api<CurrentUser>(`/api/v1/admin/users/${encodeURIComponent(userId)}/status`, {
        method: 'PUT',
        body: { status, reason: reason || undefined },
      }),
  });
}
