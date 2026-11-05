import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/shared/api/client';
import type { AdminUser, AdminUserPage, CurrentUser, Role, UserStatus } from '@/shared/api/types';

/* User lookup, roles and account status (Administrator). */

/** Roles an administrator can hand out (user-service Role). */
export const ROLES: Role[] = [
  'CUSTOMER',
  'DRIVER',
  'REVIEWER',
  'SUPPORT_STAFF',
  'FINANCE_STAFF',
  'ADMINISTRATOR',
];

/** The server needs at least this many characters to search (user-service UserController). */
export const MIN_QUERY = 2;
const PAGE_SIZE = 20;

export interface UserFilter {
  /** Name, email or phone fragment, or a user id. */
  q?: string;
  role?: Role;
  status?: UserStatus;
}

export function useUserSearch(filter: UserFilter) {
  return useInfiniteQuery({
    queryKey: ['admin', 'users', filter],
    queryFn: ({ pageParam, signal }) => {
      const params = new URLSearchParams({ limit: String(PAGE_SIZE) });
      if (filter.q) params.set('q', filter.q);
      if (filter.role) params.set('role', filter.role);
      if (filter.status) params.set('status', filter.status);
      if (pageParam) params.set('before', pageParam);
      return api<AdminUserPage>(`/api/v1/admin/users?${params}`, { signal });
    },
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextBefore ?? null,
  });
}

const userKey = (userId: string) => ['admin', 'user', userId] as const;

/** One account; each opening is audited by the server (USER_VIEWED), so it is not refetched behind the admin's back. */
export function useAdminUser(userId: string) {
  return useQuery({
    queryKey: userKey(userId),
    queryFn: ({ signal }) => api<AdminUser>(`/api/v1/admin/users/${encodeURIComponent(userId)}`, { signal }),
    staleTime: Infinity,
  });
}

/** After a change, put the server's answer into the cached account instead of opening it again. */
function useApply(userId: string) {
  const queryClient = useQueryClient();
  return (account: CurrentUser) => {
    queryClient.setQueryData<AdminUser>(userKey(userId), (old) =>
      old ? { ...old, roles: account.roles, status: account.status as UserStatus } : old,
    );
    void queryClient.invalidateQueries({ queryKey: ['admin', 'users'] });
  };
}

export function useReplaceRoles(userId: string) {
  const apply = useApply(userId);
  return useMutation({
    mutationFn: (roles: Role[]) =>
      api<CurrentUser>(`/api/v1/admin/users/${encodeURIComponent(userId)}/roles`, {
        method: 'PUT',
        body: { roles },
      }),
    onSuccess: apply,
  });
}

export function useChangeStatus(userId: string) {
  const apply = useApply(userId);
  return useMutation({
    mutationFn: ({ status, reason }: { status: UserStatus; reason: string }) =>
      api<CurrentUser>(`/api/v1/admin/users/${encodeURIComponent(userId)}/status`, {
        method: 'PUT',
        body: { status, reason: reason || undefined },
      }),
    onSuccess: apply,
  });
}
