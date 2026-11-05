import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { ApiError } from '@/shared/api/ApiError';
import { messageFor } from '@/shared/api/messages';
import type { DriverDocument, DriverProfile, DriverReview, ReviewDecision } from '@/shared/api/types';
import { useSession } from '@/shared/auth/session';
import { DOCUMENT_NAMES, requirementText, SERVICE_NAMES, VEHICLE_NAMES } from '@/shared/drivers/text';
import { formatDate, formatDateTime } from '@/shared/lib/format';
import { Button } from '@/shared/ui/Button';
import { BackIcon } from '@/shared/ui/icons';
import { ReviewStatusBadge } from '@/shared/ui/StatusBadge';
import { DocumentViewer } from './DocumentViewer';
import { useDriverReview, useReviewAction, type ReviewAction, type Verdict } from './drivers';
import { Card } from './Card';
import { ReasonDialog } from './ReasonDialog';
import { canUse } from './sections';
import { DECISION_NAMES, shortId } from './text';

/** /admin/drivers/:driverId: the whole profile, its papers and decisions, and the reviewer's actions. */
export default function DriverReviewPage() {
  const { driverId = '' } = useParams();
  const review = useDriverReview(driverId);

  return (
    <div className="flex max-w-5xl flex-col gap-6 px-10 py-8">
      <Link to="/admin/drivers" className="flex items-center gap-1 self-start text-[15px] font-semibold">
        <BackIcon width={18} height={18} /> Hàng chờ duyệt
      </Link>
      {review.isPending && <div aria-hidden="true" className="h-64 animate-pulse rounded-xl bg-white" />}
      {review.isError && (
        <p role="alert" className="m-0 text-[15px]">
          {review.error instanceof ApiError && review.error.status === 404
            ? 'Không tìm thấy hồ sơ tài xế này.'
            : messageFor(review.error)}
        </p>
      )}
      {review.data && <Review review={review.data} />}
    </div>
  );
}

type Pending = { kind: 'decide'; verdict: Verdict } | { kind: 'suspend' } | { kind: 'reinstate' } | null;

const DIALOGS = {
  APPROVE: { title: 'Duyệt hồ sơ', confirm: 'Duyệt', danger: false, minLength: 0 },
  REQUEST_CHANGES: { title: 'Yêu cầu bổ sung', confirm: 'Gửi yêu cầu', danger: false, minLength: 1 },
  REJECT: { title: 'Từ chối hồ sơ', confirm: 'Từ chối', danger: true, minLength: 1 },
  suspend: { title: 'Tạm khoá tài xế', confirm: 'Tạm khoá', danger: true, minLength: 1 },
  reinstate: { title: 'Mở lại cho tài xế', confirm: 'Mở lại', danger: false, minLength: 1 },
};

