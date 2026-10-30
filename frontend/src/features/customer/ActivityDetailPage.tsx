import { Link, useNavigate, useParams } from 'react-router';
import { ApiError } from '@/shared/api/ApiError';
import { messageFor } from '@/shared/api/messages';
import type { Trip } from '@/shared/api/types';
import { formatDistance, formatDuration } from '@/shared/lib/format';
import { BackIcon } from '@/shared/ui/icons';
import { Money } from '@/shared/ui/Money';
import { SERVICE_NAMES } from './booking';
import {
  ACTOR_NAMES,
  CANCELLED_BY,
  formatDateTime,
  formatTime,
  REASON_NAMES,
  STATUS_NAMES,
} from './tripText';
import { ENDED, useTrip, useTripHistory } from './trips';

/** /app/activity/:tripId: a past trip, its status timeline, and the way to its receipt. */
export default function ActivityDetailPage() {
  const { tripId = '' } = useParams();
  const navigate = useNavigate();
  const trip = useTrip(tripId);

  return (
    <div className="flex h-full flex-col overflow-y-auto">
      <header className="flex items-center gap-3 px-4 pt-5 pb-2">
        <button
          type="button"
          aria-label="Quay lại"
          onClick={() => navigate('/app/activity')}
          className="flex size-11 items-center justify-center rounded-full bg-concrete-2 text-asphalt"
        >
          <BackIcon />
        </button>
        <h1 className="m-0 text-[24px] leading-tight font-bold">Chi tiết chuyến</h1>
      </header>

      <div className="flex flex-col gap-5 px-5 pt-2 pb-8">
        {trip.isPending && <div aria-hidden="true" className="h-40 animate-pulse rounded-xl bg-concrete-2" />}
        {trip.error && (
          <p role="alert" className="m-0 text-[15px]">
            {trip.error instanceof ApiError && (trip.error.status === 404 || trip.error.status === 403)
              ? 'Không tìm thấy chuyến này.'
              : messageFor(trip.error)}
          </p>
        )}
        {trip.data && <Detail trip={trip.data} />}
      </div>
    </div>
  );
}

function Detail({ trip }: { trip: Trip }) {
  return (
    <>
      <section className="flex flex-col gap-2">
        <div className="flex items-baseline justify-between gap-3">
          <span className="text-lg font-bold">{SERVICE_NAMES[trip.serviceType]}</span>
          <span className="text-sm text-curb">{formatDateTime(trip.createdAt)}</span>
        </div>
        {trip.fare && (
          <p className="m-0 text-[15px] text-ink-2">
            <Money amount={trip.fare.quotedFare} className="font-semibold text-asphalt" /> ·{' '}
            {formatDistance(trip.fare.distanceMeters)}, {formatDuration(trip.fare.durationSeconds)}
          </p>
        )}
        <dl className="m-0 flex flex-col gap-1.5 text-[15px]">
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
        {trip.status === 'CANCELLED' && (
          <p className="m-0 text-[15px]">
            {CANCELLED_BY[trip.cancelledBy ?? 'SYSTEM']}
            {trip.cancelReason ? `: ${REASON_NAMES[trip.cancelReason]}` : ''}.
          </p>
        )}
        {trip.delivery && (
          <p className="m-0 text-[15px] text-ink-2">
            Giao cho {trip.delivery.recipientName}: {trip.delivery.packageDescription}
          </p>
        )}
      </section>

      {ENDED.has(trip.status) ? (
        <Link
          to={`/app/trips/${trip.id}/receipt`}
          className="flex min-h-13 items-center justify-center rounded-xl bg-asphalt text-[17px] font-bold text-white no-underline"
        >
          Xem hoá đơn
        </Link>
      ) : (
        <Link
          to={`/app/trips/${trip.id}`}
          className="flex min-h-13 items-center justify-center rounded-xl bg-sign text-[17px] font-bold text-asphalt no-underline"
        >
          Theo dõi chuyến
        </Link>
      )}

      <Timeline tripId={trip.id} />
    </>
  );
}

function Timeline({ tripId }: { tripId: string }) {
  const history = useTripHistory(tripId);
  if (history.isPending)
    return <div aria-hidden="true" className="h-32 animate-pulse rounded-xl bg-concrete-2" />;
  if (history.isError) {
    return (
      <p role="alert" className="m-0 text-[15px]">
        {messageFor(history.error)}
      </p>
    );
  }
  return (
    <section className="flex flex-col gap-2">
      <h2 className="m-0 text-base font-bold">Diễn biến</h2>
      <ol className="m-0 flex list-none flex-col p-0">
        {history.data.map((change, index) => (
          <li key={`${change.occurredAt}-${index}`} className="relative flex gap-3 pb-4 last:pb-0">
            {/* The line runs from under this dot to the next item's dot, padding included. */}
            {index < history.data.length - 1 && (
              <span aria-hidden="true" className="absolute top-5 bottom-0 left-[4.5px] w-px bg-field" />
            )}
            <span
              aria-hidden="true"
              className={`mt-1.5 size-2.5 shrink-0 rounded-full ${
                index === history.data.length - 1 ? 'bg-asphalt' : 'bg-field'
              }`}
            />
            <span className="flex flex-1 flex-col">
              <span className="flex justify-between gap-3 text-[15px]">
                <span className="font-semibold">{STATUS_NAMES[change.toStatus]}</span>
                <time dateTime={change.occurredAt} className="text-sm text-curb tabular-nums">
                  {formatTime(change.occurredAt)}
                </time>
              </span>
              {change.actorType !== 'SYSTEM' && (
                <span className="text-sm text-curb">bởi {ACTOR_NAMES[change.actorType]}</span>
              )}
            </span>
          </li>
        ))}
      </ol>
    </section>
  );
}
