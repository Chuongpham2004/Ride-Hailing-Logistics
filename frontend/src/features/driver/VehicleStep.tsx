import { useState } from 'react';
import { ApiError } from '@/shared/api/ApiError';
import { messageFor } from '@/shared/api/messages';
import type { DriverProfile, VehicleType } from '@/shared/api/types';
import { Button } from '@/shared/ui/Button';
import { FIELD_CLASS, normalisePlate, PLATE, useAddVehicle, useRemoveVehicle } from './onboarding';
import { SERVICE_NAMES, VEHICLE_NAMES, VEHICLE_SERVES } from './profile';

const TYPES: VehicleType[] = ['MOTORBIKE', 'CAR_4_SEAT', 'CAR_7_SEAT', 'VAN'];
const THIS_YEAR = new Date().getFullYear();

/** Step 2: the vehicles the driver works with; each needs its own registration and insurance. */
export function VehicleStep({ profile }: { profile: DriverProfile }) {
  const active = profile.vehicles.filter((v) => v.status === 'ACTIVE');
  const [adding, setAdding] = useState(active.length === 0);
  const remove = useRemoveVehicle();

  return (
    <div className="flex flex-col gap-3">
      {active.length > 0 && (
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
          {active.map((v) => (
            <li key={v.id} className="flex items-center gap-3 rounded-[10px] bg-concrete-2 px-3.5 py-2.5">
              <span className="flex-1 text-[15px]">
                <strong className="font-plate text-lg tracking-wide">{v.plateNumber}</strong> ·{' '}
                {VEHICLE_NAMES[v.type]}, {v.brand} {v.model}, {v.color} ({v.manufactureYear})
              </span>
              <button
                type="button"
                onClick={() => remove.mutate(v.id)}
                disabled={remove.isPending}
                aria-label={`Bỏ xe ${v.plateNumber}`}
                className="min-h-11 rounded-lg px-2 text-sm font-semibold text-stop"
              >
                Bỏ xe
              </button>
            </li>
          ))}
        </ul>
      )}
      {remove.isError && (
        <p role="alert" className="m-0 text-sm text-stop">
          {messageFor(remove.error)}
        </p>
      )}
      {adding ? (
        <AddVehicle profile={profile} onDone={() => setAdding(false)} canCancel={active.length > 0} />
      ) : (
        <Button variant="secondary" onClick={() => setAdding(true)}>
          Thêm xe
        </Button>
      )}
    </div>
  );
}

function AddVehicle({
  profile,
  onDone,
  canCancel,
}: {
  profile: DriverProfile;
  onDone: () => void;
  canCancel: boolean;
}) {
  const add = useAddVehicle();
  const [type, setType] = useState<VehicleType>('MOTORBIKE');
  const [plate, setPlate] = useState('');
  const [brand, setBrand] = useState('');
  const [model, setModel] = useState('');
  const [color, setColor] = useState('');
  const [year, setYear] = useState(String(THIS_YEAR));
  const [problem, setProblem] = useState<string | null>(null);
  // A vehicle that serves none of the profile's services could never go online.
  const useless = !VEHICLE_SERVES[type].some((s) => profile.serviceTypes.includes(s));

  const onSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    const plateNumber = normalisePlate(plate);
    const manufactureYear = Number(year);
    if (!PLATE.test(plateNumber)) return setProblem('Biển số chưa đúng (ví dụ 59X1-234.56 hoặc 51F12345).');
    if (!brand.trim() || !model.trim() || !color.trim()) return setProblem('Nhập đủ hãng, dòng xe và màu.');
    if (!Number.isInteger(manufactureYear) || manufactureYear < 1980 || manufactureYear > THIS_YEAR + 1) {
      return setProblem('Năm sản xuất chưa đúng.');
    }
    setProblem(null);
    add.mutate(
      { type, plateNumber, brand: brand.trim(), model: model.trim(), color: color.trim(), manufactureYear },
      { onSuccess: onDone },
    );
  };

  const serverProblem =
    add.error instanceof ApiError && add.error.code === 'CONFLICT'
      ? 'Biển số này đã được đăng ký.'
      : add.error instanceof ApiError && add.error.code === 'BUSINESS_RULE_VIOLATION'
        ? 'Biển số chưa đúng.'
        : add.isError
          ? messageFor(add.error)
          : null;

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-3">
      <label className="flex flex-col gap-1 text-sm text-curb">
        Loại xe
        <select className={FIELD_CLASS} value={type} onChange={(e) => setType(e.target.value as VehicleType)}>
          {TYPES.map((t) => (
            <option key={t} value={t}>
              {VEHICLE_NAMES[t]} ({VEHICLE_SERVES[t].map((s) => SERVICE_NAMES[s].toLowerCase()).join(', ')})
            </option>
          ))}
        </select>
      </label>
      {useless && (
        <p className="m-0 text-sm text-stop">
          Loại xe này không chạy được loại chuyến nào trong hồ sơ của bạn.
        </p>
      )}
      <label className="flex flex-col gap-1 text-sm text-curb">
        Biển số
        <input
          className={`${FIELD_CLASS} font-plate text-lg tracking-wide uppercase`}
          value={plate}
          maxLength={16}
          autoCapitalize="characters"
          onChange={(e) => setPlate(e.target.value)}
        />
      </label>
      <div className="grid grid-cols-2 gap-3">
        <label className="flex flex-col gap-1 text-sm text-curb">
          Hãng
          <input
            className={FIELD_CLASS}
            value={brand}
            maxLength={60}
            onChange={(e) => setBrand(e.target.value)}
          />
        </label>
        <label className="flex flex-col gap-1 text-sm text-curb">
          Dòng xe
          <input
            className={FIELD_CLASS}
            value={model}
            maxLength={60}
            onChange={(e) => setModel(e.target.value)}
          />
        </label>
        <label className="flex flex-col gap-1 text-sm text-curb">
          Màu
          <input
            className={FIELD_CLASS}
            value={color}
            maxLength={30}
            onChange={(e) => setColor(e.target.value)}
          />
        </label>
        <label className="flex flex-col gap-1 text-sm text-curb">
          Năm sản xuất
          <input
            className={FIELD_CLASS}
            inputMode="numeric"
            value={year}
            maxLength={4}
            onChange={(e) => setYear(e.target.value.replace(/\D/g, ''))}
          />
        </label>
      </div>
      {(problem || serverProblem) && (
        <p role="alert" className="m-0 text-sm text-stop">
          {problem ?? serverProblem}
        </p>
      )}
      <Button type="submit" busy={add.isPending} disabled={useless}>
        Lưu xe
      </Button>
      {canCancel && (
        <Button variant="secondary" onClick={onDone} disabled={add.isPending}>
          Huỷ
        </Button>
      )}
    </form>
  );
}
