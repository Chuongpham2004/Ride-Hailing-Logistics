import { NavLink, Outlet, useMatch } from 'react-router';
import { ListIcon, PinIcon, UserIcon } from '@/shared/ui/icons';

const TABS = [
  { to: '/app', end: true, label: 'Đặt xe', Icon: PinIcon },
  { to: '/app/activity', end: false, label: 'Hoạt động', Icon: ListIcon },
  { to: '/app/account', end: false, label: 'Tài khoản', Icon: UserIcon },
];

/**
 * Mobile-first frame for the customer area: at most 480 px wide, centred on larger screens, with
 * the bottom tab bar. The quote screen hides the tabs so its one action stays under the thumb.
 */
export function CustomerLayout() {
  const onQuote = useMatch('/app/quote/:quoteId');
  return (
    <div className="mx-auto flex h-dvh max-w-[480px] flex-col bg-white shadow-[0_0_0_1px_var(--color-line)]">
      <main className="relative min-h-0 flex-1">
        <Outlet />
      </main>
      {!onQuote && (
        <nav
          aria-label="Điều hướng chính"
          className="grid grid-cols-3 border-t border-concrete bg-white px-2 pt-1.5 pb-[max(14px,env(safe-area-inset-bottom))]"
        >
          {TABS.map(({ to, end, label, Icon }) => (
            <NavLink
              key={to}
              to={to}
              end={end}
              className={({ isActive }) =>
                `flex flex-col items-center gap-0.5 py-1.5 text-xs no-underline ${isActive ? 'font-semibold text-asphalt' : 'text-curb'}`
              }
            >
              <Icon />
              {label}
            </NavLink>
          ))}
        </nav>
      )}
    </div>
  );
}