function Review({ review }: { review: DriverReview }) {
  const { profile, decisions } = review;
  const user = useSession((state) => state.user);
  const action = useReviewAction(profile.driverId);
  const [pending, setPending] = useState<Pending>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const own = user?.id === profile.driverId;
  const blocked = profile.missingRequirements.length > 0;

  const open = (next: Pending) => {
    action.reset();
    setNotice(null);
    setPending(next);
  };

  const confirm = (reason: string) => {
    if (!pending) return;
    const request: ReviewAction =
      pending.kind === 'decide'
        ? { kind: 'decide', verdict: pending.verdict, profileVersion: profile.profileVersion, reason }
        : { kind: pending.kind, reason };
    action.mutate(request, {
      onSuccess: (decision) => {
        setPending(null);
        setNotice(`Đã ghi nhận: ${DECISION_NAMES[decision.decision]}.`);
      },
      onError: (error) => {
        // The profile changed under the reviewer (driver edit or another reviewer): it has been
        // reloaded, so close the dialog and ask for a second look.
        if (error instanceof ApiError && (error.code === 'INVALID_STATE' || error.status === 409)) {
          setPending(null);
          setNotice(
            'Hồ sơ vừa thay đổi nên quyết định chưa được ghi. Đã tải lại, xem lại trước khi quyết định.',
          );
        }
      },
    });
  };

  const dialog = pending ? DIALOGS[pending.kind === 'decide' ? pending.verdict : pending.kind] : null;

  return (
    <>
      <header className="flex flex-col gap-2">
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="m-0 text-[28px] leading-tight font-bold">{profile.fullName}</h1>
          <ReviewStatusBadge status={profile.reviewStatus} />
        </div>
        <p className="m-0 text-[15px] text-curb">
          Phiên bản hồ sơ {profile.profileVersion}
          {profile.submittedAt && ` · gửi duyệt ${formatDateTime(profile.submittedAt)}`} ·{' '}
          <span title={profile.driverId}>ID {shortId(profile.driverId)}</span>
        </p>
        {profile.statusReason && (
          <p className="m-0 text-[15px]">
            <span className="text-curb">Lý do hiện tại:</span> {profile.statusReason}
          </p>
        )}
      </header>

      {notice && (
        <p
          role="status"
          className="m-0 rounded-lg bg-white px-4 py-3 text-[15px] shadow-[inset_4px_0_0_var(--color-guide)]"
        >
          {notice}
        </p>
      )}

      {own ? (
        <p className="m-0 text-[15px] text-curb">Đây là hồ sơ của chính bạn nên bạn không quyết định được.</p>
      ) : (
        <Actions profile={profile} blocked={blocked} onOpen={open} />
      )}

      {profile.missingRequirements.length > 0 && (
        <section className="rounded-xl bg-stop-tint px-5 py-4">
          <h2 className="m-0 text-base font-bold text-stop">Còn thiếu</h2>
          <ul className="m-0 mt-1 pl-5 text-[15px]">
            {profile.missingRequirements.map((text) => (
              <li key={text}>{requirementText(text)}</li>
            ))}
          </ul>
        </section>
      )}

      <Card title="Thông tin">
        <dl className="m-0 grid grid-cols-[max-content_1fr] gap-x-8 gap-y-2 text-[15px]">
          <dt className="text-curb">Loại chuyến</dt>
          <dd className="m-0">{profile.serviceTypes.map((s) => SERVICE_NAMES[s]).join(', ')}</dd>
          <dt className="text-curb">Ngày sinh</dt>
          <dd className="m-0">{profile.dateOfBirth ? formatDate(profile.dateOfBirth) : '—'}</dd>
          <dt className="text-curb">Đang</dt>
          <dd className="m-0">{AVAILABILITY[profile.availability]}</dd>
        </dl>
        <div className="flex flex-wrap gap-5 text-[15px] font-semibold">
          {canUse('trips', user?.roles ?? []) && (
            <Link to={`/admin/trips?driverId=${profile.driverId}`}>Xem chuyến của tài xế</Link>
          )}
          {canUse('wallets', user?.roles ?? []) && (
            <Link to={`/admin/wallets/${profile.driverId}`}>Ví tài xế</Link>
          )}
          {canUse('users', user?.roles ?? []) && (
            <Link to={`/admin/users/${profile.driverId}`}>Tài khoản đăng nhập</Link>
          )}
        </div>
      </Card>

      <Vehicles profile={profile} />
      <Documents profile={profile} />
      <Decisions decisions={decisions} />

      {pending && dialog && (
        <ReasonDialog
          title={dialog.title}
          confirmLabel={dialog.confirm}
          danger={dialog.danger}
          minLength={dialog.minLength}
          busy={action.isPending}
          error={action.error ? messageFor(action.error) : undefined}
          onConfirm={confirm}
          onClose={() => setPending(null)}
        >
          {pending.kind === 'decide' && (
            <p className="m-0 text-[15px] text-ink-2">
              Quyết định cho phiên bản {profile.profileVersion} của hồ sơ {profile.fullName}.
            </p>
          )}
          {pending.kind === 'suspend' && (
            <p className="m-0 text-[15px] text-ink-2">
              Tài xế đang rảnh sẽ bị chuyển offline ngay; đang chạy chuyến thì được chạy nốt chuyến đó.
            </p>
          )}
        </ReasonDialog>
      )}
    </>
  );
}

