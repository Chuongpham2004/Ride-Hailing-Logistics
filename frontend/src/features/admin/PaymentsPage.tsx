import { Link, useSearchParams } from 'react-router';
import { messageFor } from '@/shared/api/messages';
import type { PaymentStatus } from '@/shared/api/types';
import { formatDateTime } from '@/shared/lib/format';
import { PAYMENT_STATUS_NAMES, PURPOSE_NAMES } from '@/shared/payments/text';
import { Button } from '@/shared/ui/Button';
import { Money } from '@/shared/ui/Money';
import { PaymentStatusBadge } from '@/shared/ui/StatusBadge';
import { usePayments } from './finance';
import { IdLookup } from './IdLookup';
import { shortId } from './text';

const STATUSES = Object.keys(PAYMENT_STATUS_NAMES) as PaymentStatus[];

/** /admin/payments?status=: every payment, newest first (Finance). */
export default function PaymentsPage() {
  const [params, setParams] = useSearchParams();
  const asked = params.get('status') as PaymentStatus | null;
  const status = asked && STATUSES.includes(asked) ? asked : undefined;
  const payments = usePayments(status);
  const items = payments.data?.pages.flatMap((page) => page.items) ?? [];

  return (
    <div className="flex max-w-6xl flex-col gap-5 px-10 py-8">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <h1 className="m-0 text-[28px] leading-tight font-bold">Thanh toán</h1>
        <IdLookup label="Mở theo mã thanh toán" to={(id) => `/admin/payments/${id}`} />
      </div>

      <div role="tablist" aria-label="Trạng thái thanh toán" className="flex flex-wrap gap-2">
        {[undefined, ...STATUSES].map((s) => (
          <button
            key={s ?? 'ALL'}
            type="button"
            role="tab"
            aria-selected={s === status}
            onClick={() => setParams(s ? { status: s } : {})}
            className={`rounded-full border-[1.5px] px-4 py-1.5 text-[15px] ${
              s === status
                ? 'border-asphalt bg-asphalt font-semibold text-white'
                : 'border-field bg-white text-asphalt hover:bg-concrete'
            }`}
          >
            {s ? PAYMENT_STATUS_NAMES[s] : 'Tất cả'}
          </button>
        ))}
      </div>

      {payments.isPending && <div aria-hidden="true" className="h-64 animate-pulse rounded-xl bg-white" />}
      {payments.isError && (
        <div className="flex items-center gap-3">
          <p role="alert" className="m-0 text-[15px]">
            {messageFor(payments.error)}
          </p>
          <Button size="compact" variant="secondary" onClick={() => void payments.refetch()}>
            Thử lại
          </Button>
        </div>
      )}
      {payments.isSuccess && items.length === 0 && (
        <p className="m-0 text-ink-2">Không có khoản thanh toán nào.</p>
      )}

      {items.length > 0 && (
        <div className="overflow-x-auto rounded-xl border border-line bg-white">
          <table className="w-full border-collapse text-left text-[15px]">
            <thead className="bg-concrete-2 text-sm text-curb">
              <tr>
                <th className="px-4 py-2.5 font-semibold">Tạo lúc</th>
                <th className="px-4 py-2.5 font-semibold">Khoản</th>
                <th className="px-4 py-2.5 text-right font-semibold">Số tiền</th>
                <th className="px-4 py-2.5 text-right font-semibold">Đã hoàn</th>
                <th className="px-4 py-2.5 font-semibold">Lần thử</th>
                <th className="px-4 py-2.5 font-semibold">Trạng thái</th>
              </tr>
            </thead>
            <tbody>
              {items.map((payment) => (
                <tr key={payment.id} className="border-t border-line hover:bg-concrete-2">
                  <td className="px-4 py-3 whitespace-nowrap tabular-nums">
                    {formatDateTime(payment.createdAt)}
                  </td>
                  <td className="px-4 py-3">
                    <Link to={`/admin/payments/${payment.id}`} className="font-semibold" title={payment.id}>
                      {shortId(payment.id)}
                    </Link>
                    <div className="text-sm text-curb">{PURPOSE_NAMES[payment.purpose]}</div>
                  </td>
                  <td className="px-4 py-3 text-right whitespace-nowrap">
                    <Money amount={payment.amount} />
                  </td>
                  <td className="px-4 py-3 text-right whitespace-nowrap">
                    {payment.refundedAmount > 0 ? <Money amount={payment.refundedAmount} /> : '—'}
                  </td>
                  <td className="px-4 py-3 tabular-nums">{payment.attemptCount}</td>
                  <td className="px-4 py-3">
                    <PaymentStatusBadge status={payment.status} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {payments.hasNextPage && (
        <Button
          size="compact"
          variant="secondary"
          className="self-start"
          busy={payments.isFetchingNextPage}
          onClick={() => void payments.fetchNextPage()}
        >
          Xem thêm
        </Button>
      )}
    </div>
  );
}
