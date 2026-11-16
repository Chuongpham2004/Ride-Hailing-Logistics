import { useId, useState, type ReactNode } from 'react';
import { Link, useParams } from 'react-router';
import { ApiError } from '@/shared/api/ApiError';
import { messageFor } from '@/shared/api/messages';
import type { CancelReason, HandoverCode, StaffTripDetail, TripStatus } from '@/shared/api/types';
import { useSession } from '@/shared/auth/session';
import { SERVICE_NAMES } from '@/shared/drivers/text';
import { formatDateTime, formatDistance, formatDuration, formatTime } from '@/shared/lib/format';
import { PURPOSE_NAMES } from '@/shared/payments/text';
import { STATUS_NAMES } from '@/shared/trips/text';
import { ENDED } from '@/shared/trips/trips';
import { Button } from '@/shared/ui/Button';
import { BackIcon } from '@/shared/ui/icons';
import { Money } from '@/shared/ui/Money';
import { PaymentStatusBadge, TripStatusBadge } from '@/shared/ui/StatusBadge';
import { Card } from './Card';
import { useTripPayments } from './finance';
import { ReasonDialog } from './ReasonDialog';
import { canUse } from './sections';
import { ACTOR_NAMES, CANCEL_REASON_NAMES, changeReason, FIELD, OFFER_STATUS_NAMES } from './text';
import { MIN_CANCEL_NOTE, useCodeUnlock, useStaffCancel, useStaffTrip } from './trips';

const CODE_NAMES: Record<HandoverCode, string> = { PICKUP: 'Mã đón', DELIVERY: 'Mã giao' };

/** When a code is still needed, so unlocking it helps (trip-service Trip.unlockCode). */
const CODE_IN_USE: Record<HandoverCode, ReadonlySet<TripStatus>> = {
  PICKUP: new Set(['ACCEPTED', 'PICKING_UP', 'ARRIVED']),
  DELIVERY: new Set(['IN_TRIP']),
};

const PACKAGE_SIZES = { SMALL: 'Nhỏ', MEDIUM: 'Vừa', LARGE: 'Lớn' } as const;

/** /admin/trips/:tripId: the whole trip for support, and the exceptional cancellation. */
export default function TripDetailPage() {
  const { tripId = '' } = useParams();
  const detail = useStaffTrip(tripId);

  return (
    <div className="flex w-full flex-col gap-6 px-10 py-8 2xl:px-14">
      <Link to="/admin/trips" className="flex items-center gap-1 self-start text-[15px] font-semibold">
        <BackIcon width={18} height={18} /> Danh sách chuyến
      </Link>
      {detail.isPending && <div aria-hidden="true" className="h-64 animate-pulse rounded-xl bg-white" />}
      {detail.isError && (
        <p role="alert" className="m-0 text-[15px]">
          {detail.error instanceof ApiError && detail.error.status === 404
            ? 'Không tìm thấy chuyến này.'
            : messageFor(detail.error)}
        </p>
      )}
      {detail.data && <Detail detail={detail.data} />}
    </div>
  );
}

