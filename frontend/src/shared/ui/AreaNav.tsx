import type { ComponentType, SVGProps } from 'react';
import { NavLink } from 'react-router';
import { useSession } from '@/shared/auth/session';

export interface AreaTab {
  to: string;
  end: boolean;
  label: string;
  Icon: ComponentType<SVGProps<SVGSVGElement>>;
}

/**
 * Top bar on wide screens: the road the app is named after, asphalt with a dashed lane line in
 * sign yellow, carrying the brand plate, the area's sections and who is signed in.
 */
export function RoadBar({ tabs, label, area }: { tabs: AreaTab[]; label: string; area: string }) {
  const user = useSession((state) => state.user);
  return (
    <header className="relative hidden h-16 shrink-0 items-center gap-10 bg-asphalt px-7 text-white lg:flex">
      <span className="flex items-baseline gap-2.5">
        <span className="rounded bg-sign px-1.5 font-plate text-2xl leading-tight text-asphalt">RHL</span>
        <span className="text-sm text-white/65">{area}</span>
      </span>
      <nav aria-label={label} className="flex h-full items-stretch gap-1">
        {tabs.map(({ to, end, label: text }) => (
          <NavLink
            key={to}
            to={to}
            end={end}
            className={({ isActive }) =>
              `flex items-center px-4 text-[15px] no-underline ${
                isActive ? 'font-semibold text-sign' : 'text-white/75 hover:text-white'
              }`
            }
          >
            {text}
          </NavLink>
        ))}
      </nav>
      <span className="ml-auto text-[15px] text-white/80">{user?.fullName}</span>
      <span
        aria-hidden="true"
        className="absolute inset-x-0 bottom-0 h-[3px] bg-[repeating-linear-gradient(90deg,var(--color-sign)_0_32px,transparent_32px_56px)]"
      />
    </header>
  );
}

/** Thumb-reach tabs along the bottom of a phone screen. */
export function BottomTabs({ tabs, label }: { tabs: AreaTab[]; label: string }) {
  return (
    <nav
      aria-label={label}
      className="grid grid-cols-3 border-t border-concrete bg-white px-2 pt-1.5 pb-[max(14px,env(safe-area-inset-bottom))] lg:hidden"
    >
      {tabs.map(({ to, end, label: text, Icon }) => (
        <NavLink
          key={to}
          to={to}
          end={end}
          className={({ isActive }) =>
            `flex flex-col items-center gap-0.5 py-1.5 text-xs no-underline ${isActive ? 'font-semibold text-asphalt' : 'text-curb'}`
          }
        >
          <Icon />
          {text}
        </NavLink>
      ))}
    </nav>
  );
}
