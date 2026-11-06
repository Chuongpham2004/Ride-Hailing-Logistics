import { useId, useState } from 'react';
import { Link, useParams } from 'react-router';
import { ApiError } from '@/shared/api/ApiError';
import { messageFor } from '@/shared/api/messages';
import type { Payment, RefundReason } from '@/shared/api/types';
import { useSession } from '@/shared/auth/session';
import { formatDateTime, formatVnd } from '@/shared/lib/format';
import { PAYMENT_STATUS_NAMES, PURPOSE_NAMES, REFUND_REASON_NAMES } from '@/shared/payments/text';
import { Button } from '@/shared/ui/Button';
import { Dialog } from '@/shared/ui/Dialog';
import { BackIcon } from '@/shared/ui/icons';
import { Money } from '@/shared/ui/Money';
import { Badge, PaymentStatusBadge } from '@/shared/ui/StatusBadge';
import { Card } from './Card';
import { parseVnd, useAttempts, usePayment, useRefund, useRefunds } from './finance';
import { canUse } from './sections';
import { FIELD } from './text';

const REFUND_STATUS = {
  PENDING: { tone: 'amber', label: 'Đang xử lý' },
  SUCCEEDED: { tone: 'green', label: 'Đã hoàn' },
  FAILED: { tone: 'red', label: 'Thất bại' },
} as const;

/** Only a captured payment with money left and no refund in flight can be refunded (payment-service Payment.refund). */
function refundable(payment: Payment): number {
  if (payment.status !== 'SUCCEEDED' && payment.status !== 'PARTIALLY_REFUNDED') return 0;
  return payment.amount - payment.refundedAmount;
}

/** /admin/payments/:paymentId: one payment, its provider attempts and refunds, and a new refund. */
export default function PaymentDetailPage() {
  const { paymentId = '' } = useParams();
  const payment = usePayment(paymentId);

  return (
    <div className="flex w-full flex-col gap-6 px-10 py-8 2xl:px-14">
      <Link to="/admin/payments" className="flex items-center gap-1 self-start text-[15px] font-semibold">
        <BackIcon width={18} height={18} /> Danh sách thanh toán
      </Link>
      {payment.isPending && <div aria-hidden="true" className="h-64 animate-pulse rounded-xl bg-white" />}
      {payment.isError && (
        <p role="alert" className="m-0 text-[15px]">
          {payment.error instanceof ApiError && payment.error.status === 404
            ? 'Không tìm thấy khoản thanh toán này.'
            : messageFor(payment.error)}
        </p>
      )}
      {payment.data && <Detail payment={payment.data} />}
    </div>
  );
}

function Detail({ payment }: { payment: Payment }) {
  const roles = useSession((state) => state.user?.roles ?? []);
  const [refunding, setRefunding] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const left = refundable(payment);

  return (
    <>
      <header className="flex flex-col gap-2">
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="m-0 text-[28px] leading-tight font-bold">
            {PURPOSE_NAMES[payment.purpose]} · <Money amount={payment.amount} />
          </h1>
          <PaymentStatusBadge status={payment.status} />
        </div>
        <p className="m-0 text-[15px] text-curb">
          Mã thanh toán <span className="text-asphalt tabular-nums">{payment.id}</span> · tạo{' '}
          {formatDateTime(payment.createdAt)}
        </p>
      </header>

      {notice && (
        <p
          role="status"
          className="m-0 rounded-lg bg-white px-4 py-3 text-[15px] shadow-[inset_4px_0_0_var(--color-guide)]"
        >
          {notice}
        </p>
      )}

      <div className="flex flex-wrap items-center gap-3">
        <Button size="compact" variant="dark" disabled={left <= 0} onClick={() => setRefunding(true)}>
          Hoàn tiền
        </Button>
        <span className="text-sm text-curb">
          {left > 0
            ? `Còn hoàn được tối đa ${formatVnd(left)}.`
            : payment.status === 'REFUND_PENDING'
              ? 'Đang chờ nhà cung cấp xử lý lần hoàn trước.'
              : `Không hoàn được khi khoản này ở trạng thái “${PAYMENT_STATUS_NAMES[payment.status]}”.`}
        </span>
      </div>

      <div className="flex flex-col gap-6 xl:block xl:columns-2 xl:gap-6 xl:[&>*]:mb-6 xl:[&>*]:break-inside-avoid">
        <Card title="Thông tin">
          <dl className="m-0 grid grid-cols-[max-content_1fr] gap-x-8 gap-y-2 text-[15px]">
            <dt className="text-curb">Chuyến</dt>
            <dd className="m-0 tabular-nums">
              {canUse('trips', roles) ? (
                <Link to={`/admin/trips/${payment.tripId}`} className="font-semibold">
                  {payment.tripId}
                </Link>
              ) : (
                payment.tripId
              )}
            </dd>
            <dt className="text-curb">Khách</dt>
            <dd className="m-0 tabular-nums">{payment.customerId}</dd>
            <dt className="text-curb">Tài xế</dt>
            <dd className="m-0 tabular-nums">
              {payment.driverId ? (
                <Link to={`/admin/wallets/${payment.driverId}`} className="font-semibold">
                  {payment.driverId}
                </Link>
              ) : (
                '—'
              )}
            </dd>
            <dt className="text-curb">Đã hoàn</dt>
            <dd className="m-0">
              <Money amount={payment.refundedAmount} />
            </dd>
            <dt className="text-curb">Nhà cung cấp</dt>
            <dd className="m-0">
              {payment.provider ?? '—'}
              {payment.providerRef && <span className="text-curb"> · {payment.providerRef}</span>}
            </dd>
            {payment.succeededAt && (
              <>
                <dt className="text-curb">Thu thành công</dt>
                <dd className="m-0">{formatDateTime(payment.succeededAt)}</dd>
              </>
            )}
            {payment.failureCode && (
              <>
                <dt className="text-curb">Mã lỗi</dt>
                <dd className="m-0 text-stop">{payment.failureCode}</dd>
              </>
            )}
          </dl>
        </Card>

        <Attempts paymentId={payment.id} />
        <Refunds paymentId={payment.id} />
      </div>

      {refunding && (
        <RefundDialog
          payment={payment}
          max={left}
          onClose={() => setRefunding(false)}
          onDone={(amount) => {
            setRefunding(false);
            setNotice(`Đã gửi yêu cầu hoàn ${formatVnd(amount)}. Kết quả cập nhật khi nhà cung cấp trả lời.`);
          }}
        />
      )}
    </>
  );
}

