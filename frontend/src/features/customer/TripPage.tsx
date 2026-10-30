import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { ApiError } from '@/shared/api/ApiError';
import { messageFor } from '@/shared/api/messages';
import type { CancelReason, Trip } from '@/shared/api/types';
import { formatCountdown, formatDistance, formatDuration } from '@/shared/lib/format';
import { useCountdown } from '@/shared/lib/useCountdown';
import { useHeight } from '@/shared/lib/useHeight';
import { MapView } from '@/shared/map/MapView';
import { Button } from '@/shared/ui/Button';
import { BackIcon } from '@/shared/ui/icons';
import { Money } from '@/shared/ui/Money';
import { Plate } from '@/shared/ui/Plate';
import { Sheet } from '@/shared/ui/Sheet';
import { decodeRoute, SERVICE_NAMES, useQuote } from './booking';
import { CANCELLED_BY, REASON_NAMES } from './tripText';
import {
  CUSTOMER_CANCELLABLE,
  ENDED,
  useCancellationFee,
  useCancelTrip,
  useLiveTrip,
  useTrip,
  type DriverPosition,
} from './trips';

/** Reasons a customer can give (CUSTOMER_NO_SHOW and VEHICLE_ISSUE are the driver's). */
const CUSTOMER_REASONS: { value: CancelReason; label: string }[] = [
  { value: 'CHANGED_MIND', label: 'Tôi đổi ý' },
  { value: 'WAIT_TOO_LONG', label: 'Chờ lâu quá' },
  { value: 'DRIVER_NOT_MOVING', label: 'Tài xế không di chuyển' },
  { value: 'WRONG_PICKUP', label: 'Sai điểm đón' },
  { value: 'SAFETY_CONCERN', label: 'Lo ngại an toàn' },
  { value: 'OTHER', label: 'Lý do khác' },
];

/** /app/trips/:id: one screen whose content follows the trip status (frontend-design.md §4.2). */
export default function TripPage() {
  const { tripId = '' } = useParams();
  const navigate = useNavigate();
  const { data: trip, isPending, error } = useTrip(tripId);
  const driver = useLiveTrip(tripId, trip);
  const [sheetRef, sheetHeight] = useHeight<HTMLDivElement>();

  // Before pickup the map shows the driver coming to the pickup; after it, the way to the drop-off.
  const toDropoff = trip?.status === 'IN_TRIP' || (trip !== undefined && ENDED.has(trip.status));
  const showsDropoff = toDropoff || trip?.status === 'MATCHING' || trip?.status === 'CREATED';
  // The road route comes from the booked quote; without it the map draws a dashed straight line.
  const { data: quote } = useQuote(trip?.fare?.quoteId ?? '', showsDropoff);
  const route = useMemo(() => decodeRoute(quote), [quote]);

  return (
    <div className="absolute inset-0">
      <MapView
        className="absolute inset-0"
        pickup={trip?.pickup}
        dropoff={showsDropoff ? trip?.dropoff : null}
        route={showsDropoff ? route : null}
        straightLine={showsDropoff}
        driver={driver}
        bottomInset={sheetHeight}
      />

      <button
        type="button"
        aria-label="Về trang đặt xe"
        onClick={() => navigate('/app')}
        className="absolute top-5 left-4 flex size-12 items-center justify-center rounded-full bg-white text-asphalt shadow-[0_2px_6px_rgba(43,46,51,0.18)]"
      >
        <BackIcon />
      </button>

      <div ref={sheetRef} className="absolute inset-x-0 bottom-0 max-h-[75%] overflow-y-auto">
        <Sheet>
          {isPending && <div aria-hidden="true" className="h-40 animate-pulse rounded bg-concrete-2" />}
          {error && (
            <div className="flex flex-col gap-3">
              <p role="alert" className="m-0 text-[15px]">
                {error instanceof ApiError && (error.status === 404 || error.status === 403)
                  ? 'Không tìm thấy chuyến này.'
                  : messageFor(error)}
              </p>
              <Link to="/app" className="font-semibold">
                Về trang đặt xe
              </Link>
            </div>
          )}
          {trip && <TripSheet trip={trip} driver={driver} />}
        </Sheet>
      </div>
    </div>
  );
}

