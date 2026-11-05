import { useState, type ReactNode } from 'react';
import { Navigate, useNavigate } from 'react-router';
import { ApiError } from '@/shared/api/ApiError';
import { messageFor } from '@/shared/api/messages';
import type { DriverProfile, ServiceType } from '@/shared/api/types';
import { useSession } from '@/shared/auth/session';
import { Button } from '@/shared/ui/Button';
import { ContactStep } from './ContactStep';
import { DocumentsStep } from './DocumentsStep';
import { EDITABLE, FIELD_CLASS, requirementText, useSaveProfile, useSubmitForReview } from './onboarding';
import { SERVICE_NAMES, useDriverProfile } from './profile';
import { SignOutButton } from './SignOutButton';
import { VehicleStep } from './VehicleStep';

type StepId = 'profile' | 'vehicle' | 'documents' | 'contact' | 'submit';

/**
 * /driver/onboarding: the steps of UC-01 on one page, each ticked off as the server confirms it.
 * Open while the profile is missing, DRAFT or REJECTED; otherwise the review page takes over.
 */
export default function OnboardingPage() {
  const { data: profile, isPending, error, refetch } = useDriverProfile();
  const user = useSession((state) => state.user);
  const missingProfile = error instanceof ApiError && error.status === 404;

  if (isPending) return <Loading />;
  if (error && !missingProfile) {
    return (
      <Frame>
        <p role="alert" className="m-0 text-[15px]">
          {messageFor(error)}
        </p>
        <Button variant="secondary" onClick={() => void refetch()}>
          Thử lại
        </Button>
      </Frame>
    );
  }
  if (profile && !EDITABLE.has(profile.reviewStatus)) return <Navigate to="/driver/review" replace />;

  return (
    <Steps profile={profile ?? null} contactVerified={Boolean(user?.emailVerified || user?.phoneVerified)} />
  );
}

function Steps({ profile, contactVerified }: { profile: DriverProfile | null; contactVerified: boolean }) {
  const missing = profile?.missingRequirements ?? [];
  const hasVehicle = (profile?.vehicles ?? []).some((v) => v.status === 'ACTIVE');
  const papersMissing = missing.some((m) => / (is missing|has expired)$/.test(m));
  const done: Record<StepId, boolean> = {
    profile: profile !== null,
    vehicle: hasVehicle,
    documents: profile !== null && hasVehicle && !papersMissing,
    contact: contactVerified,
    submit: false,
  };
  const firstOpen = (['profile', 'vehicle', 'documents', 'contact', 'submit'] as const).find(
    (s) => !done[s],
  )!;
  const [open, setOpen] = useState<StepId | null>(firstOpen);
  const [lastFirst, setLastFirst] = useState(firstOpen);
  // A step was just completed: move on to the next one still to do.
  if (firstOpen !== lastFirst) {
    setLastFirst(firstOpen);
    setOpen(firstOpen);
  }
  const toggle = (id: StepId) => setOpen((current) => (current === id ? null : id));

  return (
    <Frame>
      <header className="flex flex-col gap-2">
        <h1 className="m-0 text-[28px] leading-tight font-bold">Hồ sơ tài xế</h1>
        <p className="m-0 text-ink-2">
          Hoàn thành các bước dưới đây rồi gửi duyệt. Ảnh giấy tờ có thể bổ sung sau.
        </p>
        {profile?.statusReason && (
          <p className="m-0 rounded-[10px] bg-stop-tint px-3.5 py-3 text-[15px]">
            {profile.reviewStatus === 'REJECTED' ? 'Hồ sơ bị từ chối' : 'Người duyệt yêu cầu sửa'}:{' '}
            {profile.statusReason}
          </p>
        )}
      </header>

      <ol className="m-0 flex list-none flex-col gap-3 p-0">
        <Step
          n={1}
          title="Thông tin cá nhân"
          done={done.profile}
          open={open === 'profile'}
          onToggle={() => toggle('profile')}
        >
          <ProfileStep profile={profile} />
        </Step>
        <Step
          n={2}
          title="Xe"
          done={done.vehicle}
          open={open === 'vehicle'}
          onToggle={() => toggle('vehicle')}
          locked={!profile}
        >
          {profile && <VehicleStep profile={profile} />}
        </Step>
        <Step
          n={3}
          title="Giấy tờ"
          done={done.documents}
          open={open === 'documents'}
          onToggle={() => toggle('documents')}
          locked={!profile}
        >
          {profile && <DocumentsStep profile={profile} />}
        </Step>
        <Step
          n={4}
          title="Xác minh email hoặc số điện thoại"
          done={done.contact}
          open={open === 'contact'}
          onToggle={() => toggle('contact')}
        >
          <ContactStep />
        </Step>
        <Step
          n={5}
          title="Gửi duyệt"
          done={false}
          open={open === 'submit'}
          onToggle={() => toggle('submit')}
          locked={!profile}
        >
          {profile && <SubmitStep profile={profile} />}
        </Step>
      </ol>
      <SignOutButton />
    </Frame>
  );
}

function Frame({ children }: { children: ReactNode }) {
  return <div className="flex h-full flex-col gap-5 overflow-y-auto px-5 py-8">{children}</div>;
}

function Loading() {
  return (
    <Frame>
      <div aria-hidden="true" className="flex flex-col gap-3">
        {[0, 1, 2, 3].map((i) => (
          <div key={i} className="h-16 animate-pulse rounded-xl bg-concrete-2" />
        ))}
      </div>
    </Frame>
  );
}

