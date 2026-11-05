import { Link } from 'react-router';
import { messageFor } from '@/shared/api/messages';
import type { Trip } from '@/shared/api/types';
import { formatDateTime } from '@/shared/lib/format';
import { ENDED, useMyTrips } from '@/shared/trips/trips';
import { Button } from '@/shared/ui/Button';
import { Money } from '@/shared/ui/Money';
import { STATUS_NAMES } from './driverTrips';
import { SERVICE_NAMES } from './profile';

/** /driver/activity: the trips the driver took, newest first; each opens the trip screen. */
export default function DriverActivityPage() {
  const trips = useMyTrips();
  const items = trips.data?.pages.flatMap((page) => page.items) ?? [];

  return (
    <div className="flex h-full flex-col gap-4 overflow-y-auto px-5 py-8">
      <h1 className="m-0 text-[28px] leading-tight font-bold">Lịch sử chuyến</h1>

      {trips.isPending && (
        <div aria-hidden="true" className="flex flex-col gap-3">
          {[0, 1, 2].map((i) => (
            <div key={i} className="h-24 animate-pulse rounded-xl bg-concrete-2" />
          ))}
        </div>
      )}
      {trips.isError && (
        <div className="flex flex-col gap-3">
          <p role="alert" className="m-0 text-[15px]">
            {messageFor(trips.error)}
          </p>
          <Button variant="secondary" onClick={() => void trips.refetch()}>
            Thử lại
          </Button>
        </div>
      )}
      {trips.isSuccess && items.length === 0 && (
        <p className="m-0 text-ink-2">Bạn chưa chạy chuyến nào. Bật online ở trang chủ để nhận chuyến.</p>
      )}

      {items.length > 0 && (
        <ul className="m-0 flex list-none flex-col gap-3 p-0">
          {items.map((trip) => (
            <li key={trip.id}>
              <TripRow trip={trip} />
            </li>
          ))}
        </ul>
      )}

      {trips.hasNextPage && (
        <Button
          variant="secondary"
          busy={trips.isFetchingNextPage}
          onClick={() => void trips.fetchNextPage()}
        >
          Xem thêm
        </Button>
      )}
    </div>
  );
}

function TripRow({ trip }: { trip: Trip }) {
  const live = !ENDED.has(trip.status);
  const tone = live
    ? 'bg-sign text-asphalt'
    : trip.status === 'COMPLETED'
      ? 'bg-concrete-2 text-go'
      : 'bg-concrete-2 text-curb';
  return (
    <Link
      to={`/driver/trips/${trip.id}`}
      className={`flex flex-col gap-1.5 rounded-xl border p-4 text-asphalt no-underline hover:bg-concrete-2 ${
        live ? 'border-asphalt' : 'border-line'
      }`}
    >
      <span className="flex items-baseline justify-between gap-3">
        <span className="font-semibold">{SERVICE_NAMES[trip.serviceType]}</span>
        {trip.fare && <Money amount={trip.fare.quotedFare} className="font-semibold" />}
      </span>
      <span className="truncate text-[15px]">
        {trip.pickup.address} → {trip.dropoff.address}
      </span>
      <span className="flex items-center justify-between gap-3 text-sm">
        <span className="text-curb">{formatDateTime(trip.createdAt)}</span>
        <span className={`rounded-full px-2.5 py-0.5 font-semibold ${tone}`}>
          {STATUS_NAMES[trip.status]}
        </span>
      </span>
    </Link>
  );
}