function TripSheet({ trip, driver }: { trip: Trip; driver: DriverPosition | null }) {
  const [cancelling, setCancelling] = useState(false);
  const cancellable = CUSTOMER_CANCELLABLE.has(trip.status);

  if (cancelling && cancellable) {
    return <CancelPanel trip={trip} onClose={() => setCancelling(false)} />;
  }

  return (
    <>
      <StatusBlock trip={trip} driver={driver} />
      <Route trip={trip} />
      {cancellable && (
        <Button variant="secondary" onClick={() => setCancelling(true)}>
          Huỷ chuyến
        </Button>
      )}
    </>
  );
}

function StatusBlock({ trip, driver }: { trip: Trip; driver: DriverPosition | null }) {
  const secondsLeft = useCountdown(trip.matchingDeadline);
  const delivery = trip.serviceType === 'DELIVERY';

  switch (trip.status) {
    case 'CREATED':
    case 'MATCHING':
      return (
        <div className="flex flex-col gap-2" aria-live="polite">
          <div className="flex items-center gap-3">
            <span aria-hidden="true" className="relative flex size-6">
              <span className="absolute inline-flex size-full animate-ping rounded-full bg-sign opacity-75" />
              <span className="relative inline-flex size-6 rounded-full border-4 border-asphalt bg-sign" />
            </span>
            <h1 className="m-0 text-xl font-bold">Đang tìm tài xế gần bạn</h1>
          </div>
          {trip.matchingDeadline && secondsLeft > 0 && (
            <p className="m-0 text-sm text-curb" aria-live="off">
              Tìm thêm tối đa {formatCountdown(secondsLeft)}
            </p>
          )}
          <p className="m-0 text-sm text-curb">Huỷ lúc này không mất phí.</p>
        </div>
      );

    case 'NO_DRIVER':
      return (
        <div className="flex flex-col gap-3">
          <h1 className="m-0 text-xl font-bold">Chưa tìm được tài xế</h1>
          <p className="m-0 text-[15px] text-ink-2">
            Quanh điểm đón đang không có tài xế rảnh. Bạn không bị tính tiền; thử đặt lại sau ít phút.
          </p>
          <Link
            to="/app"
            className="flex min-h-13 items-center justify-center rounded-xl bg-sign text-[17px] font-bold text-asphalt no-underline"
          >
            Đặt lại
          </Link>
        </div>
      );

    case 'ACCEPTED':
    case 'PICKING_UP':
    case 'ARRIVED':
      return (
        <div className="flex flex-col gap-3" aria-live="polite">
          <h1 className="m-0 text-xl font-bold">
            {trip.status === 'ARRIVED' ? 'Tài xế đã đến điểm đón' : 'Tài xế đang đến đón'}
          </h1>
          <p className="m-0 text-[15px] text-ink-2">
            {trip.status === 'ARRIVED'
              ? 'Ra điểm đón và đọc mã này cho tài xế để bắt đầu chuyến.'
              : driver
                ? 'Theo dõi xe trên bản đồ. Khi gặp tài xế, đọc mã đón bên dưới.'
                : 'Đang chờ vị trí của tài xế. Khi gặp tài xế, đọc mã đón bên dưới.'}
          </p>
          {trip.pickupCode && (
            <div className="flex flex-col items-center gap-1.5 py-1">
              <Plate code={trip.pickupCode} label="Mã đón" />
              <span className="text-sm text-curb">Mã đón</span>
            </div>
          )}
        </div>
      );

    case 'IN_TRIP':
      return (
        <div className="flex flex-col gap-3" aria-live="polite">
          <h1 className="m-0 text-xl font-bold">
            {delivery ? 'Đang giao hàng' : 'Đang trên đường đến điểm đến'}
          </h1>
          {delivery && trip.deliveryCode && (
            <>
              <p className="m-0 text-[15px] text-ink-2">
                Gửi mã giao cho {trip.delivery?.recipientName ?? 'người nhận'}; tài xế chỉ hoàn tất khi nhận
                được mã này.
              </p>
              <div className="flex flex-col items-center gap-1.5 py-1">
                <Plate code={trip.deliveryCode} label="Mã giao" />
                <span className="text-sm text-curb">Mã giao</span>
              </div>
            </>
          )}
        </div>
      );

    case 'COMPLETED':
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
          <p className="m-0 text-sm text-curb">Tiền chuyến được thu tự động.</p>
          <Link
            to={`/app/trips/${trip.id}/receipt`}
            className="flex min-h-13 items-center justify-center rounded-xl bg-asphalt text-[17px] font-bold text-white no-underline"
          >
            Xem hoá đơn
          </Link>
          <Link to="/app" className="self-center font-semibold">
            Đặt chuyến mới
          </Link>
        </div>
      );

    case 'CANCELLED':
      return (
        <div className="flex flex-col gap-2">
          <h1 className="m-0 text-xl font-bold">{CANCELLED_BY[trip.cancelledBy ?? 'SYSTEM']}</h1>
          {trip.cancelReason && (
            <p className="m-0 text-[15px] text-ink-2">Lý do: {REASON_NAMES[trip.cancelReason]}.</p>
          )}
          {trip.acceptedAt && (
            <Link to={`/app/trips/${trip.id}/receipt`} className="font-semibold">
              Xem phí huỷ (nếu có)
            </Link>
          )}
          <Link to="/app" className="font-semibold">
            Đặt chuyến mới
          </Link>
        </div>
      );
  }
}

