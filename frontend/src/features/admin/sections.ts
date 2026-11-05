import type { Role } from '@/shared/api/types';

/*
 * The staff area's sections and who may open them (frontend-design.md §4.4). Navigation only:
 * the server answers 403 / 404 whatever the sidebar shows.
 */

export type SectionKey = 'drivers' | 'trips' | 'payments' | 'wallets' | 'pricing' | 'users';

export interface Section {
  key: SectionKey;
  to: string;
  label: string;
  roles: Role[];
}

export const SECTIONS: Section[] = [
  { key: 'drivers', to: '/admin/drivers', label: 'Hồ sơ tài xế', roles: ['REVIEWER', 'ADMINISTRATOR'] },
  { key: 'trips', to: '/admin/trips', label: 'Chuyến', roles: ['SUPPORT_STAFF', 'ADMINISTRATOR'] },
  { key: 'payments', to: '/admin/payments', label: 'Thanh toán', roles: ['FINANCE_STAFF', 'ADMINISTRATOR'] },
  { key: 'wallets', to: '/admin/wallets', label: 'Ví tài xế', roles: ['FINANCE_STAFF', 'ADMINISTRATOR'] },
  { key: 'pricing', to: '/admin/pricing', label: 'Bảng giá', roles: ['ADMINISTRATOR'] },
  { key: 'users', to: '/admin/users', label: 'Người dùng', roles: ['ADMINISTRATOR'] },
];

export const ROLE_NAMES: Record<Role, string> = {
  CUSTOMER: 'Khách hàng',
  DRIVER: 'Tài xế',
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
