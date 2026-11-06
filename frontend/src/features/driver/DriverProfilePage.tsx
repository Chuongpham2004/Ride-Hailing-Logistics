import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { messageFor } from '@/shared/api/messages';
import type { DriverDocument, DriverProfile } from '@/shared/api/types';
import { useSession } from '@/shared/auth/session';
import { formatDate } from '@/shared/lib/format';
import { Button } from '@/shared/ui/Button';
import { DOCUMENT_NAMES, todayIso } from './onboarding';
import { SERVICE_NAMES, useDriverProfile, VEHICLE_NAMES } from './profile';
import { SignOutButton } from './SignOutButton';

/** Warn this many days before a document runs out: an expired one blocks going online. */
const EXPIRY_WARNING_DAYS = 30;

/** /driver/profile: read-only view of the approved profile, vehicles and papers. */
export default function DriverProfilePage() {
  const { data: profile, isPending, error, refetch } = useDriverProfile();
  const user = useSession((state) => state.user);

  return (
    <div className="flex h-full flex-col gap-5 overflow-y-auto px-5 py-8 lg:page-column lg:py-12">
      <h1 className="m-0 text-[28px] leading-tight font-bold">
        {profile?.fullName ?? user?.fullName ?? 'Hồ sơ'}
      </h1>
      {isPending && <div aria-hidden="true" className="h-40 animate-pulse rounded-xl bg-concrete-2" />}
      {error && (
        <div className="flex flex-col gap-3">
          <p role="alert" className="m-0 text-[15px]">
            {messageFor(error)}
          </p>
          <Button variant="secondary" onClick={() => void refetch()}>
            Thử lại
          </Button>
        </div>
      )}
      {profile && <Details profile={profile} />}
      <Link to="/driver/activity" className="font-semibold">
        Lịch sử chuyến
      </Link>
      <SignOutButton className="mt-auto" />
    </div>
  );
}

function Details({ profile }: { profile: DriverProfile }) {
  const user = useSession((state) => state.user);
  const vehicles = profile.vehicles.filter((v) => v.status === 'ACTIVE');
  const documents = profile.documents.filter((d) => d.status === 'ACTIVE');
  const plate = (id?: string) => profile.vehicles.find((v) => v.id === id)?.plateNumber;

  return (
    <>
      <dl className="m-0 flex flex-col text-[15px]">
        <Row label="Loại chuyến" value={profile.serviceTypes.map((s) => SERVICE_NAMES[s]).join(', ')} />
        {profile.dateOfBirth && <Row label="Ngày sinh" value={formatDate(profile.dateOfBirth)} />}
        {user?.email && (
          <Row
            label="Email"
            value={user.email}
            note={<VerifiedNote channel="email" verified={user.emailVerified} />}
          />
        )}
        {user?.phone && (
          <Row
            label="Số điện thoại"
            value={user.phone}
            note={<VerifiedNote channel="phone" verified={user.phoneVerified} />}
          />
        )}
      </dl>

      <section className="flex flex-col gap-2">
        <h2 className="m-0 text-lg font-bold">Xe</h2>
        {vehicles.length === 0 && <p className="m-0 text-[15px] text-curb">Chưa có xe nào đang dùng.</p>}
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
          {vehicles.map((v) => (
            <li key={v.id} className="rounded-[10px] bg-concrete-2 px-3.5 py-2.5 text-[15px]">
              <strong className="font-plate text-lg tracking-wide">{v.plateNumber}</strong> ·{' '}
              {VEHICLE_NAMES[v.type]}, {v.brand} {v.model}, {v.color} ({v.manufactureYear})
              {v.id === profile.activeVehicleId && <span className="text-go"> · đang dùng</span>}
            </li>
          ))}
        </ul>
      </section>

      <section className="flex flex-col gap-2">
        <h2 className="m-0 text-lg font-bold">Giấy tờ</h2>
        <ul className="m-0 flex list-none flex-col p-0">
          {documents.map((d) => (
            <li key={d.id}>
              <DocumentRow document={d} plate={plate(d.vehicleId)} />
            </li>
          ))}
        </ul>
        {profile.reviewStatus === 'APPROVED' && (
          <p className="m-0 text-sm text-curb">
            Hồ sơ đã duyệt không tự sửa được; cần thay giấy tờ thì liên hệ tổng đài.
          </p>
        )}
      </section>
    </>
  );
}

function VerifiedNote({ channel, verified }: { channel: 'email' | 'phone'; verified: boolean }) {
  return verified ? (
    <>Đã xác minh</>
  ) : (
    <Link to={`/verify/${channel}?next=/driver/profile`} className="font-semibold">
      Xác minh
    </Link>
  );
}

function Row({ label, value, note }: { label: string; value: string; note?: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5 border-t border-dashed border-line py-3">
      <dt className="text-sm text-curb">{label}</dt>
      <dd className="m-0 flex flex-wrap items-baseline justify-between gap-2">
        <span className="font-semibold">{value}</span>
        {note && <span className="text-sm text-curb">{note}</span>}
      </dd>
    </div>
  );
}

function daysUntil(isoDate: string, today: string): number {
  return Math.round((Date.parse(isoDate) - Date.parse(today)) / 86_400_000);
}

function DocumentRow({ document, plate }: { document: DriverDocument; plate?: string }) {
  const today = todayIso();
  const left = document.expiresOn ? daysUntil(document.expiresOn, today) : null;
  const expiry =
    left === null
      ? null
      : left <= 0
        ? { text: `Đã hết hạn ${formatDate(document.expiresOn!)}`, tone: 'text-stop font-semibold' }
        : left <= EXPIRY_WARNING_DAYS
          ? { text: `Còn ${left} ngày (hết hạn ${formatDate(document.expiresOn!)})`, tone: 'text-stop' }
          : { text: `Hết hạn ${formatDate(document.expiresOn!)}`, tone: 'text-curb' };
  return (
    <div className="flex flex-col gap-0.5 border-t border-dashed border-line py-3 text-[15px]">
      <span className="font-semibold">
        {DOCUMENT_NAMES[document.type]}
        {plate && <span className="font-normal text-curb"> · {plate}</span>}
      </span>
      <span className="text-sm text-curb">
        Số {document.documentNumber}
        {document.hasFile ? ' · có ảnh' : ''}
      </span>
      {expiry && <span className={`text-sm ${expiry.tone}`}>{expiry.text}</span>}
    </div>
  );
}
