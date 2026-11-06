import { useState, type FormEvent, type ReactElement } from 'react';
import { Link, useSearchParams } from 'react-router';
import { messageFor } from '@/shared/api/messages';
import type { ServiceType, TripStatus } from '@/shared/api/types';
import { SERVICE_NAMES } from '@/shared/drivers/text';
import { formatDateTime } from '@/shared/lib/format';
import { STATUS_NAMES } from '@/shared/trips/text';
import { Button } from '@/shared/ui/Button';
import { Money } from '@/shared/ui/Money';
import { TripStatusBadge } from '@/shared/ui/StatusBadge';
import { IdLookup } from './IdLookup';
import { FIELD, shortId } from './text';
import { UUID, useTripSearch, type TripFilter } from './trips';

const STATUSES = Object.keys(STATUS_NAMES) as TripStatus[];
const SERVICES = Object.keys(SERVICE_NAMES) as ServiceType[];
const KEYS = ['status', 'serviceType', 'customerId', 'driverId', 'from', 'to'] as const;

/** The filter lives in the address, so a search can be reloaded or sent to a colleague. */
function readFilter(params: URLSearchParams): TripFilter {
  const filter: TripFilter = {};
  const status = params.get('status') as TripStatus | null;
  if (status && STATUSES.includes(status)) filter.status = status;
  const service = params.get('serviceType') as ServiceType | null;
  if (service && SERVICES.includes(service)) filter.serviceType = service;
  for (const key of ['customerId', 'driverId'] as const) {
    const value = params.get(key);
    if (value && UUID.test(value)) filter[key] = value;
  }
  for (const key of ['from', 'to'] as const) {
    const value = params.get(key);
    if (value && /^\d{4}-\d{2}-\d{2}$/.test(value)) filter[key] = value;
  }
  return filter;
}