const AVAILABILITY: Record<DriverProfile['availability'], string> = {
  OFFLINE: 'Offline',
  AVAILABLE: 'Online, đang rảnh',
  OFFERED: 'Online, đang có offer',
  BUSY: 'Đang chạy chuyến',
};

function Actions({
  profile,
  blocked,
  onOpen,
}: {
  profile: DriverProfile;
  blocked: boolean;
  onOpen: (pending: Pending) => void;
}) {
  if (profile.reviewStatus === 'PENDING_REVIEW') {
    return (
      <div className="flex flex-wrap items-center gap-2">
        <Button
          size="compact"
          disabled={blocked}
          onClick={() => onOpen({ kind: 'decide', verdict: 'APPROVE' })}
        >
          Duyệt
        </Button>
        <Button
          size="compact"
          variant="secondary"
          onClick={() => onOpen({ kind: 'decide', verdict: 'REQUEST_CHANGES' })}
        >
          Yêu cầu bổ sung
        </Button>
        <Button
          size="compact"
          variant="secondary"
          className="text-stop"
          onClick={() => onOpen({ kind: 'decide', verdict: 'REJECT' })}
        >
          Từ chối
        </Button>
        {blocked && <span className="text-sm text-curb">Chưa duyệt được khi hồ sơ còn thiếu.</span>}
      </div>
    );
  }
  if (profile.reviewStatus === 'APPROVED') {
    return (
      <div className="flex gap-2">
        <Button size="compact" variant="danger" onClick={() => onOpen({ kind: 'suspend' })}>
          Tạm khoá
        </Button>
      </div>
    );
  }
  if (profile.reviewStatus === 'SUSPENDED') {
    return (
      <div className="flex gap-2">
        <Button size="compact" variant="dark" onClick={() => onOpen({ kind: 'reinstate' })}>
          Mở lại
        </Button>
      </div>
    );
  }
  return (
    <p className="m-0 text-[15px] text-curb">
      Hồ sơ đang ở tài xế ({profile.reviewStatus === 'DRAFT' ? 'chưa gửi hoặc đang bổ sung' : 'đã bị từ chối'}
      ); chờ tài xế gửi lại để duyệt.
    </p>
  );
}

