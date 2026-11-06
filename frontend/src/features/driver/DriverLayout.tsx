import { Outlet, useMatch } from 'react-router';
import { ConnectionBanner } from '@/shared/realtime/ConnectionBanner';
import { useRealtimeSession } from '@/shared/realtime/realtime';
import { BottomTabs, RoadBar, type AreaTab } from '@/shared/ui/AreaNav';
import { ListIcon, PinIcon, UserIcon } from '@/shared/ui/icons';
import { useLocationReporter } from './location';
import { OfferOverlay } from './OfferOverlay';
import { useOfferFeed } from './offers';
import { useDriverProfile, useDriverStateSync } from './profile';

const TABS: AreaTab[] = [
  { to: '/driver', end: true, label: 'Trang chủ', Icon: PinIcon },
  { to: '/driver/earnings', end: false, label: 'Thu nhập', Icon: ListIcon },
  { to: '/driver/profile', end: false, label: 'Hồ sơ', Icon: UserIcon },
];

/**
 * Frame for the driver area, like the customer one: the whole screen, the realtime connection
 * open for the whole area, plus what has to run whatever screen is open: the location reports
 * while online and the offer overlay. The phone tab bar is for approved drivers, away from a trip;
 * on a wide screen the road bar stays, so the trip screen keeps its way back.
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
    <div className="flex h-dvh flex-col bg-white">
      {/* Unapproved drivers only see onboarding and review: no sections to switch between. */}
      {profile?.reviewStatus === 'APPROVED' && (
        <RoadBar tabs={TABS} label="Điều hướng tài xế" area="Tài xế" />
      )}
      <main className="relative min-h-0 flex-1">
        <ConnectionBanner />
        <Outlet />
        <OfferOverlay />
      </main>
      {showTabs && <BottomTabs tabs={TABS} label="Điều hướng tài xế" />}
    </div>
  );
}
