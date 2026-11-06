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

/** Each area has its own sign-in page (customer, driver, staff portals). */
export const LOGIN_PATH: Record<Area, string> = {
  customer: '/login',
  driver: '/driver/login',
  admin: '/admin/login',
};

/** The area a path belongs to, by its first segment; anything else is the customer's. */
export function areaOfPath(path: string | null | undefined): Area {
  if (path?.startsWith('/driver')) return 'driver';
  if (path?.startsWith('/admin')) return 'admin';
  return 'customer';
}

export function canEnter(area: Area, roles: readonly Role[]): boolean {
  return AREA_ROLES[area].some((role) => roles.includes(role));
}

/** The areas a signed-in user may use, in a fixed order. */
export function areasFor(roles: readonly Role[]): Area[] {
  return (['customer', 'driver', 'admin'] as const).filter((a) => canEnter(a, roles));
}

export function areaHome(area: Area): string {
  return AREA_HOME[area];
}

/**
 * Where a signed-in user lands. Navigation only: the server decides what each role may do
 * (403 / 404), whatever the UI shows.
 */
export function homeFor(roles: readonly Role[]): string {
  const area = (['customer', 'driver', 'admin'] as const).find((a) => canEnter(a, roles));
  return area ? AREA_HOME[area] : LOGIN_PATH.customer;
}