function Vehicles({ profile }: { profile: DriverProfile }) {
  return (
    <Card title="Xe">
      {profile.vehicles.length === 0 ? (
        <p className="m-0 text-[15px] text-curb">Chưa có xe.</p>
      ) : (
        <table className="w-full border-collapse text-left text-[15px]">
          <thead className="text-sm text-curb">
            <tr>
              <th className="py-1.5 pr-4 font-semibold">Biển số</th>
              <th className="py-1.5 pr-4 font-semibold">Loại</th>
              <th className="py-1.5 pr-4 font-semibold">Xe</th>
              <th className="py-1.5 font-semibold">Trạng thái</th>
            </tr>
          </thead>
          <tbody>
            {profile.vehicles.map((v) => (
              <tr key={v.id} className="border-t border-line">
                <td className="py-2 pr-4 font-plate text-lg tracking-wide">{v.plateNumber}</td>
                <td className="py-2 pr-4">{VEHICLE_NAMES[v.type]}</td>
                <td className="py-2 pr-4">
                  {v.brand} {v.model}, {v.color} ({v.manufactureYear})
                </td>
                <td className="py-2">
                  {v.status === 'ACTIVE' ? 'Đang dùng được' : 'Đã gỡ'}
                  {v.id === profile.activeVehicleId && <span className="text-go"> · đang chạy</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Card>
  );
}

function Documents({ profile }: { profile: DriverProfile }) {
  const [viewing, setViewing] = useState<DriverDocument | null>(null);
  const today = new Date().toISOString().slice(0, 10);
  const plate = (id?: string) => profile.vehicles.find((v) => v.id === id)?.plateNumber;
  // Current papers first; replaced ones stay listed for the record.
  const documents = [...profile.documents].sort(
    (a, b) => Number(a.status !== 'ACTIVE') - Number(b.status !== 'ACTIVE'),
  );

  return (
    <Card title="Giấy tờ">
      {documents.length === 0 ? (
        <p className="m-0 text-[15px] text-curb">Chưa có giấy tờ.</p>
      ) : (
        <table className="w-full border-collapse text-left text-[15px]">
          <thead className="text-sm text-curb">
            <tr>
              <th className="py-1.5 pr-4 font-semibold">Loại</th>
              <th className="py-1.5 pr-4 font-semibold">Số</th>
              <th className="py-1.5 pr-4 font-semibold">Cấp ngày</th>
              <th className="py-1.5 pr-4 font-semibold">Hết hạn</th>
              <th className="py-1.5 font-semibold">Ảnh</th>
            </tr>
          </thead>
          <tbody>
            {documents.map((d) => {
              const expired = d.expiresOn !== undefined && d.expiresOn <= today;
              return (
                <tr key={d.id} className={`border-t border-line ${d.status === 'ACTIVE' ? '' : 'text-curb'}`}>
                  <td className="py-2 pr-4">
                    {DOCUMENT_NAMES[d.type]}
                    {plate(d.vehicleId) && <span className="text-curb"> · {plate(d.vehicleId)}</span>}
                    {d.status !== 'ACTIVE' && <span> (đã thay)</span>}
                  </td>
                  <td className="py-2 pr-4 tabular-nums">{d.documentNumber}</td>
                  <td className="py-2 pr-4 tabular-nums">{d.issuedOn ? formatDate(d.issuedOn) : '—'}</td>
                  <td className={`py-2 pr-4 tabular-nums ${expired ? 'font-semibold text-stop' : ''}`}>
                    {d.expiresOn ? formatDate(d.expiresOn) : '—'}
                    {expired && ' (hết hạn)'}
                  </td>
                  <td className="py-2">
                    {d.hasFile ? (
                      <button
                        type="button"
                        onClick={() => setViewing(d)}
                        className="font-semibold text-guide underline-offset-2 hover:underline"
                      >
                        Xem
                      </button>
                    ) : (
                      <span className="text-curb">Không có</span>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      {viewing && (
        <DocumentViewer
          driverId={profile.driverId}
          document={viewing}
          title={`${DOCUMENT_NAMES[viewing.type]} · ${viewing.documentNumber}`}
          onClose={() => setViewing(null)}
        />
      )}
    </Card>
  );
}

function Decisions({ decisions }: { decisions: ReviewDecision[] }) {
  const newestFirst = [...decisions].reverse();
  return (
    <Card title="Lịch sử quyết định">
      {newestFirst.length === 0 ? (
        <p className="m-0 text-[15px] text-curb">Chưa có quyết định nào.</p>
      ) : (
        <ol className="m-0 flex list-none flex-col p-0">
          {newestFirst.map((d) => (
            <li
              key={d.id}
              className="flex flex-col gap-0.5 border-t border-line py-2.5 first:border-t-0 first:pt-0"
            >
              <span className="flex flex-wrap justify-between gap-3 text-[15px]">
                <span className="font-semibold">
                  {DECISION_NAMES[d.decision]}{' '}
                  <span className="font-normal text-curb">· phiên bản {d.profileVersion}</span>
                </span>
                <time dateTime={d.decidedAt} className="text-sm text-curb tabular-nums">
                  {formatDateTime(d.decidedAt)}
                </time>
              </span>
              {d.reason && <span className="text-[15px]">{d.reason}</span>}
              <span className="text-sm text-curb" title={d.reviewerId}>
                Người quyết định {shortId(d.reviewerId)}
              </span>
            </li>
          ))}
        </ol>
      )}
    </Card>
  );
}
