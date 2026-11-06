import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { logout, useSession } from '@/shared/auth/session';
import { Button } from '@/shared/ui/Button';

/** /app/account: read-only for now, the API has no profile update yet (frontend-design.md §10). */
export default function AccountPage() {
  const user = useSession((state) => state.user);
  const navigate = useNavigate();
  const [leaving, setLeaving] = useState(false);

  const signOut = async () => {
    setLeaving(true);
    await logout();
    navigate('/login', { replace: true });
  };

  return (
    <div className="flex h-full flex-col gap-6 overflow-y-auto px-5 py-8 lg:page-column lg:py-12">
      <h1 className="m-0 text-[28px] leading-tight font-bold">{user?.fullName ?? 'Tài khoản'}</h1>
      <dl className="m-0 flex flex-col text-[15px]">
        <ContactRow label="Email" channel="email" value={user?.email} verified={user?.emailVerified} />
        <ContactRow
          label="Số điện thoại"
          channel="phone"
          value={user?.phone}
          verified={user?.phoneVerified}
        />
      </dl>
      <Button variant="secondary" onClick={signOut} busy={leaving} className="mt-auto">
        Đăng xuất
      </Button>
    </div>
  );
}

function ContactRow({
  label,
  channel,
  value,
  verified,
}: {
  label: string;
  channel: 'email' | 'phone';
  value?: string;
  verified?: boolean;
}) {
  return (
    <div className="flex flex-col gap-0.5 border-t border-dashed border-line py-3">
      <dt className="text-sm text-curb">{label}</dt>
      <dd className="m-0 flex flex-wrap items-baseline justify-between gap-2">
        <span className="font-semibold">{value ?? 'Chưa có'}</span>
        {value &&
          (verified ? (
            <span className="text-sm text-go">Đã xác minh</span>
          ) : (
            <Link to={`/verify/${channel}?next=/app/account`} className="text-sm font-semibold">
              Xác minh
            </Link>
          ))}
      </dd>
    </div>
  );
}
