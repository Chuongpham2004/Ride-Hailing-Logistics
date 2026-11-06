import { useQueryClient } from '@tanstack/react-query';
import { useId, useState } from 'react';
import { Link, useParams } from 'react-router';
import { ApiError } from '@/shared/api/ApiError';
import { messageFor } from '@/shared/api/messages';
import type { CancelReason, Trip } from '@/shared/api/types';
import { formatDistance, formatDuration } from '@/shared/lib/format';
import { MapView } from '@/shared/map/MapView';
import { MapPanel } from '@/shared/ui/MapPanel';
import { BESIDE_PANEL, useMapPanel } from '@/shared/ui/panelLayout';
import type { LngLat } from '@/shared/map/polyline';
import { Button } from '@/shared/ui/Button';
import { Money } from '@/shared/ui/Money';
import { Sheet } from '@/shared/ui/Sheet';
import { ENDED, useCancelTrip, useTrip, useTripSnapshotSync } from '@/shared/trips/trips';
import {
  CANCEL_REASON_NAMES,
  DRIVER_CANCELLABLE,
  DRIVER_REASONS,
  NEXT_STEP,
  stepErrorText,
  useTripStep,
  type TripStep,
} from './driverTrips';
import { useLegRoute, waypointsFrom } from './driverRoute';
import { LocationPanel } from './LocationPanel';
import { distanceMeters, useDriverLocation, useMapPosition } from './location';
import { rememberActiveTrip, SERVICE_NAMES } from './profile';

/** Further than this from the pickup, "Đã đến" asks again (the server does not check). */
const ARRIVE_CHECK_M = 200;

const PACKAGE_SIZES = { SMALL: 'Nhỏ', MEDIUM: 'Vừa', LARGE: 'Lớn' } as const;

/**
 * /driver/trips/:id: one main button for the next step (frontend-design.md §4.3). Trip events
 * reach the driver on the personal channel, so no SUBSCRIBE_TRIP is needed.
 */
export default function DriverTripPage() {
  const { tripId = '' } = useParams();
  const { data: trip, isPending, error } = useTrip(tripId);
  useTripSnapshotSync(tripId);
  const { ref: sheetRef, insets } = useMapPanel();
  const ended = trip !== undefined && ENDED.has(trip.status);
  const { driver, onPick } = useMapPosition(!ended);
  // On the way to the pickup the map shows the pickup; once on board, the way to the drop-off.
  const toDropoff = trip?.status === 'IN_TRIP' || ended;
  // Road route of the current leg; without one (estimate, still loading) a straight dashed line.
  const route = useLegRoute(trip);

  return (
    <div className="absolute inset-0">
      <MapView
        className="absolute inset-0"
        pickup={trip?.pickup}
        dropoff={trip ? trip.dropoff : null}
        route={route}
        straightLine={toDropoff}
        driver={ended ? null : driver}
        onPick={ended ? undefined : onPick}
        bottomInset={insets.bottom}
        leftInset={insets.left}
      />
      {!ended && <LocationPanel online className={`absolute top-14 right-4 left-4 ${BESIDE_PANEL}`} />}

      <MapPanel panelRef={sheetRef} maxHeight={80}>
        <Sheet>
          {isPending && <div aria-hidden="true" className="h-40 animate-pulse rounded bg-concrete-2" />}
          {error && (
            <div className="flex flex-col gap-3">
              <p role="alert" className="m-0 text-[15px]">
                {error instanceof ApiError && (error.status === 404 || error.status === 403)
                  ? 'Không tìm thấy chuyến này.'
                  : messageFor(error)}
              </p>
              <Link to="/driver" className="font-semibold">
                Về trang chủ
              </Link>
            </div>
          )}
          {trip && <TripSheet key={trip.status} trip={trip} route={route} />}
        </Sheet>
      </MapPanel>
    </div>
  );
}

