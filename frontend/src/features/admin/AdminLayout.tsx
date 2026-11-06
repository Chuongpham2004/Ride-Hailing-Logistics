import { useState } from 'react';
import { NavLink, Outlet, useMatches, useNavigate } from 'react-router';
import { LOGIN_PATH } from '@/shared/auth/areas';
import { logout, useSession } from '@/shared/auth/session';
import { ConnectionBanner } from '@/shared/realtime/ConnectionBanner';
import { canUse, ROLE_NAMES, sectionsFor, type SectionKey } from './sections';

/**
 * Desktop frame for staff: a sidebar with the sections the user's roles allow, content on the
 * right. A section the roles cannot use shows a notice instead of a page whose requests all fail.
 */
export default function AdminLayout() {
  const user = useSession((state) => state.user);
  const navigate = useNavigate();
  const [leaving, setLeaving] = useState(false);
  const roles = user?.roles ?? [];
  const sections = sectionsFor(roles);
  // Routes name their section in handle.section (app/router.tsx).
  const section = useMatches()
    .map((match) => (match.handle as { section?: SectionKey } | undefined)?.section)
    .find(Boolean);
  const allowed = !section || canUse(section, roles);

  const signOut = async () => {
    setLeaving(true);
    await logout();
    navigate(LOGIN_PATH.admin, { replace: true });
  };

  return (
    <div className="grid h-dvh grid-cols-[232px_1fr] bg-concrete-2">
      <aside className="relative flex flex-col gap-6 bg-asphalt px-4 py-6 text-white">
        {/* The same lane marking as the customer and driver road bar, along the sidebar edge. */}
        <span
          aria-hidden="true"
          className="absolute inset-y-0 right-0 w-[3px] bg-[repeating-linear-gradient(180deg,var(--color-sign)_0_32px,transparent_32px_56px)]"
        />
        <div className="flex items-baseline gap-2 px-2">
          <span className="rounded bg-sign px-1.5 font-plate text-xl leading-tight text-asphalt">RHL</span>
          <span className="text-sm text-white/70">Nhân viên</span>
        </div>
        <nav aria-label="Khu vực nhân viên" className="flex flex-col gap-1">
          {sections.map((section) => (
            <NavLink
              key={section.key}
              to={section.to}
              className={({ isActive }) =>
                `rounded-lg px-3 py-2.5 text-[15px] no-underline ${
                  isActive
                    ? 'bg-white/12 font-semibold text-white'
                    : 'text-white/75 hover:bg-white/6 hover:text-white'
                }`
              }
            >
              {section.label}
            </NavLink>
          ))}
        </nav>
        <div className="mt-auto flex flex-col gap-1 px-2 text-sm">
          <span className="font-semibold">{user?.fullName}</span>
          <span className="text-white/60">
            {roles
              .map((role) => ROLE_NAMES[role])
              .filter(Boolean)
              .join(', ')}
          </span>
          <button
            type="button"
            onClick={() => void signOut()}
            disabled={leaving}
            className="mt-2 self-start rounded-lg border border-white/30 px-3 py-1.5 text-white hover:bg-white/10 disabled:opacity-50"
          >
            Đăng xuất
          </button>
        </div>
      </aside>
      <main className="relative min-w-0 overflow-y-auto">
        <ConnectionBanner live={false} />
        {allowed ? (
          <Outlet />
        ) : (
          <div className="px-10 py-10">
            <h1 className="m-0 text-2xl font-bold">Không có quyền</h1>
            <p className="text-ink-2">Vai trò của bạn không dùng được mục này.</p>
          </div>
        )}
      </main>
    </div>
  );
}
