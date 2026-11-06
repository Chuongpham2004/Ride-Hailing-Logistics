import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router';
import { z } from 'zod/mini';
import { messageFor } from '@/shared/api/messages';
import { areaHome, areaOfPath, areasFor, canEnter, LOGIN_PATH, type Area } from '@/shared/auth/areas';
import { login, logout, useSession } from '@/shared/auth/session';
import { Button } from '@/shared/ui/Button';
import { RevealBackdrop } from '@/shared/ui/RevealBackdrop';
import { Splash } from '@/shared/ui/Splash';

const schema = z.object({
  identifier: z.string().check(z.trim(), z.minLength(1, 'Nhập email hoặc số điện thoại.'), z.maxLength(254)),
  password: z.string().check(z.minLength(1, 'Nhập mật khẩu.'), z.maxLength(128)),
});

type Form = z.infer<typeof schema>;

interface Portal {
  title: string;
  intro: string;
  /** Who this portal is for, in "this is not a … account". */
  accountNoun: string;
  register?: { prompt: string; label: string; to: string };
  /** Header tone: the customer and driver portals are light, the staff one dark. */
  dark: boolean;
}

/** One sign-in page per area (frontend-design.md §4.1); each only lets its own accounts in. */
const PORTALS: Record<Area, Portal> = {
  customer: {
    title: 'Đăng nhập',
    intro: 'Đặt xe, gửi hàng và theo dõi chuyến đi trong thành phố.',
    accountNoun: 'khách hàng',
    register: { prompt: 'Chưa có tài khoản?', label: 'Đăng ký', to: '/register' },
    dark: false,
  },
  driver: {
    title: 'Đăng nhập tài xế',
    intro: 'Bật online, nhận chuyến và theo dõi thu nhập.',
    accountNoun: 'tài xế',
    register: { prompt: 'Muốn chạy xe cùng RHL?', label: 'Đăng ký tài xế', to: '/register?role=DRIVER' },
    dark: false,
  },
  admin: {
    title: 'Đăng nhập nhân viên',
    intro: 'Dành cho nhân viên duyệt hồ sơ, hỗ trợ, tài chính và quản trị.',
    accountNoun: 'nhân viên',
    dark: true,
  },
};

const OTHER_PORTALS: Record<Area, { label: string; to: string }[]> = {
  customer: [{ label: 'Bạn là tài xế?', to: LOGIN_PATH.driver }],
  driver: [{ label: 'Bạn là khách hàng?', to: LOGIN_PATH.customer }],
  admin: [],
};

/** Pages every signed-in user may open, whichever door they came in through. */
const SHARED_PATHS = ['/verify/'];

/**
 * Only same-site paths of this area (or shared pages), so a crafted ?next= cannot send people
 * elsewhere or into an area this portal is not for.
 */
function safeNext(next: string | null, area: Area): string | null {
  if (!next || !next.startsWith('/') || next.startsWith('//')) return null;
  if (SHARED_PATHS.some((path) => next.startsWith(path))) return next;
  return areaOfPath(next) === area ? next : null;
}

interface WrongPortal {
  noun: string;
  to: string;
  name: string;
}

