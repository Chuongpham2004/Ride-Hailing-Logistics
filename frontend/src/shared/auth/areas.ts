import type { Role } from '@/shared/api/types';

export type Area = 'customer' | 'driver' | 'admin';

const AREA_ROLES: Record<Area, Role[]> = {
  customer: ['CUSTOMER'],
  driver: ['DRIVER'],
  admin: ['REVIEWER', 'SUPPORT_STAFF', 'FINANCE_STAFF', 'ADMINISTRATOR'],
};

const AREA_HOME: Record<Area, string> = {
  customer: '/app',
  driver: '/driver',
  admin: '/admin',
};

export function canEnter(area: Area, roles: readonly Role[]): boolean {
  return AREA_ROLES[area].some((role) => roles.includes(role));
}

/**
 * Where a signed-in user lands. Navigation only: the server decides what each role may do
 * (403 / 404), whatever the UI shows.
 */
export function homeFor(roles: readonly Role[]): string {
  const area = (['customer', 'driver', 'admin'] as const).find((a) => canEnter(a, roles));
  return area ? AREA_HOME[area] : '/login';
}