function TripSheet({ trip, route }: { trip: Trip; route: LngLat[] | null }) {
  const [cancelling, setCancelling] = useState(false);
  if (cancelling && DRIVER_CANCELLABLE.has(trip.status)) {
    return <CancelPanel trip={trip} onClose={() => setCancelling(false)} />;
  }
  const delivery = trip.serviceType === 'DELIVERY';
  return (
    <>
      <StepBlock trip={trip} />
      <Stops trip={trip} />
      {delivery && trip.delivery && !ENDED.has(trip.status) && <Recipient trip={trip} />}
      <SimulatedDrive trip={trip} route={route} />
      {DRIVER_CANCELLABLE.has(trip.status) && (
        <Button variant="secondary" onClick={() => setCancelling(true)}>
          Huỷ chuyến
        </Button>
      )}
    </>
  );
}

const TITLES: Partial<Record<Trip['status'], string>> = {
  ACCEPTED: 'Đã nhận chuyến',
  PICKING_UP: 'Đang đi đón',
  ARRIVED: 'Đã đến điểm đón',
};

function StepBlock({ trip }: { trip: Trip }) {
  const step = NEXT_STEP[trip.status];
  const delivery = trip.serviceType === 'DELIVERY';

  if (trip.status === 'COMPLETED') {
    return (
      <div className="flex flex-col gap-2">
        <h1 className="m-0 text-xl font-bold text-go">
          {delivery ? 'Đã giao hàng' : 'Chuyến đi đã hoàn tất'}
        </h1>
        {trip.fare && (
          <p className="m-0 font-plate text-[48px] leading-none font-bold">
            <Money amount={trip.fare.quotedFare} />
          </p>
        )}
        <p className="m-0 text-sm text-curb">Khách trả tự động; thu nhập vào ví sau khi thanh toán xong.</p>
        <HomeLink />
      </div>
    );
  }
  if (trip.status === 'CANCELLED' || trip.status === 'NO_DRIVER') {
    const by =
      trip.cancelledBy === 'DRIVER'
        ? 'Bạn đã huỷ chuyến'
        : trip.cancelledBy === 'CUSTOMER'
          ? 'Khách đã huỷ chuyến'
          : 'Chuyến đã bị huỷ';
    return (
      <div className="flex flex-col gap-2">
        <h1 className="m-0 text-xl font-bold">{by}</h1>
        {trip.cancelReason && (
          <p className="m-0 text-[15px] text-ink-2">Lý do: {CANCEL_REASON_NAMES[trip.cancelReason]}.</p>
        )}
        <HomeLink />
      </div>
    );
  }
  if (!step) {
    return <h1 className="m-0 text-xl font-bold">Đang cập nhật chuyến…</h1>;
  }

  return (
    <div className="flex flex-col gap-3" aria-live="polite">
      <h1 className="m-0 text-xl font-bold">
        {trip.status === 'IN_TRIP' ? (delivery ? 'Đang giao hàng' : 'Đang chở khách') : TITLES[trip.status]}
      </h1>
      {step === 'start-pickup' && <PlainStep trip={trip} step={step} label="Bắt đầu đi đón" />}
      {step === 'arrive' && <ArriveStep trip={trip} />}
      {step === 'start' && (
        <CodeStep
          trip={trip}
          step={step}
          codeName="mã đón"
          hint={delivery ? 'Hỏi người gửi mã đón để nhận hàng.' : 'Hỏi khách mã đón rồi nhập vào đây.'}
          label={delivery ? 'Đã lấy hàng, bắt đầu giao' : 'Bắt đầu chuyến'}
        />
      )}
      {step === 'complete' &&
        (delivery ? (
          <CodeStep
            trip={trip}
            step={step}
            codeName="mã giao"
            hint={`Hỏi ${trip.delivery?.recipientName ?? 'người nhận'} mã giao rồi nhập vào đây.`}
            label="Hoàn tất giao hàng"
          />
        ) : (
          <PlainStep trip={trip} step={step} label="Hoàn tất chuyến" />
        ))}
    </div>
  );
}

function HomeLink() {
  return (
    <Link
      to="/driver"
      className="flex min-h-13 items-center justify-center rounded-xl bg-asphalt text-[17px] font-bold text-white no-underline"
    >
      Về trang chủ
    </Link>
  );
}