export function LoginPage({ area = 'customer' }: { area?: Area }) {
  const portal = PORTALS[area];
  const { status, user } = useSession();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [failure, setFailure] = useState<string | null>(null);
  const [wrongPortal, setWrongPortal] = useState<WrongPortal | null>(null);
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<Form>({
    resolver: zodResolver(schema),
    // Back from the password reset: the address is already known.
    defaultValues: { identifier: params.get('identifier') ?? '', password: '' },
  });
  const afterReset = params.get('reset') === '1';

  if (status === 'loading') return <Splash />;
  // Already signed in with an account for this portal: straight in.
  if (user && canEnter(area, user.roles)) {
    return <Navigate to={safeNext(params.get('next'), area) ?? areaHome(area)} replace />;
  }

  const onSubmit = handleSubmit(async ({ identifier, password }) => {
    setFailure(null);
    setWrongPortal(null);
    try {
      const signedIn = await login(identifier, password);
      if (!canEnter(area, signedIn.roles)) {
        // Signed in at the wrong door: sign out again and point to the right one.
        const own = areasFor(signedIn.roles)[0];
        await logout();
        setWrongPortal(
          own
            ? { noun: portal.accountNoun, to: LOGIN_PATH[own], name: PORTALS[own].title.toLowerCase() }
            : { noun: portal.accountNoun, to: LOGIN_PATH.customer, name: 'đăng nhập' },
        );
        return;
      }
      navigate(safeNext(params.get('next'), area) ?? areaHome(area), { replace: true });
    } catch (error) {
      setFailure(messageFor(error));
    }
  });

  return (
    <div
      className={`flex min-h-dvh items-center justify-center px-4 py-10 ${portal.dark ? 'bg-asphalt' : 'bg-concrete'}`}
    >
      {!portal.dark && <RevealBackdrop />}
      <div className="relative flex w-full max-w-[400px] flex-col gap-7 rounded-sheet bg-white px-6 py-8 shadow-[0_2px_12px_rgba(43,46,51,0.1)]">
        <header className="flex flex-col gap-3">
          <span
            aria-hidden="true"
            className={`flex size-11 items-center justify-center rounded-full ${portal.dark ? 'bg-sign' : 'bg-asphalt'}`}
          >
            <span className={`size-7 rounded-full ${portal.dark ? 'bg-asphalt' : 'bg-sign'}`} />
          </span>
          <h1 className="m-0 text-[28px] leading-tight font-bold">{portal.title}</h1>
          <p className="m-0 text-ink-2">{portal.intro}</p>
        </header>

        {afterReset && (
          <p role="status" className="m-0 rounded-[10px] bg-concrete-2 px-3.5 py-3 text-[15px]">
            Đã đặt mật khẩu mới. Đăng nhập bằng mật khẩu đó.
          </p>
        )}
        <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
          <label className="flex flex-col gap-1 text-sm text-curb">
            Email hoặc số điện thoại
            <input
              type="text"
              autoComplete="username"
              inputMode="email"
              aria-invalid={errors.identifier ? true : undefined}
              aria-describedby={errors.identifier ? 'identifier-error' : undefined}
              className="h-12 rounded-[10px] border-[1.5px] border-field px-3.5 text-base text-asphalt aria-invalid:border-stop"
              {...register('identifier')}
            />
            {errors.identifier && (
              <span id="identifier-error" className="text-sm text-stop">
                {errors.identifier.message}
              </span>
            )}
          </label>

          <label className="flex flex-col gap-1 text-sm text-curb">
            Mật khẩu
            <input
              type="password"
              autoComplete="current-password"
              aria-invalid={errors.password ? true : undefined}
              aria-describedby={errors.password ? 'password-error' : undefined}
              className="h-12 rounded-[10px] border-[1.5px] border-field px-3.5 text-base text-asphalt aria-invalid:border-stop"
              {...register('password')}
            />
            {errors.password && (
              <span id="password-error" className="text-sm text-stop">
                {errors.password.message}
              </span>
            )}
          </label>

          {failure && (
            <p role="alert" className="m-0 rounded-[10px] bg-stop-tint px-3.5 py-3 text-[15px] text-asphalt">
              {failure}
            </p>
          )}
          {wrongPortal && (
            <p role="alert" className="m-0 rounded-[10px] bg-stop-tint px-3.5 py-3 text-[15px] text-asphalt">
              Đây không phải tài khoản {wrongPortal.noun}. Dùng trang{' '}
              <Link to={wrongPortal.to}>{wrongPortal.name}</Link>.
            </p>
          )}

          <Button type="submit" variant={portal.dark ? 'dark' : 'primary'} busy={isSubmitting}>
            Đăng nhập
          </Button>
          <Link to={`/forgot-password?portal=${area}`} className="self-center text-[15px] font-semibold">
            Quên mật khẩu?
          </Link>
        </form>
        <div className="flex flex-col gap-1 text-center text-[15px] text-ink-2">
          {portal.register && (
            <p className="m-0">
              {portal.register.prompt} <Link to={portal.register.to}>{portal.register.label}</Link>
            </p>
          )}
          {OTHER_PORTALS[area].map((other) => (
            <p key={other.to} className="m-0">
              {other.label} <Link to={other.to}>Đăng nhập tại đây</Link>
            </p>
          ))}
        </div>
      </div>
    </div>
  );
}

export default function CustomerLoginPage() {
  return <LoginPage area="customer" />;
}