function Detail({ detail }: { detail: StaffTripDetail }) {
  const { trip } = detail;
  const roles = useSession((state) => state.user?.roles ?? []);
  const [cancelling, setCancelling] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const seesDrivers = canUse('drivers', roles);

  const driverLink = (driverId: string) =>
    seesDrivers ? (
      <Link to={`/admin/drivers/${driverId}`} className="font-semibold">
        {driverId}
      </Link>
    ) : (
      <span>{driverId}</span>
    );

  return (
    <>
      <header className="flex flex-col gap-2">
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="m-0 text-[28px] leading-tight font-bold">{SERVICE_NAMES[trip.serviceType]}</h1>
          <TripStatusBadge status={trip.status} />
        </div>
        <p className="m-0 text-[15px] text-curb">
          Mã chuyến <span className="text-asphalt tabular-nums">{trip.id}</span> · tạo{' '}
          {formatDateTime(trip.createdAt)}
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

      {!ENDED.has(trip.status) && (
        <div className="flex items-center gap-3">
          <Button size="compact" variant="danger" onClick={() => setCancelling(true)}>
            Huỷ chuyến (ngoại lệ)
          </Button>
          <span className="text-sm text-curb">
            Chỉ dùng khi khách và tài xế không tự xử lý được; có ghi nhật ký.
          </span>
        </div>
      )}

      <CodeWarnings detail={detail} onUnlocked={setNotice} />

      <div className="flex flex-col gap-6 xl:block xl:columns-2 xl:gap-6 xl:[&>*]:mb-6 xl:[&>*]:break-inside-avoid">
        <Card title="Người tham gia">
          <Rows>
            <Row label="Khách">
              <span className="tabular-nums">{trip.customerId}</span>
            </Row>
            <Row label="Tài xế">{trip.driverId ? driverLink(trip.driverId) : 'Chưa có'}</Row>
          </Rows>
        </Card>

        <Card title="Lộ trình và giá">
          <Rows>
            <Row label="Điểm đón">{trip.pickup.address}</Row>
            <Row label="Điểm đến">{trip.dropoff.address}</Row>
            {trip.fare && (
              <>
                <Row label="Giá đã chốt">
                  <Money amount={trip.fare.quotedFare} className="font-semibold" />
                  {trip.fare.surgeMultiplier !== 1 && (
                    <span className="text-curb"> · hệ số cao điểm ×{trip.fare.surgeMultiplier}</span>
                  )}
                </Row>
                <Row label="Quãng đường">
                  {formatDistance(trip.fare.distanceMeters)}, khoảng{' '}
                  {formatDuration(trip.fare.durationSeconds)}
                </Row>
                <Row label="Bảng giá">phiên bản {trip.fare.pricingRuleVersion}</Row>
              </>
            )}
            {trip.acceptedAt && <Row label="Tài xế nhận">{formatDateTime(trip.acceptedAt)}</Row>}
            {trip.completedAt && <Row label="Hoàn tất">{formatDateTime(trip.completedAt)}</Row>}
            {trip.status === 'COMPLETED' && (
              <Row label="Nơi hoàn tất">
                {!detail.completion ? (
                  <span className="text-curb">Không rõ: lúc đó chưa có vị trí của tài xế</span>
                ) : detail.completion.awayFromDropoff ? (
                  <span className="font-semibold text-stop">
                    Cách điểm đến {formatDistance(detail.completion.distanceToDropoffMeters)}: dừng sớm hoặc
                    hoàn tất trước khi tới, nên kiểm tra
                  </span>
                ) : (
                  <span>Tại điểm đến (cách {formatDistance(detail.completion.distanceToDropoffMeters)})</span>
                )}
              </Row>
            )}
            {trip.cancelledAt && (
              <Row label="Huỷ">
                {formatDateTime(trip.cancelledAt)} bởi {ACTOR_NAMES[trip.cancelledBy ?? 'SYSTEM']}
                {trip.cancelReason && `: ${CANCEL_REASON_NAMES[trip.cancelReason]}`}
                {detail.cancelNote && <div className="text-ink-2">Ghi chú: {detail.cancelNote}</div>}
              </Row>
            )}
          </Rows>
        </Card>

        {trip.delivery && (
          <Card title="Giao hàng">
            <Rows>
              <Row label="Người nhận">
                {trip.delivery.recipientName} · {trip.delivery.recipientPhone}
              </Row>
              <Row label="Hàng">
                {trip.delivery.packageDescription} ({PACKAGE_SIZES[trip.delivery.packageSize]},{' '}
                {(trip.delivery.packageWeightGrams / 1000).toLocaleString('vi-VN')} kg)
              </Row>
              {trip.delivery.instructions && <Row label="Dặn dò">{trip.delivery.instructions}</Row>}
              <Row label="Bằng chứng giao">
                {detail.deliveryProof
                  ? `${detail.deliveryProof.method === 'DELIVERY_CODE' ? 'Mã giao hàng' : detail.deliveryProof.method}, xác nhận ${formatDateTime(detail.deliveryProof.verifiedAt)}`
                  : 'Chưa có'}
              </Row>
            </Rows>
          </Card>
        )}

        {canUse('payments', roles) && <TripPayments tripId={trip.id} />}

        <Card title="Diễn biến">
          <ol className="m-0 flex list-none flex-col p-0">
            {detail.history.map((change, index) => (
              <li
                key={`${change.occurredAt}-${index}`}
                className="flex flex-wrap justify-between gap-3 border-t border-line py-2 text-[15px] first:border-t-0 first:pt-0"
              >
                <span>
                  <span className="font-semibold">{STATUS_NAMES[change.toStatus]}</span>
                  <span className="text-curb"> · bởi {ACTOR_NAMES[change.actorType]}</span>
                  {change.reason && <span className="text-curb"> · {changeReason(change.reason)}</span>}
                </span>
                <time dateTime={change.occurredAt} className="text-sm text-curb tabular-nums">
                  {formatTime(change.occurredAt)}
                </time>
              </li>
            ))}
          </ol>
        </Card>

        <Card title="Offer đã gửi">
          {detail.offers.length === 0 ? (
            <p className="m-0 text-[15px] text-curb">Chưa gửi offer cho tài xế nào.</p>
          ) : (
            <table className="w-full border-collapse text-left text-[15px]">
              <thead className="text-sm text-curb">
                <tr>
                  <th className="py-1.5 pr-4 font-semibold">Gửi lúc</th>
                  <th className="py-1.5 pr-4 font-semibold">Tài xế</th>
                  <th className="py-1.5 pr-4 font-semibold">Cách điểm đón</th>
                  <th className="py-1.5 font-semibold">Kết quả</th>
                </tr>
              </thead>
              <tbody>
                {detail.offers.map((offer) => (
                  <tr key={offer.id} className="border-t border-line">
                    <td className="py-2 pr-4 tabular-nums">{formatTime(offer.createdAt)}</td>
                    <td className="py-2 pr-4 text-sm">{driverLink(offer.driverId)}</td>
                    <td className="py-2 pr-4">{formatDistance(offer.pickupDistanceMeters)}</td>
                    <td className="py-2">
                      {OFFER_STATUS_NAMES[offer.status]}
                      {offer.respondedAt && (
                        <span className="text-curb"> · {formatTime(offer.respondedAt)}</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
      </div>

      {cancelling && (
        <CancelDialog
          tripId={trip.id}
          onClose={() => setCancelling(false)}
          onDone={() => {
            setCancelling(false);
            setNotice('Đã huỷ chuyến. Khách và tài xế được báo ngay qua ứng dụng.');
          }}
        />
      )}
    </>
  );
}

function TripPayments({ tripId }: { tripId: string }) {
  const payments = useTripPayments(tripId);
  return (
    <Card title="Thanh toán">
      {payments.isPending && (
        <div aria-hidden="true" className="h-12 animate-pulse rounded-lg bg-concrete-2" />
      )}
      {payments.isError && <p role="alert">{messageFor(payments.error)}</p>}
      {payments.data?.length === 0 && <p className="m-0 text-[15px] text-curb">Chưa có khoản thu nào.</p>}
      {payments.data && payments.data.length > 0 && (
        <ul className="m-0 flex list-none flex-col p-0">
          {payments.data.map((payment) => (
            <li
              key={payment.id}
              className="flex flex-wrap items-center justify-between gap-3 border-t border-line py-2 text-[15px] first:border-t-0 first:pt-0"
            >
              <Link to={`/admin/payments/${payment.id}`} className="font-semibold">
                {PURPOSE_NAMES[payment.purpose]} · <Money amount={payment.amount} />
              </Link>
              <PaymentStatusBadge status={payment.status} />
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

function CodeWarnings({
  detail,
  onUnlocked,
}: {
  detail: StaffTripDetail;
  onUnlocked: (notice: string) => void;
}) {
  const [unlocking, setUnlocking] = useState<HandoverCode | null>(null);
  const max = detail.codeMaxAttempts;
  const lines = (
    [
      { code: 'PICKUP', failures: detail.pickupCodeFailures },
      { code: 'DELIVERY', failures: detail.deliveryCodeFailures },
    ] as const
  ).filter((line) => line.failures > 0);
  return (
    <>
      {lines.length > 0 && (
        <section className="flex flex-col gap-2 rounded-xl bg-stop-tint px-5 py-4 text-[15px]">
          {lines.map(({ code, failures }) => {
            const locked = failures >= max;
            const canUnlock = locked && CODE_IN_USE[code].has(detail.trip.status);
            return (
              <div key={code} className="flex flex-wrap items-center justify-between gap-3">
                <p className="m-0">
                  <strong className="text-stop">{CODE_NAMES[code]}</strong>: tài xế nhập sai {failures} lần
                  {!locked
                    ? ` (khoá sau ${max} lần).`
                    : canUnlock
                      ? ', mã đã bị khoá. Xác minh với khách rồi mở khoá, hoặc huỷ ngoại lệ.'
                      : ', mã đã bị khoá.'}
                </p>
                {canUnlock && (
                  <Button size="compact" onClick={() => setUnlocking(code)}>
                    Mở khoá {CODE_NAMES[code].toLowerCase()}
                  </Button>
                )}
              </div>
            );
          })}
        </section>
      )}
      {detail.codeUnlocks.length > 0 && (
        <section className="flex flex-col gap-1 rounded-xl bg-white px-5 py-4 text-[15px]">
          <h2 className="m-0 text-base font-bold">Đã mở khoá mã</h2>
          <ul className="m-0 flex list-none flex-col gap-1 p-0">
            {detail.codeUnlocks.map((unlock) => (
              <li key={unlock.createdAt + unlock.code}>
                <span className="font-semibold">{CODE_NAMES[unlock.code]}</span> ·{' '}
                {formatDateTime(unlock.createdAt)} · sau {unlock.failuresBefore} lần sai · nhân viên{' '}
                <span className="tabular-nums">{unlock.staffId}</span>
                <span className="block text-curb">{unlock.note}</span>
              </li>
            ))}
          </ul>
        </section>
      )}
      {unlocking && (
        <UnlockDialog
          tripId={detail.trip.id}
          code={unlocking}
          onClose={() => setUnlocking(null)}
          onDone={() => {
            setUnlocking(null);
            onUnlocked(`Đã mở khoá ${CODE_NAMES[unlocking].toLowerCase()}. Tài xế nhập lại được mã cũ.`);
          }}
        />
      )}
    </>
  );
}

function UnlockDialog({
  tripId,
  code,
  onClose,
  onDone,
}: {
  tripId: string;
  code: HandoverCode;
  onClose: () => void;
  onDone: () => void;
}) {
  const unlock = useCodeUnlock(tripId);
  return (
    <ReasonDialog
      title={`Mở khoá ${CODE_NAMES[code].toLowerCase()}`}
      confirmLabel="Mở khoá"
      minLength={MIN_CANCEL_NOTE}
      maxLength={300}
      busy={unlock.isPending}
      error={unlock.error ? messageFor(unlock.error) : undefined}
      onConfirm={(note) => unlock.mutate({ code, note }, { onSuccess: onDone })}
      onClose={onClose}
    >
      <p className="m-0 text-[15px]">
        Mã giữ nguyên, tài xế được nhập lại từ đầu. Ghi rõ đã xác minh thế nào (ví dụ gọi khách xác nhận mã);
        lần mở khoá được ghi nhật ký.
      </p>
    </ReasonDialog>
  );
}

function CancelDialog({
  tripId,
  onClose,
  onDone,
}: {
  tripId: string;
  onClose: () => void;
  onDone: () => void;
}) {
  const cancel = useStaffCancel(tripId);
  const [reason, setReason] = useState<CancelReason>('OTHER');
  const selectId = useId();
  return (
    <ReasonDialog
      title="Huỷ chuyến (ngoại lệ)"
      confirmLabel="Huỷ chuyến"
      danger
      minLength={MIN_CANCEL_NOTE}
      maxLength={300}
      busy={cancel.isPending}
      error={cancel.error ? messageFor(cancel.error) : undefined}
      onConfirm={(note) => cancel.mutate({ reason, note }, { onSuccess: onDone })}
      onClose={onClose}
    >
      <div className="flex flex-col gap-1">
        <label htmlFor={selectId} className="text-sm font-semibold">
          Nguyên nhân
        </label>
        <select
          id={selectId}
          className={FIELD}
          value={reason}
          onChange={(event) => setReason(event.target.value as CancelReason)}
        >
          {(Object.keys(CANCEL_REASON_NAMES) as CancelReason[]).map((r) => (
            <option key={r} value={r}>
              {CANCEL_REASON_NAMES[r]}
            </option>
          ))}
        </select>
      </div>
    </ReasonDialog>
  );
}

function Rows({ children }: { children: ReactNode }) {
  return <dl className="m-0 grid grid-cols-[max-content_1fr] gap-x-8 gap-y-2 text-[15px]">{children}</dl>;
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <>
      <dt className="text-curb">{label}</dt>
      <dd className="m-0">{children}</dd>
    </>
  );
}
