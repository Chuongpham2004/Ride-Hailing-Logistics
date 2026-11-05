import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { ApiError } from '@/shared/api/ApiError';
import { messageFor } from '@/shared/api/messages';
import type { DriverProfile, ServiceType } from '@/shared/api/types';
import { useSession } from '@/shared/auth/session';
import { useHeight } from '@/shared/lib/useHeight';
import { MapView } from '@/shared/map/MapView';
import { Button } from '@/shared/ui/Button';
import { Sheet } from '@/shared/ui/Sheet';
import { LocationPanel } from './LocationPanel';
import { useMapPosition } from './location';
import {
  SERVICE_NAMES,
  useActiveTrip,
  useDriverProfile,
  useGoOffline,
  useGoOnline,
  VEHICLE_NAMES,
  VEHICLE_SERVES,
} from './profile';

/** /driver: the map, the vehicle and services to work with, and the big Online / Offline switch. */
export default function DriverHomePage() {
  const { data: profile, isPending, error, refetch } = useDriverProfile();
  const approved = profile?.reviewStatus === 'APPROVED';
  const navigate = useNavigate();
  const { data: activeTrip } = useActiveTrip(approved);
  const [sheetRef, sheetHeight] = useHeight<HTMLDivElement>();
  const online = profile !== undefined && profile.availability !== 'OFFLINE';
  const { driver, onPick } = useMapPosition(online);

  // A trip in progress always wins over the home screen.
  useEffect(() => {
    if (activeTrip) navigate(`/driver/trips/${activeTrip.id}`, { replace: true });
  }, [activeTrip, navigate]);

  return (
    <div className="absolute inset-0">
      <MapView
        className="absolute inset-0"
        driver={driver}
        onPick={approved ? onPick : undefined}
        bottomInset={sheetHeight}
      />
      {approved && <LocationPanel online={online} className="absolute top-14 right-4 left-4" />}

      <div ref={sheetRef} className="absolute inset-x-0 bottom-0 max-h-[80%] overflow-y-auto">
        <Sheet>
          {isPending && <div aria-hidden="true" className="h-40 animate-pulse rounded bg-concrete-2" />}
          {error && <ProfileProblem error={error} onRetry={() => void refetch()} />}
          {profile && !approved && <NotApproved profile={profile} />}
          {profile && approved && <WorkPanel profile={profile} />}
        </Sheet>
      </div>
    </div>
  );
}

