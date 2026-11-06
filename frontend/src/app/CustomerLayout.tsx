import { Outlet, useMatch } from 'react-router';
import { ConnectionBanner } from '@/shared/realtime/ConnectionBanner';
import { useRealtimeSession } from '@/shared/realtime/realtime';
import { BottomTabs, RoadBar, type AreaTab } from '@/shared/ui/AreaNav';
import { ListIcon, PinIcon, UserIcon } from '@/shared/ui/icons';

const TABS: AreaTab[] = [
  { to: '/app', end: true, label: 'Đặt xe', Icon: PinIcon },
  { to: '/app/activity', end: false, label: 'Hoạt động', Icon: ListIcon },
  { to: '/app/account', end: false, label: 'Tài khoản', Icon: UserIcon },
];

/**
 * The customer area fills the screen: on a phone the bottom tab bar (hidden on the quote and trip
 * screens so their action stays under the thumb), on a wide screen the road bar on top. Holds the
 * realtime connection open for the whole area.
 */
export function CustomerLayout() {
  useRealtimeSession();
  const onQuote = useMatch('/app/quote/:quoteId');
  const onTrip = useMatch('/app/trips/:tripId');
  return (
    <div className="flex h-dvh flex-col bg-white">
      <RoadBar tabs={TABS} label="Điều hướng chính" area="Khách hàng" />
      <main className="relative min-h-0 flex-1">
        <ConnectionBanner />
        <Outlet />
      </main>
      {!onQuote && !onTrip && <BottomTabs tabs={TABS} label="Điều hướng chính" />}
    </div>
  );
}
