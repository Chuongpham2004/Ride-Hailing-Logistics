import { Link, Navigate, useLocation, useParams, useSearchParams } from 'react-router';
import { areaOfPath, homeFor, LOGIN_PATH } from '@/shared/auth/areas';
import type { Channel } from '@/shared/auth/contacts';
import { ContactVerification } from '@/shared/auth/ContactVerification';
import { useSession } from '@/shared/auth/session';
import { RevealBackdrop } from '@/shared/ui/RevealBackdrop';
import { Splash } from '@/shared/ui/Splash';

const LABELS: Record<Channel, { label: string; noun: string }> = {
  email: { label: 'Email', noun: 'email' },
  phone: { label: 'Số điện thoại', noun: 'số điện thoại' },
};

/** Only same-site paths, so a crafted ?next= cannot send people elsewhere. */
function safeNext(next: string | null): string | null {
  return next && next.startsWith('/') && !next.startsWith('//') ? next : null;
}

/**
 * /verify/:channel?next=: the signed-in user verifies their email or phone with a one-time code
 * (frontend-design.md §4.1), from any area. Done, it offers the way back to where they came from.
 */
export default function VerifyPage() {
  const { channel: raw } = useParams();
  const [params] = useSearchParams();
  const location = useLocation();
  const { status, user } = useSession();

  if (status === 'loading') return <Splash />;
  if (!user) {
    const next = encodeURIComponent(location.pathname + location.search);
    // Sign in at the door of the area the user came from.
    return <Navigate to={`${LOGIN_PATH[areaOfPath(params.get('next'))]}?next=${next}`} replace />;
  }

  const back = safeNext(params.get('next')) ?? homeFor(user.roles);
  const channel = raw === 'email' || raw === 'phone' ? raw : null;
  const value = channel === 'email' ? user.email : channel === 'phone' ? user.phone : undefined;
  const verified = channel === 'email' ? user.emailVerified : user.phoneVerified;

  return (
    <div className="flex min-h-dvh items-center justify-center bg-concrete px-4 py-10">
      <RevealBackdrop />
      <div className="relative flex w-full max-w-[400px] flex-col gap-6 rounded-sheet bg-white px-6 py-8 shadow-[0_2px_12px_rgba(43,46,51,0.1)]">
        <header className="flex flex-col gap-2">
          <h1 className="m-0 text-[28px] leading-tight font-bold">
            {channel ? `Xác minh ${LABELS[channel].noun}` : 'Xác minh liên hệ'}
          </h1>
          <p className="m-0 text-ink-2">
            Liên hệ đã xác minh dùng để nhận mã khi quên mật khẩu, và là điều kiện để tài xế gửi hồ sơ.
          </p>
        </header>

        {!channel && <p role="alert">Không có mục xác minh này.</p>}
        {channel && !value && (
          <p role="alert" className="m-0 text-[15px]">
            Tài khoản chưa có {LABELS[channel].noun} nên chưa xác minh được.
          </p>
        )}
        {channel && value && (
          <ContactVerification
            channel={channel}
            label={LABELS[channel].label}
            value={value}
            verified={verified}
          />
        )}
        {channel && value && verified && (
          <p role="status" className="m-0 text-[15px] text-go">
            {LABELS[channel].label} đã được xác minh.
          </p>
        )}

        <Link
          to={back}
          className={`flex min-h-13 items-center justify-center rounded-xl text-[17px] font-bold no-underline ${
            verified ? 'bg-sign text-asphalt' : 'border-[1.5px] border-field text-asphalt'
          }`}
        >
          {verified ? 'Tiếp tục' : 'Để sau'}
        </Link>
      </div>
    </div>
  );
}
