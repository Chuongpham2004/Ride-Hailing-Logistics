import { useState } from 'react';
import { useNavigate } from 'react-router';
import { logout, useSession } from '@/shared/auth/session';
import { Button } from './Button';

/** Driver and staff areas until their stages land (frontend-design.md §11). */
export function AreaPlaceholder({
  title,
  body,
  wide = false,
}: {
  title: string;
  body: string;
  wide?: boolean;
}) {
  const user = useSession((state) => state.user);
  const navigate = useNavigate();
  const [leaving, setLeaving] = useState(false);

  const signOut = async () => {
    setLeaving(true);
    await logout();
    navigate('/login', { replace: true });
  };

  return (
    <div
      className={`mx-auto flex min-h-dvh flex-col justify-center gap-4 px-6 ${wide ? 'max-w-3xl' : 'max-w-[480px]'}`}
    >
      <p className="m-0 text-curb">{user?.fullName}</p>
      <h1 className="m-0 text-[28px] leading-tight font-bold">{title}</h1>
      <p className="m-0 max-w-[60ch] text-ink-2">{body}</p>
      <div className="max-w-xs">
        <Button variant="secondary" onClick={signOut} busy={leaving}>
          Đăng xuất
        </Button>
      </div>
    </div>
  );
}