function Step({
  n,
  title,
  done,
  open,
  locked = false,
  onToggle,
  children,
}: {
  n: number;
  title: string;
  done: boolean;
  open: boolean;
  locked?: boolean;
  onToggle: () => void;
  children: ReactNode;
}) {
  const panelId = `step-${n}`;
  return (
    <li className={`rounded-xl border ${open ? 'border-asphalt' : 'border-line'}`}>
      <h2 className="m-0 text-base">
        <button
          type="button"
          onClick={onToggle}
          disabled={locked}
          aria-expanded={open}
          aria-controls={panelId}
          className="flex min-h-14 w-full items-center gap-3 px-4 text-left disabled:opacity-50"
        >
          <span
            aria-hidden="true"
            className={`flex size-7 shrink-0 items-center justify-center rounded-full text-sm font-bold ${
              done ? 'bg-go text-white' : 'bg-concrete-2 text-asphalt'
            }`}
          >
            {done ? '✓' : n}
          </span>
          <span className="flex-1 font-semibold">
            <span className="sr-only">Bước {n}: </span>
            {title}
          </span>
          <span className="text-sm text-curb">{done ? 'Xong' : locked ? 'Làm bước 1 trước' : ''}</span>
        </button>
      </h2>
      {open && !locked && (
        <div id={panelId} className="flex flex-col gap-3 border-t border-dashed border-line px-4 pt-3 pb-4">
          {children}
        </div>
      )}
    </li>
  );
}

const SERVICES: ServiceType[] = ['RIDE', 'DELIVERY'];

function ProfileStep({ profile }: { profile: DriverProfile | null }) {
  const user = useSession((state) => state.user);
  const save = useSaveProfile(profile !== null);
  const [fullName, setFullName] = useState(profile?.fullName ?? user?.fullName ?? '');
  const [dateOfBirth, setDateOfBirth] = useState(profile?.dateOfBirth ?? '');
  const [services, setServices] = useState<ServiceType[]>(profile?.serviceTypes ?? ['RIDE']);
  const [problem, setProblem] = useState<string | null>(null);

  const onSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    if (!fullName.trim()) return setProblem('Nhập họ tên như trên CCCD.');
    if (services.length === 0) return setProblem('Chọn ít nhất một loại chuyến.');
    if (dateOfBirth && !isAdult(dateOfBirth)) return setProblem('Tài xế phải từ đủ 18 tuổi.');
    setProblem(null);
    save.mutate({ fullName: fullName.trim(), dateOfBirth: dateOfBirth || undefined, serviceTypes: services });
  };

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-3">
      <label className="flex flex-col gap-1 text-sm text-curb">
        Họ và tên (như trên CCCD)
        <input
          className={FIELD_CLASS}
          value={fullName}
          maxLength={120}
          onChange={(e) => setFullName(e.target.value)}
        />
      </label>
      <label className="flex flex-col gap-1 text-sm text-curb">
        Ngày sinh
        <input
          type="date"
          className={FIELD_CLASS}
          value={dateOfBirth}
          onChange={(e) => setDateOfBirth(e.target.value)}
        />
      </label>
      <fieldset className="m-0 flex flex-col gap-1 border-0 p-0">
        <legend className="mb-1 p-0 text-sm text-curb">Nhận loại chuyến</legend>
        {SERVICES.map((service) => (
          <label key={service} className="flex items-center gap-3 py-1 text-[15px]">
            <input
              type="checkbox"
              checked={services.includes(service)}
              onChange={() =>
                setServices((current) =>
                  current.includes(service) ? current.filter((s) => s !== service) : [...current, service],
                )
              }
              className="size-5 accent-asphalt"
            />
            {SERVICE_NAMES[service]}
          </label>
        ))}
      </fieldset>
      {(problem || save.isError) && (
        <p role="alert" className="m-0 text-sm text-stop">
          {problem ?? messageFor(save.error)}
        </p>
      )}
      {save.isSuccess && !save.isPending && (
        <p role="status" className="m-0 text-sm text-go">
          Đã lưu.
        </p>
      )}
      <Button type="submit" busy={save.isPending}>
        {profile ? 'Lưu thay đổi' : 'Tạo hồ sơ'}
      </Button>
    </form>
  );
}

/** At least 18 on the date given (yyyy-mm-dd), as user-service checks. */
function isAdult(isoDate: string, today = new Date()): boolean {
  const [y, m, d] = isoDate.split('-').map(Number);
  const eighteenth = new Date(y! + 18, (m ?? 1) - 1, d ?? 1);
  return eighteenth <= today;
}

function SubmitStep({ profile }: { profile: DriverProfile }) {
  const navigate = useNavigate();
  const submit = useSubmitForReview();
  const missing = profile.missingRequirements;

  return (
    <div className="flex flex-col gap-3">
      {missing.length > 0 ? (
        <>
          <p className="m-0 text-[15px]">Còn thiếu trước khi gửi duyệt:</p>
          <ul className="m-0 flex flex-col gap-1 pl-5 text-[15px]">
            {missing.map((item) => (
              <li key={item}>{requirementText(item)}</li>
            ))}
          </ul>
        </>
      ) : (
        <p className="m-0 text-[15px]">
          Hồ sơ đã đủ. Sau khi gửi, bạn không sửa được cho tới khi có kết quả duyệt.
        </p>
      )}
      {submit.isError && (
        <p role="alert" className="m-0 text-sm text-stop">
          {messageFor(submit.error)}
        </p>
      )}
      <Button
        busy={submit.isPending}
        disabled={missing.length > 0}
        onClick={() =>
          submit.mutate(undefined, { onSuccess: () => navigate('/driver/review', { replace: true }) })
        }
      >
        Gửi duyệt
      </Button>
    </div>
  );
}
