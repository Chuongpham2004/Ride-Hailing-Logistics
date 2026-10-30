import { Link, useNavigate, useParams } from 'react-router';
import { ApiError } from '@/shared/api/ApiError';
import { messageFor } from '@/shared/api/messages';
import type { Payment, Trip } from '@/shared/api/types';
import { formatDistance, formatDuration } from '@/shared/lib/format';
import { Button } from '@/shared/ui/Button';
import { BackIcon } from '@/shared/ui/icons';
import { Money } from '@/shared/ui/Money';
import { fareLines, SERVICE_NAMES, useQuote } from './booking';
import { IN_FLIGHT, usePayments, useRefunds, useRetryPayment } from './payments';
import {
  CANCELLED_BY,
  formatDateTime,
  PAYMENT_STATUS_NAMES,
  PURPOSE_NAMES,
  REASON_NAMES,
  REFUND_REASON_NAMES,
} from './tripText';
import { ENDED, useTrip } from './trips';

/** Matches payment-service rhl.payment.max-attempts: after that, only support can help. */
const MAX_ATTEMPTS = 5;

/**
 * /app/trips/:tripId/receipt: what the trip cost, whether it was paid, a retry when the charge
 * failed, and any refunds (frontend-design.md §4.2). Payments run on their own after the trip
 * ends; there is no payment method to choose yet.
 */
export default function ReceiptPage() {
  const { tripId = '' } = useParams();
  const navigate = useNavigate();
  const trip = useTrip(tripId);

  return (
    <div className="flex h-full flex-col overflow-y-auto">
      <header className="flex items-center gap-3 px-4 pt-5 pb-2">
        <button
          type="button"
          aria-label="Quay lại"
          onClick={() => navigate(-1)}
          className="flex size-11 items-center justify-center rounded-full bg-concrete-2 text-asphalt"
        >
          <BackIcon />
        </button>
        <h1 className="m-0 text-[24px] leading-tight font-bold">Hoá đơn</h1>
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
        {trip.data && <Receipt trip={trip.data} />}
      </div>
    </div>
  );
}

function Receipt({ trip }: { trip: Trip }) {
  const ended = ENDED.has(trip.status);
  return (
    <>
      <section className="flex flex-col gap-2 rounded-xl border border-line p-4">
        <div className="flex items-baseline justify-between gap-3">
          <span className="font-semibold">{SERVICE_NAMES[trip.serviceType]}</span>
          <span className="text-sm text-curb">{formatDateTime(trip.createdAt)}</span>
        </div>
        <Stops trip={trip} />
        {trip.status === 'CANCELLED' && (
          <p className="m-0 text-sm text-curb">
            {CANCELLED_BY[trip.cancelledBy ?? 'SYSTEM']}
            {trip.cancelReason ? ` (${REASON_NAMES[trip.cancelReason]})` : ''}.
          </p>
        )}
        {trip.status === 'NO_DRIVER' && <p className="m-0 text-sm text-curb">Không tìm được tài xế.</p>}
      </section>

      {trip.status === 'COMPLETED' && <FareBreakdown trip={trip} />}

      {ended ? (
        <Payments trip={trip} />
      ) : (
        <div className="flex flex-col gap-2">
          <p className="m-0 text-[15px]">Hoá đơn có sau khi chuyến kết thúc.</p>
          <Link to={`/app/trips/${trip.id}`} className="font-semibold">
            Xem chuyến đang chạy
          </Link>
        </div>
      )}
    </>
  );
}

function Stops({ trip }: { trip: Trip }) {
  return (
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
  );
}

/** The booked price (UPFRONT: the final fare is the quote) and, when the quote is still there, its parts. */
function FareBreakdown({ trip }: { trip: Trip }) {
  const quote = useQuote(trip.fare?.quoteId ?? '', Boolean(trip.fare));
  if (!trip.fare) return null;
  return (
    <section className="flex flex-col gap-1">
      <h2 className="m-0 text-base font-bold">Cước chuyến</h2>
      <p className="m-0 font-plate text-[48px] leading-none font-bold">
        <Money amount={trip.fare.quotedFare} />
      </p>
      <p className="m-0 text-sm text-curb">
        {formatDistance(trip.fare.distanceMeters)}, {formatDuration(trip.fare.durationSeconds)} · giá chốt lúc
        đặt
      </p>
      {quote.data && (
        <dl className="m-0 mt-2 flex flex-col text-[15px]">
          {fareLines(quote.data).map((line) => (
            <div
              key={line.label}
              className="flex justify-between gap-3 border-t border-dashed border-line py-2"
            >
              <dt className="text-curb">{line.label}</dt>
              <dd className="m-0">
                <Money amount={line.amount} />
              </dd>
            </div>
          ))}
        </dl>
      )}
    </section>
  );
}