function Route({ trip }: { trip: Trip }) {
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

/** Shows what cancelling now costs (FR-CAN) before the customer confirms. */
function CancelPanel({ trip, onClose }: { trip: Trip; onClose: () => void }) {
  const [reason, setReason] = useState<CancelReason>('CHANGED_MIND');
  const fee = useCancellationFee(trip.id, reason, true);
  const cancel = useCancelTrip(trip.id);

  return (
    <div className="flex flex-col gap-3.5">
      <h1 className="m-0 text-xl font-bold">Huỷ chuyến?</h1>
      <fieldset className="m-0 flex flex-col gap-1 border-0 p-0">
        <legend className="mb-1 p-0 text-sm text-curb">Lý do</legend>
        {CUSTOMER_REASONS.map((option) => (
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

      <p className="m-0 rounded-[10px] bg-concrete-2 px-3.5 py-3 text-[15px]" aria-live="polite">
        {fee.isPending && 'Đang tính phí huỷ…'}
        {fee.isError && 'Chưa tính được phí huỷ lúc này.'}
        {fee.data &&
          (fee.data.fee > 0 ? (
            <>
              Phí huỷ:{' '}
              <strong>
                <Money amount={fee.data.fee} />
              </strong>
            </>
          ) : (
            'Huỷ lúc này không mất phí.'
          ))}
      </p>

      {cancel.isError && (
        <p role="alert" className="m-0 text-sm text-stop">
          {messageFor(cancel.error)}
        </p>
      )}
      <Button
        variant="danger"
        busy={cancel.isPending}
        disabled={fee.isPending}
        onClick={() => cancel.mutate(reason, { onSuccess: onClose })}
      >
        {fee.data && fee.data.fee > 0 ? 'Huỷ và trả phí' : 'Huỷ chuyến'}
      </Button>
      <Button variant="secondary" onClick={onClose} disabled={cancel.isPending}>
        Giữ chuyến
      </Button>
    </div>
  );
}
