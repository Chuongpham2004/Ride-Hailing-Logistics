import { useId, useState, type ReactNode } from 'react';
import { Link, useParams } from 'react-router';
import { ApiError } from '@/shared/api/ApiError';
import { messageFor } from '@/shared/api/messages';
import type { CancelReason, StaffTripDetail } from '@/shared/api/types';
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
import { MIN_CANCEL_NOTE, useStaffCancel, useStaffTrip } from './trips';

/** Wrong codes allowed before a code locks (trip-service rhl.trip.codes.max-attempts). */
const CODE_LOCK_AFTER = 5;

const PACKAGE_SIZES = { SMALL: 'Nhỏ', MEDIUM: 'Vừa', LARGE: 'Lớn' } as const;

/** /admin/trips/:tripId: the whole trip for support, and the exceptional cancellation. */
export default function TripDetailPage() {
  const { tripId = '' } = useParams();
  const detail = useStaffTrip(tripId);

  return (
    <div className="flex max-w-5xl flex-col gap-6 px-10 py-8">
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

      <CodeWarnings detail={detail} />

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
                {formatDistance(trip.fare.distanceMeters)}, khoảng {formatDuration(trip.fare.durationSeconds)}
              </Row>
              <Row label="Bảng giá">phiên bản {trip.fare.pricingRuleVersion}</Row>
            </>
          )}
          {trip.acceptedAt && <Row label="Tài xế nhận">{formatDateTime(trip.acceptedAt)}</Row>}
          {trip.completedAt && <Row label="Hoàn tất">{formatDateTime(trip.completedAt)}</Row>}
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

function CodeWarnings({ detail }: { detail: StaffTripDetail }) {
  const lines = [
    { label: 'Mã đón', failures: detail.pickupCodeFailures },
    { label: 'Mã giao', failures: detail.deliveryCodeFailures },
  ].filter((line) => line.failures > 0);
  if (lines.length === 0) return null;
  return (
    <section className="rounded-xl bg-stop-tint px-5 py-4 text-[15px]">
      {lines.map(({ label, failures }) => (
        <p key={label} className="m-0">
          <strong className="text-stop">{label}</strong>: tài xế nhập sai {failures} lần
          {failures >= CODE_LOCK_AFTER
            ? ', mã đã bị khoá. Chuyến không đi tiếp được; cân nhắc huỷ ngoại lệ.'
            : ` (khoá sau ${CODE_LOCK_AFTER} lần).`}
        </p>
      ))}
    </section>
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
