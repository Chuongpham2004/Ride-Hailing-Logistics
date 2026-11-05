import { useId, useState } from 'react';
import { Link, useParams } from 'react-router';
import { ApiError } from '@/shared/api/ApiError';
import { messageFor } from '@/shared/api/messages';
import type { AdjustmentReason } from '@/shared/api/types';
import { useSession } from '@/shared/auth/session';
import { formatDateTime, formatVnd } from '@/shared/lib/format';
import { ENTRY_NAMES } from '@/shared/payments/text';
import { Button } from '@/shared/ui/Button';
import { Dialog } from '@/shared/ui/Dialog';
import { Money } from '@/shared/ui/Money';
import { MAX_ADJUSTMENT, parseVnd, useAdjustWallet, useDriverWallet } from './finance';
import { IdLookup } from './IdLookup';
import { canUse } from './sections';
import { ADJUSTMENT_REASON_NAMES, FIELD } from './text';
import { UUID } from './trips';

/** /admin/wallets and /admin/wallets/:driverId: look up a driver's wallet and correct it (Finance). */
export default function WalletPage() {
  const { driverId } = useParams();

  return (
    <div className="flex max-w-5xl flex-col gap-5 px-10 py-8">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <h1 className="m-0 text-[28px] leading-tight font-bold">Ví tài xế</h1>
        <IdLookup key={driverId} label="Mã tài xế" initial={driverId} to={(id) => `/admin/wallets/${id}`} />
      </div>
      {!driverId && (
        <p className="m-0 max-w-[60ch] text-ink-2">
          Nhập mã tài xế để xem số dư và các dòng ví. Mã tài xế có trên trang hồ sơ tài xế, trang chuyến và
          trang thanh toán.
        </p>
      )}
      {driverId && !UUID.test(driverId) && <p role="alert">Mã tài xế không hợp lệ.</p>}
      {driverId && UUID.test(driverId) && <Wallet driverId={driverId} />}
    </div>
  );
}

