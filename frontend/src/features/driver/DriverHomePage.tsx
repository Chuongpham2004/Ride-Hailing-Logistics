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
import { requirementText } from './onboarding';
import {
  SERVICE_NAMES,
  useActiveTrip,
  useDriverProfile,
  useGoOffline,
  useGoOnline,
  VEHICLE_NAMES,
  VEHICLE_SERVES,
} from './profile';

/**
 * /driver: the map, the vehicle and services to work with, and the big Online / Offline switch.
 * Behind RequireApproved, so the profile is an approved one.
 */
export default function DriverHomePage() {
  const { data: profile } = useDriverProfile();
  const navigate = useNavigate();
  const { data: activeTrip } = useActiveTrip(profile !== undefined);
  const [sheetRef, sheetHeight] = useHeight<HTMLDivElement>();
  const online = profile !== undefined && profile.availability !== 'OFFLINE';
  const { driver, onPick } = useMapPosition(online);

  // A trip in progress always wins over the home screen.
  useEffect(() => {
    if (activeTrip) navigate(`/driver/trips/${activeTrip.id}`, { replace: true });
  }, [activeTrip, navigate]);

  return (
    <div className="absolute inset-0">
      <MapView className="absolute inset-0" driver={driver} onPick={onPick} bottomInset={sheetHeight} />
      <LocationPanel online={online} className="absolute top-14 right-4 left-4" />

      <div ref={sheetRef} className="absolute inset-x-0 bottom-0 max-h-[80%] overflow-y-auto">
        <Sheet>{profile && <WorkPanel profile={profile} />}</Sheet>
      </div>
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
                  <li key={item}>{requirementText(item)}</li>
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