function PlainStep({ trip, step, label }: { trip: Trip; step: TripStep; label: string }) {
  const move = useTripStep(trip.id);
  return (
    <>
      {move.isError && (
        <p role="alert" className="m-0 text-sm text-stop">
          {messageFor(move.error)}
        </p>
      )}
      <Button busy={move.isPending} onClick={() => move.mutate({ step })}>
        {label}
      </Button>
    </>
  );
}

/** The server takes "arrived" anywhere; ask again when the car is still far from the pickup. */
function ArriveStep({ trip }: { trip: Trip }) {
  const move = useTripStep(trip.id);
  const position = useDriverLocation((state) => state.position);
  const [confirming, setConfirming] = useState(false);
  const away = position ? distanceMeters(position, trip.pickup) : null;

  const onArrive = () => {
    if (!confirming && away !== null && away > ARRIVE_CHECK_M) {
      setConfirming(true);
      return;
    }
    move.mutate({ step: 'arrive' });
  };

  return (
    <>
      {confirming && away !== null && (
        <p role="alert" className="m-0 rounded-[10px] bg-concrete-2 px-3.5 py-3 text-[15px]">
          Bạn còn cách điểm đón khoảng <strong>{formatDistance(away)}</strong>. Vẫn báo đã đến?
        </p>
      )}
      {move.isError && (
        <p role="alert" className="m-0 text-sm text-stop">
          {messageFor(move.error)}
        </p>
      )}
      <Button busy={move.isPending} onClick={onArrive}>
        {confirming ? 'Vẫn báo đã đến' : 'Đã đến điểm đón'}
      </Button>
      {confirming && (
        <Button variant="secondary" onClick={() => setConfirming(false)}>
          Chưa, đi tiếp
        </Button>
      )}
    </>
  );
}

/** A number pad entry for the pickup or delivery code the other side reads out. */
function CodeStep({
  trip,
  step,
  codeName,
  hint,
  label,
}: {
  trip: Trip;
  step: TripStep;
  codeName: string;
  hint: string;
  label: string;
}) {
  const move = useTripStep(trip.id);
  const [code, setCode] = useState('');
  const inputId = useId();
  const errorId = useId();

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    move.mutate({ step, code: code || undefined });
  };

  return (
    <form onSubmit={submit} className="flex flex-col gap-2.5">
      <label htmlFor={inputId} className="text-[15px] text-ink-2">
        {hint}
      </label>
      <input
        id={inputId}
        inputMode="numeric"
        autoComplete="one-time-code"
        pattern="[0-9]*"
        maxLength={8}
        value={code}
        onChange={(event) => setCode(event.target.value.replace(/\D/g, ''))}
        aria-invalid={move.isError || undefined}
        aria-describedby={move.isError ? errorId : undefined}
        placeholder="••••••"
        className="h-16 rounded-xl border-[1.5px] border-field px-4 text-center font-plate text-[36px] tracking-[0.3em] aria-invalid:border-stop"
      />
      {move.isError && (
        <p id={errorId} role="alert" className="m-0 text-sm text-stop">
          {stepErrorText(move.error, codeName)}
        </p>
      )}
      <Button type="submit" busy={move.isPending}>
        {label}
      </Button>
    </form>
  );
}

function Stops({ trip }: { trip: Trip }) {
  return (
    <dl className="m-0 flex flex-col gap-2 border-t border-dashed border-line pt-3 text-[15px]">
      <div className="flex items-baseline justify-between gap-3">
        <dt className="font-semibold">{SERVICE_NAMES[trip.serviceType]}</dt>
        {trip.fare && (
          <dd className="m-0 text-sm text-curb">
            {formatDistance(trip.fare.distanceMeters)}, {formatDuration(trip.fare.durationSeconds)} ·{' '}
            <Money amount={trip.fare.quotedFare} />
          </dd>
        )}
      </div>
      <div className="flex gap-3">
        <dt className="sr-only">Điểm đón</dt>
        <span
          aria-hidden="true"
          className="mt-1.5 size-3 shrink-0 rounded-full border-2 border-asphalt bg-sign"
        />
        <dd className="m-0">{trip.pickup.address}</dd>
      </div>
      <div className="flex gap-3">
        <dt className="sr-only">Điểm đến</dt>
        <span aria-hidden="true" className="mt-1.5 size-3 shrink-0 rounded-[2px] bg-asphalt" />
        <dd className="m-0">{trip.dropoff.address}</dd>
      </div>
    </dl>
  );
}

