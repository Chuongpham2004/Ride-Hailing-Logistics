import { Link, Navigate } from 'react-router';
import { ApiError } from '@/shared/api/ApiError';
import { messageFor } from '@/shared/api/messages';
import { formatDateTime } from '@/shared/lib/format';
import { Button } from '@/shared/ui/Button';
import { EDITABLE } from './onboarding';
import { useDriverProfile } from './profile';
import { SignOutButton } from './SignOutButton';

/** Polls while waiting: the decision is not pushed over the socket. */
const WAITING_REFRESH_MS = 30_000;

/** /driver/review: where the profile stands after it was submitted (frontend-design.md §4.3). */
export default function ReviewPage() {
  const { data: profile, isPending, error, refetch } = useDriverProfile(WAITING_REFRESH_MS);

  if (isPending) {
    return <div aria-hidden="true" className="m-5 h-40 animate-pulse rounded-xl bg-concrete-2" />;
  }
  if (error instanceof ApiError && error.status === 404) return <Navigate to="/driver/onboarding" replace />;
  if (error || !profile) {
    return (
      <div className="flex flex-col gap-3 px-5 py-8">
        <p role="alert" className="m-0 text-[15px]">
          {messageFor(error)}
        </p>
        <Button variant="secondary" onClick={() => void refetch()}>
          Thử lại
        </Button>
      </div>
    );
  }
  // Sent back for changes (DRAFT with a reason) is handled on the onboarding page.
  if (profile.reviewStatus === 'DRAFT') return <Navigate to="/driver/onboarding" replace />;

  return (
    <div className="flex h-full flex-col gap-5 overflow-y-auto px-5 py-8 lg:page-column lg:py-12">
      {profile.reviewStatus === 'PENDING_REVIEW' && (
        <Block tone="bg-sign" title="Hồ sơ đang chờ duyệt">
          <p className="m-0 text-[15px] text-ink-2">
            Người duyệt sẽ đối chiếu thông tin và giấy tờ của bạn. Trang này tự cập nhật khi có kết quả.
          </p>
          {profile.submittedAt && (
            <p className="m-0 text-sm text-curb">Gửi lúc {formatDateTime(profile.submittedAt)}</p>
          )}
        </Block>
      )}
      {profile.reviewStatus === 'REJECTED' && (
        <Block tone="bg-stop" title="Hồ sơ bị từ chối">
          {profile.statusReason && (
            <p className="m-0 rounded-[10px] bg-stop-tint px-3.5 py-3 text-[15px]">
              Lý do: {profile.statusReason}
            </p>
          )}
          <p className="m-0 text-[15px] text-ink-2">Sửa theo lý do trên rồi gửi duyệt lại.</p>
          <Link
            to="/driver/onboarding"
            className="flex min-h-13 items-center justify-center rounded-xl bg-sign text-[17px] font-bold text-asphalt no-underline"
          >
            Sửa hồ sơ
          </Link>
        </Block>
      )}
      {profile.reviewStatus === 'APPROVED' && (
        <Block tone="bg-go" title="Hồ sơ đã được duyệt">
          <p className="m-0 text-[15px] text-ink-2">Bạn đã có thể bật online và nhận chuyến.</p>
          <Link
            to="/driver"
            className="flex min-h-13 items-center justify-center rounded-xl bg-sign text-[17px] font-bold text-asphalt no-underline"
          >
            Bắt đầu làm việc
          </Link>
        </Block>
      )}
      {profile.reviewStatus === 'SUSPENDED' && (
        <Block tone="bg-stop" title="Tài khoản tài xế đang bị tạm khoá">
          {profile.statusReason && <p className="m-0 text-[15px]">Lý do: {profile.statusReason}</p>}
          <p className="m-0 text-[15px] text-ink-2">Liên hệ tổng đài để được hỗ trợ.</p>
        </Block>
      )}
      {!EDITABLE.has(profile.reviewStatus) && profile.reviewStatus !== 'APPROVED' && (
        <SignOutButton className="mt-auto" />
      )}
    </div>
  );
}

function Block({ tone, title, children }: { tone: string; title: string; children: React.ReactNode }) {
  return (
    <section className="flex flex-col gap-3" aria-live="polite">
      <div className="flex items-center gap-3">
        <span aria-hidden="true" className={`size-3 rounded-full ${tone}`} />
        <h1 className="m-0 text-[24px] leading-tight font-bold">{title}</h1>
      </div>
      {children}
    </section>
  );
}
