import type { Role } from '@/shared/api/types';

/*
 * The staff area's sections and who may open them (frontend-design.md §4.4). Navigation only:
 * the server answers 403 / 404 whatever the sidebar shows.
 */

export type SectionKey = 'drivers' | 'trips';

export interface Section {
  key: SectionKey;
  to: string;
  label: string;
  roles: Role[];
}

export const SECTIONS: Section[] = [
  { key: 'drivers', to: '/admin/drivers', label: 'Hồ sơ tài xế', roles: ['REVIEWER', 'ADMINISTRATOR'] },
  { key: 'trips', to: '/admin/trips', label: 'Chuyến', roles: ['SUPPORT_STAFF', 'ADMINISTRATOR'] },
];

export const ROLE_NAMES: Partial<Record<Role, string>> = {
  REVIEWER: 'Duyệt hồ sơ',
  SUPPORT_STAFF: 'Hỗ trợ',
  FINANCE_STAFF: 'Tài chính',
  ADMINISTRATOR: 'Quản trị',
};

export function sectionsFor(roles: readonly Role[]): Section[] {
  return SECTIONS.filter((section) => section.roles.some((role) => roles.includes(role)));
}

export function canUse(key: SectionKey, roles: readonly Role[]): boolean {
  return sectionsFor(roles).some((section) => section.key === key);
}
