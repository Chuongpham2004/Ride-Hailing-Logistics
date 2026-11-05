import { NavLink, Outlet, useMatch } from 'react-router';
import { ConnectionBanner } from '@/shared/realtime/ConnectionBanner';
import { useRealtimeSession } from '@/shared/realtime/realtime';
import { ListIcon, PinIcon, UserIcon } from '@/shared/ui/icons';
import { RevealBackdrop } from '@/shared/ui/RevealBackdrop';
import { useLocationReporter } from './location';
import { OfferOverlay } from './OfferOverlay';
import { useOfferFeed } from './offers';
import { useDriverProfile, useDriverStateSync } from './profile';

const TABS = [
  { to: '/driver', end: true, label: 'Trang chủ', Icon: PinIcon },
  { to: '/driver/earnings', end: false, label: 'Thu nhập', Icon: ListIcon },
  { to: '/driver/profile', end: false, label: 'Hồ sơ', Icon: UserIcon },
];

/**
 * Frame for the driver area, like the customer one: at most 480 px wide, the realtime connection
 * open for the whole area, plus what has to run whatever screen is open: the location reports
 * while online and the offer overlay. The tab bar is for approved drivers, away from a trip.
 */
export default function DriverLayout() {
  useRealtimeSession();
  useDriverStateSync();
  const { data: profile } = useDriverProfile();
  const online = profile !== undefined && profile.availability !== 'OFFLINE';
  useLocationReporter(online);
  useOfferFeed(online);
  const onTrip = useMatch('/driver/trips/:tripId');
  const showTabs = profile?.reviewStatus === 'APPROVED' && !onTrip;

  return (
    <>
      <RevealBackdrop />
      <div className="relative mx-auto flex h-dvh max-w-[480px] flex-col bg-white shadow-[0_0_0_1px_var(--color-line)]">
        <main className="relative min-h-0 flex-1">
          <ConnectionBanner />
          <Outlet />
          <OfferOverlay />
        </main>
        {showTabs && (
          <nav
            aria-label="Điều hướng tài xế"
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
    </>
  );
}
