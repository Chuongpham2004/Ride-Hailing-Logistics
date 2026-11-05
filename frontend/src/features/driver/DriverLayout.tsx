import { Outlet } from 'react-router';
import { ConnectionBanner } from '@/shared/realtime/ConnectionBanner';
import { useRealtimeSession } from '@/shared/realtime/realtime';
import { RevealBackdrop } from '@/shared/ui/RevealBackdrop';
import { useLocationReporter } from './location';
import { OfferOverlay } from './OfferOverlay';
import { useOfferFeed } from './offers';
import { useDriverProfile, useDriverStateSync } from './profile';

/**
 * Frame for the driver area, like the customer one: at most 480 px wide, the realtime connection
 * open for the whole area, plus what has to run whatever screen is open: the location reports
 * while online and the offer overlay.
 */
export default function DriverLayout() {
  useRealtimeSession();
  useDriverStateSync();
  const { data: profile } = useDriverProfile();
  const online = profile !== undefined && profile.availability !== 'OFFLINE';
  useLocationReporter(online);
  useOfferFeed(online);

  return (
    <>
      <RevealBackdrop />
      <div className="relative mx-auto flex h-dvh max-w-[480px] flex-col bg-white shadow-[0_0_0_1px_var(--color-line)]">
        <main className="relative min-h-0 flex-1">
          <ConnectionBanner />
          <Outlet />
          <OfferOverlay />
        </main>
      </div>
    </>
  );
}