function Recipient({ trip }: { trip: Trip }) {
  const d = trip.delivery!;
  return (
    <dl className="m-0 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 rounded-[10px] bg-concrete-2 px-3.5 py-3 text-[15px]">
      <dt className="text-curb">Người nhận</dt>
      <dd className="m-0 font-semibold">{d.recipientName}</dd>
      <dt className="text-curb">Điện thoại</dt>
      <dd className="m-0">
        <a href={`tel:${d.recipientPhone}`}>{d.recipientPhone}</a>
      </dd>
      <dt className="text-curb">Hàng</dt>
      <dd className="m-0">
        {d.packageDescription} · {PACKAGE_SIZES[d.packageSize]},{' '}
        {(d.packageWeightGrams / 1000).toLocaleString('vi-VN')} kg
      </dd>
      {d.instructions && (
        <>
          <dt className="text-curb">Ghi chú</dt>
          <dd className="m-0">{d.instructions}</dd>
        </>
      )}
    </dl>
  );
}

/** Simulated mode only: drive the car to the next stop, for testing without moving. */
function SimulatedDrive({ trip, route }: { trip: Trip; route: LngLat[] | null }) {
  const mode = useDriverLocation((state) => state.mode);
  const target = useDriverLocation((state) => state.target);
  const driveTo = useDriverLocation((state) => state.driveTo);
  if (mode !== 'simulated' || ENDED.has(trip.status) || trip.status === 'ARRIVED') return null;
  const toDropoff = trip.status === 'IN_TRIP';
  const stop = toDropoff ? trip.dropoff : trip.pickup;
  return (
    <Button
      variant="secondary"
      disabled={target !== null}
      // Along the road route when there is one, so the car does not cut through buildings.
      onClick={() => driveTo(stop, waypointsFrom(route, useDriverLocation.getState().position))}
    >
      {target
        ? 'Xe giả lập đang chạy…'
        : toDropoff
          ? 'Giả lập: chạy tới điểm đến'
          : 'Giả lập: chạy tới điểm đón'}
    </Button>
  );
}

function CancelPanel({ trip, onClose }: { trip: Trip; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [reason, setReason] = useState<CancelReason>(
    trip.status === 'ARRIVED' ? 'CUSTOMER_NO_SHOW' : 'VEHICLE_ISSUE',
  );
  const cancel = useCancelTrip(trip.id);
  // Only once the driver is at the pickup can the customer have failed to show up.
  const reasons = DRIVER_REASONS.filter((r) => r.value !== 'CUSTOMER_NO_SHOW' || trip.status === 'ARRIVED');

  return (
    <div className="flex flex-col gap-3.5">
      <h1 className="m-0 text-xl font-bold">Huỷ chuyến?</h1>
      <fieldset className="m-0 flex flex-col gap-1 border-0 p-0">
        <legend className="mb-1 p-0 text-sm text-curb">Lý do</legend>
        {reasons.map((option) => (
          <label key={option.value} className="flex items-center gap-3 py-1 text-[15px]">
            <input
              type="radio"
              name="cancel-reason"
              value={option.value}
              checked={reason === option.value}
              onChange={() => setReason(option.value)}
              className="size-5 accent-asphalt"
            />
            {option.label}
          </label>
        ))}
      </fieldset>
      {cancel.isError && (
        <p role="alert" className="m-0 text-sm text-stop">
          {messageFor(cancel.error)}
        </p>
      )}
      <Button
        variant="danger"
        busy={cancel.isPending}
        onClick={() =>
          cancel.mutate(reason, {
            onSuccess: (cancelled) => {
              rememberActiveTrip(queryClient, cancelled);
              onClose();
            },
          })
        }
      >
        Huỷ chuyến
      </Button>
      <Button variant="secondary" onClick={onClose} disabled={cancel.isPending}>
        Giữ chuyến
      </Button>
    </div>
  );
}
