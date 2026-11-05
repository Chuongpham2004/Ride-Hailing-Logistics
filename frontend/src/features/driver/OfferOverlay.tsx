import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { ApiError } from '@/shared/api/ApiError';
import { messageFor } from '@/shared/api/messages';
import { formatDistance } from '@/shared/lib/format';
import { useCountdown } from '@/shared/lib/useCountdown';
import { Button } from '@/shared/ui/Button';
import { ParcelIcon, RideIcon } from '@/shared/ui/icons';
import { useAcceptOffer, useDeclineOffer, useOffers, type PendingOffer } from './offers';
import { SERVICE_NAMES } from './profile';

/** Answers that mean the offer is gone: close the overlay instead of offering a retry. */
const GONE = new Set(['OFFER_EXPIRED', 'DRIVER_ALREADY_ASSIGNED', 'RESOURCE_NOT_FOUND']);

/** Full-screen offer with a countdown, over whatever driver screen is open (frontend-design.md §4.3). */
export function OfferOverlay() {
  const offer = useOffers((state) => state.offer);
  const notice = useOffers((state) => state.notice);
  const clearNotice = useOffers((state) => state.clearNotice);

  useEffect(() => {
    if (!notice) return;
    const id = setTimeout(clearNotice, 4000);
    return () => clearTimeout(id);
  }, [notice, clearNotice]);

  if (offer) return <OfferCard key={offer.id} offer={offer} />;
  if (notice) {
    return (
      <p
        role="status"
        className="absolute inset-x-4 top-5 z-30 m-0 rounded-xl bg-asphalt px-4 py-3 text-center text-[15px] text-white shadow-lg"
      >
        {notice}
      </p>
    );
  }
  return null;
}

function OfferCard({ offer }: { offer: PendingOffer }) {
  const navigate = useNavigate();
  const accept = useAcceptOffer();
  const decline = useDeclineOffer();
  const drop = useOffers((state) => state.drop);
  const secondsLeft = useCountdown(offer.expiresAt);
  // The whole window, for the ring; at least one second so a late offer still draws.
  const [total] = useState(() => Math.max(1, Math.round((Date.parse(offer.expiresAt) - Date.now()) / 1000)));
  const busy = accept.isPending || decline.isPending;

  useAlert();

  // Time is up and nobody answered: the server expires it too (DRIVER_OFFER_EXPIRED follows).
  useEffect(() => {
    if (secondsLeft === 0 && !busy) drop(offer.id, 'Offer đã hết hạn.');
  }, [secondsLeft, busy, drop, offer.id]);

  const onAccept = () =>
    accept.mutate(offer.id, {
      onSuccess: (trip) => {
        navigate(`/driver/trips/${trip.id}`);
        drop(offer.id);
      },
      onError: (error) => {
        if (error instanceof ApiError && GONE.has(error.code)) drop(offer.id, messageFor(error));
      },
    });

  const Icon = offer.serviceType === 'DELIVERY' ? ParcelIcon : RideIcon;
  const fraction = Math.min(1, secondsLeft / total);

  return (
    <div
      role="alertdialog"
      aria-modal="true"
      aria-labelledby="offer-title"
      className="absolute inset-0 z-30 flex flex-col justify-end bg-asphalt/70"
    >
      <div className="flex flex-col gap-4 rounded-t-sheet bg-white px-5 pt-5 pb-[max(20px,env(safe-area-inset-bottom))]">
        <div className="flex items-center gap-4">
          <CountdownRing fraction={fraction} seconds={secondsLeft} />
          <div className="flex min-w-0 flex-col gap-1">
            <h2 id="offer-title" className="m-0 flex items-center gap-2 text-xl font-bold">
              <Icon aria-hidden="true" />
              Chuyến mới · {SERVICE_NAMES[offer.serviceType]}
            </h2>
            <p className="m-0 text-[15px] text-ink-2">
              Cách điểm đón <strong>{formatDistance(offer.estimatedPickupDistanceMeters)}</strong>
            </p>
          </div>
        </div>

        <div className="flex gap-3 rounded-[10px] bg-concrete-2 px-3.5 py-3">
          <span
            aria-hidden="true"
            className="mt-1.5 size-3 shrink-0 rounded-full border-2 border-asphalt bg-sign"
          />
          <p className="m-0 text-[15px]">
            <span className="sr-only">Điểm đón: </span>
            {offer.pickup.address ??
              `${offer.pickup.latitude.toFixed(5)}, ${offer.pickup.longitude.toFixed(5)}`}
          </p>
        </div>
        <p className="m-0 text-sm text-curb">Điểm đến hiện sau khi nhận chuyến.</p>

        {(accept.isError || decline.isError) && (
          <p role="alert" className="m-0 text-sm text-stop">
            {messageFor(accept.error ?? decline.error)}
          </p>
        )}
        <Button busy={accept.isPending} disabled={busy} onClick={onAccept}>
          Nhận chuyến
        </Button>
        <Button
          variant="secondary"
          busy={decline.isPending}
          disabled={busy}
          onClick={() => decline.mutate(offer.id)}
        >
          Từ chối
        </Button>
      </div>
    </div>
  );
}

const RING_R = 26;
const RING_C = 2 * Math.PI * RING_R;

function CountdownRing({ fraction, seconds }: { fraction: number; seconds: number }) {
  return (
    <div className="relative size-16 shrink-0" role="timer" aria-label={`Còn ${seconds} giây`}>
      <svg viewBox="0 0 64 64" className="size-16 -rotate-90" aria-hidden="true">
        <circle cx="32" cy="32" r={RING_R} fill="none" stroke="var(--color-concrete)" strokeWidth="6" />
        <circle
          cx="32"
          cy="32"
          r={RING_R}
          fill="none"
          stroke={seconds <= 5 ? 'var(--color-stop)' : 'var(--color-sign)'}
          strokeWidth="6"
          strokeLinecap="round"
          strokeDasharray={RING_C}
          strokeDashoffset={RING_C * (1 - fraction)}
          style={{ transition: 'stroke-dashoffset 1s linear' }}
        />
      </svg>
      <span className="absolute inset-0 flex items-center justify-center font-plate text-2xl font-bold">
        {seconds}
      </span>
    </div>
  );
}

/**
 * A short two-tone beep and a vibration when an offer appears: offers only reach an open tab, so
 * the driver must notice them (frontend-design.md §10). Browsers may block sound until the page
 * has been clicked once; that is fine, the overlay still shows.
 */
function useAlert(): void {
  const played = useRef(false);
  useEffect(() => {
    if (played.current) return;
    played.current = true;
    navigator.vibrate?.([200, 100, 200]);
    const AudioCtx = window.AudioContext as typeof AudioContext | undefined;
    if (!AudioCtx) return;
    try {
      const ctx = new AudioCtx();
      [880, 1175].forEach((frequency, i) => {
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.frequency.value = frequency;
        gain.gain.value = 0.15;
        osc.connect(gain).connect(ctx.destination);
        const at = ctx.currentTime + i * 0.18;
        osc.start(at);
        osc.stop(at + 0.15);
      });
      setTimeout(() => void ctx.close(), 800);
    } catch {
      // No audio device or blocked: the overlay is enough.
    }
  }, []);
}