function Refunds({ paymentId }: { paymentId: string }) {
  const refunds = useRefunds(paymentId);
  return (
    <Card title="Hoàn tiền" className="xl:[column-span:all]">
      {refunds.isPending && (
        <div aria-hidden="true" className="h-16 animate-pulse rounded-lg bg-concrete-2" />
      )}
      {refunds.isError && <p role="alert">{messageFor(refunds.error)}</p>}
      {refunds.data?.length === 0 && <p className="m-0 text-[15px] text-curb">Chưa hoàn lần nào.</p>}
      {refunds.data && refunds.data.length > 0 && (
        <table className="w-full border-collapse text-left text-[15px]">
          <thead className="text-sm text-curb">
            <tr>
              <th className="py-1.5 pr-4 font-semibold">Lúc</th>
              <th className="py-1.5 pr-4 text-right font-semibold">Số tiền</th>
              <th className="py-1.5 pr-4 font-semibold">Lý do</th>
              <th className="py-1.5 font-semibold">Kết quả</th>
            </tr>
          </thead>
          <tbody>
            {refunds.data.map((refund) => (
              <tr key={refund.id} className="border-t border-line align-top">
                <td className="py-2 pr-4 whitespace-nowrap tabular-nums">
                  {formatDateTime(refund.createdAt)}
                </td>
                <td className="py-2 pr-4 text-right whitespace-nowrap">
                  <Money amount={refund.amount} />
                </td>
                <td className="py-2 pr-4">
                  {REFUND_REASON_NAMES[refund.reason]}
                  {refund.note && <div className="text-sm text-curb">{refund.note}</div>}
                  <div className="text-sm text-curb" title={refund.id}>
                    Mã hoàn {refund.id}
                  </div>
                </td>
                <td className="py-2">
                  <Badge tone={REFUND_STATUS[refund.status].tone}>{REFUND_STATUS[refund.status].label}</Badge>
                  {refund.failureCode && <div className="text-sm text-stop">{refund.failureCode}</div>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Card>
  );
}

function Attempts({ paymentId }: { paymentId: string }) {
  const attempts = useAttempts(paymentId);
  return (
    <Card title="Các lần thu">
      {attempts.isPending && (
        <div aria-hidden="true" className="h-16 animate-pulse rounded-lg bg-concrete-2" />
      )}
      {attempts.isError && <p role="alert">{messageFor(attempts.error)}</p>}
      {attempts.data?.length === 0 && (
        <p className="m-0 text-[15px] text-curb">Chưa gọi nhà cung cấp lần nào.</p>
      )}
      {attempts.data && attempts.data.length > 0 && (
        <table className="w-full border-collapse text-left text-[15px]">
          <thead className="text-sm text-curb">
            <tr>
              <th className="py-1.5 pr-4 font-semibold">Lần</th>
              <th className="py-1.5 pr-4 font-semibold">Lúc</th>
              <th className="py-1.5 pr-4 font-semibold">Nhà cung cấp</th>
              <th className="py-1.5 font-semibold">Kết quả</th>
            </tr>
          </thead>
          <tbody>
            {attempts.data.map((attempt) => (
              <tr key={attempt.attemptNo} className="border-t border-line">
                <td className="py-2 pr-4 tabular-nums">{attempt.attemptNo}</td>
                <td className="py-2 pr-4 tabular-nums">{formatDateTime(attempt.createdAt)}</td>
                <td className="py-2 pr-4">
                  {attempt.provider ?? '—'}
                  {attempt.providerRef && <span className="text-curb"> · {attempt.providerRef}</span>}
                </td>
                <td className="py-2">
                  <PaymentStatusBadge status={attempt.status} />
                  {attempt.failureCode && (
                    <span className="ml-2 text-sm text-stop">{attempt.failureCode}</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Card>
  );
}

function RefundDialog({
  payment,
  max,
  onClose,
  onDone,
}: {
  payment: Payment;
  max: number;
  onClose: () => void;
  onDone: (amount: number) => void;
}) {
  const refund = useRefund(payment.id);
  // One key per dialog: a retry after a network error is the same refund, not a second one.
  const [idempotencyKey] = useState(() => crypto.randomUUID());
  const [amountText, setAmountText] = useState(String(max));
  const [reason, setReason] = useState<RefundReason>('SERVICE_COMPLAINT');
  const [note, setNote] = useState('');
  const ids = { amount: useId(), reason: useId(), note: useId() };
  const amount = parseVnd(amountText);
  const amountError =
    amount === null ? 'Nhập số tiền nguyên dương' : amount > max ? `Tối đa ${formatVnd(max)}` : undefined;
  const noteError = reason === 'OTHER' && note.trim() === '' ? 'Lý do khác cần ghi chú' : undefined;
  const ready = !amountError && !noteError;

  return (
    <Dialog title="Hoàn tiền cho khách" onClose={onClose}>
      <form
        className="flex flex-col gap-3"
        onSubmit={(event) => {
          event.preventDefault();
          if (!ready || amount === null) return;
          refund.mutate(
            {
              amount,
              reason,
              note: note.trim() || undefined,
              idempotencyKey,
            },
            { onSuccess: (created) => onDone(created.amount) },
          );
        }}
      >
        <p className="m-0 text-[15px] text-ink-2">
          Khoản {PURPOSE_NAMES[payment.purpose].toLowerCase()} <Money amount={payment.amount} />, đã hoàn{' '}
          <Money amount={payment.refundedAmount} />. Hoàn tiền không tự trừ ví tài xế; cần thì tạo điều chỉnh
          ví “Thu lại phần đã hoàn” sau khi hoàn xong.
        </p>
        <label htmlFor={ids.amount} className="text-sm font-semibold">
          Số tiền (₫)
        </label>
        <input
          id={ids.amount}
          autoFocus
          inputMode="numeric"
          className={FIELD}
          value={amountText}
          aria-invalid={amountError ? true : undefined}
          onChange={(e) => setAmountText(e.target.value)}
        />
        {amountError && <span className="text-sm text-stop">{amountError}</span>}
        <label htmlFor={ids.reason} className="text-sm font-semibold">
          Lý do
        </label>
        <select
          id={ids.reason}
          className={FIELD}
          value={reason}
          onChange={(e) => setReason(e.target.value as RefundReason)}
        >
          {(Object.keys(REFUND_REASON_NAMES) as RefundReason[]).map((r) => (
            <option key={r} value={r}>
              {REFUND_REASON_NAMES[r]}
            </option>
          ))}
        </select>
        <label htmlFor={ids.note} className="text-sm font-semibold">
          Ghi chú nội bộ {reason === 'OTHER' ? '' : '(không bắt buộc)'}
        </label>
        <textarea
          id={ids.note}
          rows={3}
          maxLength={500}
          className="resize-y rounded-lg border-[1.5px] border-field px-3 py-2 text-[15px]"
          value={note}
          onChange={(e) => setNote(e.target.value)}
        />
        {noteError && <span className="text-sm text-stop">{noteError}</span>}
        {refund.error && (
          <p role="alert" className="m-0 text-[15px] text-stop">
            {messageFor(refund.error)}
          </p>
        )}
        <div className="flex justify-end gap-2">
          <Button size="compact" variant="secondary" onClick={onClose}>
            Đóng
          </Button>
          <Button size="compact" type="submit" variant="danger" disabled={!ready} busy={refund.isPending}>
            {amount !== null && !amountError ? `Hoàn ${formatVnd(amount)}` : 'Hoàn tiền'}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