/** /admin/trips: any trip, newest first, by status, service, customer, driver and day (Support). */
export default function TripSearchPage() {
  const [params, setParams] = useSearchParams();
  const filter = readFilter(params);
  const trips = useTripSearch(filter);
  const items = trips.data?.pages.flatMap((page) => page.items) ?? [];

  return (
    <div className="flex w-full flex-col gap-5 px-10 py-8 2xl:px-14">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <h1 className="m-0 text-[28px] leading-tight font-bold">Chuyến</h1>
        <IdLookup label="Mở theo mã chuyến" to={(id) => `/admin/trips/${id}`} />
      </div>

      <Filters
        key={params.toString()}
        initial={filter}
        onApply={(next) => {
          const query: Record<string, string> = {};
          for (const key of KEYS) if (next[key]) query[key] = next[key]!;
          setParams(query);
        }}
      />

      {trips.isPending && <div aria-hidden="true" className="h-64 animate-pulse rounded-xl bg-white" />}
      {trips.isError && (
        <div className="flex items-center gap-3">
          <p role="alert" className="m-0 text-[15px]">
            {messageFor(trips.error)}
          </p>
          <Button size="compact" variant="secondary" onClick={() => void trips.refetch()}>
            Thử lại
          </Button>
        </div>
      )}
      {trips.isSuccess && items.length === 0 && (
        <p className="m-0 text-ink-2">Không có chuyến nào khớp bộ lọc.</p>
      )}

      {items.length > 0 && (
        <div className="overflow-x-auto rounded-xl border border-line bg-white">
          <table className="w-full border-collapse text-left text-[15px]">
            <thead className="bg-concrete-2 text-sm text-curb">
              <tr>
                <th className="px-4 py-2.5 font-semibold">Tạo lúc</th>
                <th className="px-4 py-2.5 font-semibold">Chuyến</th>
                <th className="px-4 py-2.5 font-semibold">Lộ trình</th>
                <th className="px-4 py-2.5 font-semibold">Giá</th>
                <th className="px-4 py-2.5 font-semibold">Trạng thái</th>
              </tr>
            </thead>
            <tbody>
              {items.map((trip) => (
                <tr key={trip.id} className="border-t border-line align-top hover:bg-concrete-2">
                  <td className="px-4 py-3 whitespace-nowrap tabular-nums">
                    {formatDateTime(trip.createdAt)}
                  </td>
                  <td className="px-4 py-3">
                    <Link to={`/admin/trips/${trip.id}`} className="font-semibold" title={trip.id}>
                      {shortId(trip.id)}
                    </Link>
                    <div className="text-sm text-curb">{SERVICE_NAMES[trip.serviceType]}</div>
                  </td>
                  <td className="max-w-md px-4 py-3">
                    <div className="truncate">{trip.pickup.address}</div>
                    <div className="truncate text-curb">→ {trip.dropoff.address}</div>
                  </td>
                  <td className="px-4 py-3 whitespace-nowrap">
                    {trip.fare ? <Money amount={trip.fare.quotedFare} /> : '—'}
                  </td>
                  <td className="px-4 py-3">
                    <TripStatusBadge status={trip.status} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {trips.hasNextPage && (
        <Button
          size="compact"
          variant="secondary"
          className="self-start"
          busy={trips.isFetchingNextPage}
          onClick={() => void trips.fetchNextPage()}
        >
          Xem thêm
        </Button>
      )}
    </div>
  );
}

function Filters({ initial, onApply }: { initial: TripFilter; onApply: (filter: TripFilter) => void }) {
  const [draft, setDraft] = useState<TripFilter>(initial);
  const [errors, setErrors] = useState<Partial<Record<'customerId' | 'driverId' | 'to', string>>>({});
  const set = (key: keyof TripFilter, value: string) =>
    setDraft((d) => ({ ...d, [key]: value || undefined }));

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const found: typeof errors = {};
    for (const key of ['customerId', 'driverId'] as const) {
      if (draft[key] && !UUID.test(draft[key].trim())) found[key] = 'Mã phải có dạng UUID';
    }
    if (draft.from && draft.to && draft.to < draft.from) found.to = 'Ngày kết thúc trước ngày bắt đầu';
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    onApply({ ...draft, customerId: draft.customerId?.trim(), driverId: draft.driverId?.trim() });
  };

  return (
    <form
      onSubmit={submit}
      aria-label="Lọc chuyến"
      className="grid grid-cols-[repeat(auto-fill,minmax(200px,1fr))] items-end gap-3 rounded-xl border border-line bg-white p-4"
    >
      <Field label="Trạng thái">
        <select className={FIELD} value={draft.status ?? ''} onChange={(e) => set('status', e.target.value)}>
          <option value="">Tất cả</option>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {STATUS_NAMES[s]}
            </option>
          ))}
        </select>
      </Field>
      <Field label="Dịch vụ">
        <select
          className={FIELD}
          value={draft.serviceType ?? ''}
          onChange={(e) => set('serviceType', e.target.value)}
        >
          <option value="">Tất cả</option>
          {SERVICES.map((s) => (
            <option key={s} value={s}>
              {SERVICE_NAMES[s]}
            </option>
          ))}
        </select>
      </Field>
      <Field label="Mã khách" error={errors.customerId}>
        <input
          className={FIELD}
          value={draft.customerId ?? ''}
          aria-invalid={errors.customerId ? true : undefined}
          onChange={(e) => set('customerId', e.target.value)}
        />
      </Field>
      <Field label="Mã tài xế" error={errors.driverId}>
        <input
          className={FIELD}
          value={draft.driverId ?? ''}
          aria-invalid={errors.driverId ? true : undefined}
          onChange={(e) => set('driverId', e.target.value)}
        />
      </Field>
      <Field label="Từ ngày">
        <input
          type="date"
          className={FIELD}
          value={draft.from ?? ''}
          onChange={(e) => set('from', e.target.value)}
        />
      </Field>
      <Field label="Đến ngày" error={errors.to}>
        <input
          type="date"
          className={FIELD}
          value={draft.to ?? ''}
          aria-invalid={errors.to ? true : undefined}
          onChange={(e) => set('to', e.target.value)}
        />
      </Field>
      <div className="flex gap-2">
        <Button size="compact" variant="dark" type="submit">
          Lọc
        </Button>
        <Button size="compact" variant="secondary" onClick={() => onApply({})}>
          Xoá lọc
        </Button>
      </div>
    </form>
  );
}

function Field({ label, error, children }: { label: string; error?: string; children: ReactElement }) {
  return (
    <label className="flex flex-col gap-1 text-sm font-semibold">
      {label}
      {children}
      {error && <span className="font-normal text-stop">{error}</span>}
    </label>
  );
}