function Wallet({ driverId }: { driverId: string }) {
  const roles = useSession((state) => state.user?.roles ?? []);
  const wallet = useDriverWallet(driverId);
  const [adjusting, setAdjusting] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const first = wallet.data?.pages[0];
  const entries = wallet.data?.pages.flatMap((page) => page.entries) ?? [];

  if (wallet.isPending) return <div aria-hidden="true" className="h-64 animate-pulse rounded-xl bg-white" />;
  if (wallet.isError) {
    return (
      <p role="alert" className="m-0 text-[15px]">
        {wallet.error instanceof ApiError && wallet.error.status === 404
          ? 'Không tìm thấy ví của tài xế này.'
          : messageFor(wallet.error)}
      </p>
    );
  }

  return (
    <>
      <section className="flex flex-wrap items-end justify-between gap-4 rounded-xl border border-line bg-white px-6 py-5">
        <div className="flex flex-col gap-1">
          <span className="text-sm text-curb">Số dư</span>
          <Money amount={first?.balance ?? 0} className="font-plate text-4xl leading-none" />
          <span className="text-sm text-curb">
            Tài xế{' '}
            {canUse('drivers', roles) ? (
              <Link to={`/admin/drivers/${driverId}`} className="font-semibold">
                {driverId}
              </Link>
            ) : (
              driverId
            )}
          </span>
        </div>
        <Button size="compact" variant="dark" onClick={() => setAdjusting(true)}>
          Tạo điều chỉnh
        </Button>
      </section>

      {notice && (
        <p
          role="status"
          className="m-0 rounded-lg bg-white px-4 py-3 text-[15px] shadow-[inset_4px_0_0_var(--color-guide)]"
        >
          {notice}
        </p>
      )}

      {entries.length === 0 ? (
        <p className="m-0 text-ink-2">Ví chưa có dòng nào.</p>
      ) : (
        <div className="overflow-x-auto rounded-xl border border-line bg-white">
          <table className="w-full border-collapse text-left text-[15px]">
            <thead className="bg-concrete-2 text-sm text-curb">
              <tr>
                <th className="px-4 py-2.5 font-semibold">Lúc</th>
                <th className="px-4 py-2.5 font-semibold">Loại</th>
                <th className="px-4 py-2.5 text-right font-semibold">Số tiền</th>
                <th className="px-4 py-2.5 text-right font-semibold">Số dư sau</th>
                <th className="px-4 py-2.5 font-semibold">Liên quan</th>
              </tr>
            </thead>
            <tbody>
              {entries.map((entry) => (
                <tr key={entry.id} className="border-t border-line">
                  <td className="px-4 py-3 whitespace-nowrap tabular-nums">
                    {formatDateTime(entry.createdAt)}
                  </td>
                  <td className="px-4 py-3">{ENTRY_NAMES[entry.type]}</td>
                  <td
                    className={`px-4 py-3 text-right whitespace-nowrap tabular-nums ${entry.amount < 0 ? 'text-stop' : 'text-go'}`}
                  >
                    {entry.amount > 0 ? '+' : '−'}
                    {formatVnd(Math.abs(entry.amount))}
                  </td>
                  <td className="px-4 py-3 text-right whitespace-nowrap">
                    <Money amount={entry.balanceAfter} />
                  </td>
                  <td className="px-4 py-3 text-sm">
                    {entry.referenceType === 'PAYMENT' ? (
                      <Link to={`/admin/payments/${entry.referenceId}`}>Thanh toán</Link>
                    ) : (
                      <span className="text-curb">Điều chỉnh</span>
                    )}
                    {entry.tripId && canUse('trips', roles) && (
                      <>
                        {' · '}
                        <Link to={`/admin/trips/${entry.tripId}`}>Chuyến</Link>
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {wallet.hasNextPage && (
        <Button
          size="compact"
          variant="secondary"
          className="self-start"
          busy={wallet.isFetchingNextPage}
          onClick={() => void wallet.fetchNextPage()}
        >
          Xem thêm
        </Button>
      )}

      {adjusting && (
        <AdjustmentDialog
          driverId={driverId}
          balance={first?.balance ?? 0}
          onClose={() => setAdjusting(false)}
          onDone={(amount, balanceAfter) => {
            setAdjusting(false);
            setNotice(
              `Đã ${amount > 0 ? 'cộng' : 'trừ'} ${formatVnd(Math.abs(amount))}. Số dư mới ${formatVnd(balanceAfter)}.`,
            );
          }}
        />
      )}
    </>
  );
}

function AdjustmentDialog({
  driverId,
  balance,
  onClose,
  onDone,
}: {
  driverId: string;
  balance: number;
  onClose: () => void;
  onDone: (amount: number, balanceAfter: number) => void;
}) {
  const adjust = useAdjustWallet(driverId);
  // One key per dialog: a retry after a network error is the same adjustment, not a second one.
  const [idempotencyKey] = useState(() => crypto.randomUUID());
  const [direction, setDirection] = useState<'credit' | 'debit'>('credit');
  const [amountText, setAmountText] = useState('');
  const [reason, setReason] = useState<AdjustmentReason>('INCENTIVE');
  const [note, setNote] = useState('');
  const [refundId, setRefundId] = useState('');
  const ids = { amount: useId(), reason: useId(), note: useId(), refund: useId() };

  const clawback = reason === 'REFUND_CLAWBACK';
  const sign = clawback || direction === 'debit' ? -1 : 1;
  const amount = parseVnd(amountText);
  const errors = {
    amount:
      amount === null
        ? 'Nhập số tiền nguyên dương'
        : amount > MAX_ADJUSTMENT
          ? `Mỗi lần điều chỉnh tối đa ${formatVnd(MAX_ADJUSTMENT)}`
          : sign < 0 && amount > balance
            ? `Số dư chỉ còn ${formatVnd(balance)}`
            : undefined,
    note: reason === 'OTHER' && note.trim() === '' ? 'Lý do khác cần ghi chú' : undefined,
    refund: clawback && !UUID.test(refundId.trim()) ? 'Nhập mã lần hoàn tiền (UUID)' : undefined,
  };
  const ready = !errors.amount && !errors.note && !errors.refund;

  return (
    <Dialog title="Điều chỉnh ví" onClose={onClose}>
      <form
        className="flex flex-col gap-3"
        onSubmit={(event) => {
          event.preventDefault();
          if (!ready || amount === null) return;
          adjust.mutate(
            {
              amount: sign * amount,
              reason,
              note: note.trim() || undefined,
              refundId: clawback ? refundId.trim() : undefined,
              idempotencyKey,
            },
            { onSuccess: (created) => onDone(created.amount, created.balanceAfter) },
          );
        }}
      >
        <label htmlFor={ids.reason} className="text-sm font-semibold">
          Lý do
        </label>
        <select
          id={ids.reason}
          className={FIELD}
          value={reason}
          onChange={(e) => setReason(e.target.value as AdjustmentReason)}
        >
          {(Object.keys(ADJUSTMENT_REASON_NAMES) as AdjustmentReason[]).map((r) => (
            <option key={r} value={r}>
              {ADJUSTMENT_REASON_NAMES[r]}
            </option>
          ))}
        </select>

        {clawback ? (
          <>
            <label htmlFor={ids.refund} className="text-sm font-semibold">
              Mã lần hoàn tiền
            </label>
            <input
              id={ids.refund}
              className={FIELD}
              value={refundId}
              aria-invalid={errors.refund ? true : undefined}
              onChange={(e) => setRefundId(e.target.value)}
            />
            <span className="text-sm text-curb">
              Có trên trang thanh toán, mục Hoàn tiền. Luôn là khoản trừ, tối đa bằng số đã hoàn.
            </span>
            {refundId !== '' && errors.refund && <span className="text-sm text-stop">{errors.refund}</span>}
          </>
        ) : (
          <fieldset className="m-0 flex gap-4 border-0 p-0 text-[15px]">
            <legend className="mb-1 text-sm font-semibold">Chiều</legend>
            <label className="flex items-center gap-2">
              <input
                type="radio"
                name="direction"
                checked={direction === 'credit'}
                onChange={() => setDirection('credit')}
              />
              Cộng vào ví
            </label>
            <label className="flex items-center gap-2">
              <input
                type="radio"
                name="direction"
                checked={direction === 'debit'}
                onChange={() => setDirection('debit')}
              />
              Trừ khỏi ví
            </label>
          </fieldset>
        )}

        <label htmlFor={ids.amount} className="text-sm font-semibold">
          Số tiền (₫)
        </label>
        <input
          id={ids.amount}
          autoFocus
          inputMode="numeric"
          className={FIELD}
          value={amountText}
          aria-invalid={amountText !== '' && errors.amount ? true : undefined}
          onChange={(e) => setAmountText(e.target.value)}
        />
        {amountText !== '' && errors.amount && <span className="text-sm text-stop">{errors.amount}</span>}

        <label htmlFor={ids.note} className="text-sm font-semibold">
          Ghi chú {reason === 'OTHER' ? '' : '(không bắt buộc)'}
        </label>
        <textarea
          id={ids.note}
          rows={3}
          maxLength={500}
          className="resize-y rounded-lg border-[1.5px] border-field px-3 py-2 text-[15px]"
          value={note}
          onChange={(e) => setNote(e.target.value)}
        />
        {errors.note && <span className="text-sm text-stop">{errors.note}</span>}

        {adjust.error && (
          <p role="alert" className="m-0 text-[15px] text-stop">
            {messageFor(adjust.error)}
          </p>
        )}
        <div className="flex justify-end gap-2">
          <Button size="compact" variant="secondary" onClick={onClose}>
            Đóng
          </Button>
          <Button
            size="compact"
            type="submit"
            variant={sign < 0 ? 'danger' : 'dark'}
            disabled={!ready}
            busy={adjust.isPending}
          >
            {amount !== null && !errors.amount
              ? `${sign < 0 ? 'Trừ' : 'Cộng'} ${formatVnd(amount)}`
              : 'Xác nhận'}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