function Payments({ trip }: { trip: Trip }) {
  const payments = usePayments(trip.id);

  if (payments.isPending) {
    return <div aria-hidden="true" className="h-20 animate-pulse rounded-xl bg-concrete-2" />;
  }
  if (payments.isError) {
    return (
      <p role="alert" className="m-0 text-[15px]">
        {messageFor(payments.error)}
      </p>
    );
  }
  if (payments.data.length === 0) {
    // Cancelled before a driver took it: always free, nothing will come.
    const free = trip.status !== 'COMPLETED' && !trip.acceptedAt;
    if (payments.waiting && !free) {
      return (
        <p className="m-0 text-[15px] text-ink-2" aria-live="polite">
          {trip.status === 'COMPLETED' ? 'Đang tạo khoản thanh toán…' : 'Đang kiểm tra phí huỷ…'}
        </p>
      );
    }
    return (
      <p className="m-0 text-[15px] text-ink-2">
        {trip.status === 'COMPLETED'
          ? 'Chưa có thông tin thanh toán. Thử mở lại hoá đơn sau ít phút.'
          : 'Chuyến này không phát sinh khoản thu nào.'}
      </p>
    );
  }

  return (
    <section className="flex flex-col gap-3">
      <h2 className="m-0 text-base font-bold">Thanh toán</h2>
      {payments.data.map((payment) => (
        <PaymentCard key={payment.id} payment={payment} />
      ))}
    </section>
  );
}

function PaymentCard({ payment }: { payment: Payment }) {
  const retry = useRetryPayment(payment.tripId);
  const refunds = useRefunds(payment);
  const failed = payment.status === 'FAILED';
  const tone = failed
    ? 'bg-stop-tint text-stop'
    : IN_FLIGHT.has(payment.status)
      ? 'bg-[#FDF3D0]'
      : 'bg-concrete-2';

  return (
    <div className="flex flex-col gap-3 rounded-xl border border-line p-4">
      <div className="flex items-baseline justify-between gap-3">
        <span className="font-semibold">{PURPOSE_NAMES[payment.purpose]}</span>
        <Money amount={payment.amount} className="text-lg font-bold" />
      </div>
      <div className="flex items-center justify-between gap-3 text-sm">
        <span className={`rounded-full px-2.5 py-0.5 font-semibold ${tone}`} aria-live="polite">
          {PAYMENT_STATUS_NAMES[payment.status]}
        </span>
        {payment.succeededAt && <span className="text-curb">{formatDateTime(payment.succeededAt)}</span>}
      </div>

      {failed && (
        <div className="flex flex-col gap-2">
          {payment.attemptCount < MAX_ATTEMPTS ? (
            <>
              <p className="m-0 text-[15px]">Lần thu tiền gần nhất không thành công. Bạn có thể thử lại.</p>
              {retry.isError && (
                <p role="alert" className="m-0 text-sm text-stop">
                  {messageFor(retry.error)}
                </p>
              )}
              <Button variant="dark" busy={retry.isPending} onClick={() => retry.mutate(payment.id)}>
                Thanh toán lại
              </Button>
            </>
          ) : (
            <p className="m-0 text-[15px]">
              Đã thử {MAX_ATTEMPTS} lần không thành công. Vui lòng liên hệ tổng đài.
            </p>
          )}
        </div>
      )}

      {payment.refundedAmount > 0 && (
        <p className="m-0 flex justify-between gap-3 text-[15px]">
          <span className="text-curb">Đã hoàn</span>
          <Money amount={payment.refundedAmount} />
        </p>
      )}
      {refunds.data && refunds.data.length > 0 && (
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
          {refunds.data.map((refund) => (
            <li key={refund.id} className="flex flex-col border-t border-dashed border-line pt-2 text-sm">
              <span className="flex justify-between gap-3">
                <span>{REFUND_REASON_NAMES[refund.reason]}</span>
                <Money amount={refund.amount} />
              </span>
              <span className="text-curb">
                {refund.status === 'SUCCEEDED'
                  ? `Đã hoàn ${refund.completedAt ? formatDateTime(refund.completedAt) : ''}`
                  : refund.status === 'PENDING'
                    ? 'Đang hoàn tiền'
                    : 'Hoàn tiền không thành công, tổng đài sẽ xử lý'}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