function ProfileProblem({ error, onRetry }: { error: Error; onRetry: () => void }) {
  if (error instanceof ApiError && error.status === 404) {
    return (
      <div className="flex flex-col gap-2">
        <h1 className="m-0 text-xl font-bold">Bạn chưa có hồ sơ tài xế</h1>
        <p className="m-0 text-[15px] text-ink-2">
          Hoàn thiện hồ sơ trên web sẽ có ở bản cập nhật tới. Trong lúc chờ, tạo hồ sơ bằng trang test console
          (tools/test-console) hoặc bộ Postman.
        </p>
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-3">
      <p role="alert" className="m-0 text-[15px]">
        {messageFor(error)}
      </p>
      <Button variant="secondary" onClick={onRetry}>
        Thử lại
      </Button>
    </div>
  );
}

const REVIEW_TEXT: Record<
  Exclude<DriverProfile['reviewStatus'], 'APPROVED'>,
  { title: string; body: string }
> = {
  DRAFT: { title: 'Hồ sơ chưa gửi duyệt', body: 'Bổ sung các mục còn thiếu rồi gửi duyệt.' },
  PENDING_REVIEW: {
    title: 'Hồ sơ đang chờ duyệt',
    body: 'Bạn sẽ bật online được ngay khi hồ sơ được duyệt.',
  },
  REJECTED: { title: 'Hồ sơ bị từ chối', body: 'Sửa theo lý do bên dưới rồi gửi duyệt lại.' },
  SUSPENDED: { title: 'Tài khoản tài xế đang bị tạm khoá', body: 'Liên hệ tổng đài để biết thêm.' },
};

/** Until onboarding is on the web (next update), the driver only sees where the profile stands. */
function NotApproved({ profile }: { profile: DriverProfile }) {
  const text = REVIEW_TEXT[profile.reviewStatus as keyof typeof REVIEW_TEXT];
  return (
    <div className="flex flex-col gap-2">
      <h1 className="m-0 text-xl font-bold">{text.title}</h1>
      <p className="m-0 text-[15px] text-ink-2">{text.body}</p>
      {profile.statusReason && (
        <p className="m-0 rounded-[10px] bg-stop-tint px-3.5 py-3 text-[15px]">
          Lý do: {profile.statusReason}
        </p>
      )}
      {profile.missingRequirements.length > 0 && (
        <>
          <p className="m-0 text-sm text-curb">Máy chủ báo còn thiếu:</p>
          <ul className="m-0 pl-5 text-[15px]">
            {profile.missingRequirements.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
        </>
      )}
      <p className="m-0 text-sm text-curb">
        Hoàn thiện hồ sơ trên web sẽ có ở bản cập nhật tới; trong lúc chờ, dùng trang test console.
      </p>
    </div>
  );
}

const AVAILABILITY_TEXT: Record<DriverProfile['availability'], string> = {
  OFFLINE: 'Bạn đang offline',
  AVAILABLE: 'Đang online, chờ chuyến',
  OFFERED: 'Có chuyến mới đang chờ bạn trả lời',
  BUSY: 'Đang chạy chuyến',
};

function WorkPanel({ profile }: { profile: DriverProfile }) {
  const { user } = useSession();
  const vehicles = useMemo(() => profile.vehicles.filter((v) => v.status === 'ACTIVE'), [profile.vehicles]);
  const [vehicleId, setVehicleId] = useState(profile.activeVehicleId ?? vehicles[0]?.id ?? '');
  const vehicle = vehicles.find((v) => v.id === vehicleId) ?? vehicles[0];
  const offered = useMemo<ServiceType[]>(
    () => (vehicle ? profile.serviceTypes.filter((s) => VEHICLE_SERVES[vehicle.type].includes(s)) : []),
    [vehicle, profile.serviceTypes],
  );
  const [services, setServices] = useState<ServiceType[]>(offered);
  const chosen = services.filter((s) => offered.includes(s));
  const goOnline = useGoOnline();
  const goOffline = useGoOffline();
  const offline = profile.availability === 'OFFLINE';
  const working = profile.availability === 'OFFERED' || profile.availability === 'BUSY';

  const toggle = (service: ServiceType) =>
    setServices((current) =>
      current.includes(service) ? current.filter((s) => s !== service) : [...current, service],
    );

  const error = goOnline.error ?? goOffline.error;

  return (
    <div className="flex flex-col gap-3.5">
      <div className="flex items-center gap-3">
        <span
          aria-hidden="true"
          className={`size-3 rounded-full ${offline ? 'bg-curb' : working ? 'bg-sign' : 'bg-go'}`}
        />
        <h1 className="m-0 text-xl font-bold">{AVAILABILITY_TEXT[profile.availability]}</h1>
      </div>
      <p className="m-0 text-sm text-curb">Xin chào {profile.fullName || user?.fullName}.</p>

      {offline && vehicles.length === 0 && (
        <p className="m-0 text-[15px]">Bạn chưa có xe nào đang dùng được, nên chưa bật online được.</p>
      )}

      {offline && vehicles.length > 0 && (
        <>
          <fieldset className="m-0 flex flex-col gap-1 border-0 p-0">
            <legend className="mb-1 p-0 text-sm text-curb">Xe</legend>
            {vehicles.map((v) => (
              <label key={v.id} className="flex items-center gap-3 py-1 text-[15px]">
                <input
                  type="radio"
                  name="vehicle"
                  value={v.id}
                  checked={vehicle?.id === v.id}
                  onChange={() => setVehicleId(v.id)}
                  className="size-5 accent-asphalt"
                />
                <span>
                  <strong className="font-plate text-lg tracking-wide">{v.plateNumber}</strong> ·{' '}
                  {VEHICLE_NAMES[v.type]}, {v.brand} {v.model}
                </span>
              </label>
            ))}
          </fieldset>
          <fieldset className="m-0 flex flex-col gap-1 border-0 p-0">
            <legend className="mb-1 p-0 text-sm text-curb">Nhận loại chuyến</legend>
            {offered.length === 0 && (
              <p className="m-0 text-[15px]">Xe này không phục vụ dịch vụ nào trong hồ sơ.</p>
            )}
            {offered.map((service) => (
              <label key={service} className="flex items-center gap-3 py-1 text-[15px]">
                <input
                  type="checkbox"
                  checked={chosen.includes(service)}
                  onChange={() => toggle(service)}
                  className="size-5 accent-asphalt"
                />
                {SERVICE_NAMES[service]}
              </label>
            ))}
          </fieldset>
        </>
      )}

      {error && (
        <div role="alert" className="flex flex-col gap-1 text-sm text-stop">
          <p className="m-0">{messageFor(error)}</p>
          {error instanceof ApiError &&
            error.code === 'BUSINESS_RULE_VIOLATION' &&
            profile.missingRequirements.length > 0 && (
              <ul className="m-0 pl-5">
                {profile.missingRequirements.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            )}
        </div>
      )}

      {offline ? (
        <Button
          busy={goOnline.isPending}
          disabled={!vehicle || chosen.length === 0}
          onClick={() => vehicle && goOnline.mutate({ vehicleId: vehicle.id, serviceTypes: chosen })}
          className="min-h-15 text-xl"
        >
          Bật online
        </Button>
      ) : (
        <>
          <Button
            variant="dark"
            busy={goOffline.isPending}
            disabled={working}
            onClick={() => goOffline.mutate()}
            className="min-h-15 text-xl"
          >
            Tắt online
          </Button>
          {working && (
            <p className="m-0 text-sm text-curb">Xong offer hoặc chuyến đang chạy mới tắt online được.</p>
          )}
        </>
      )}
    </div>
  );
}
