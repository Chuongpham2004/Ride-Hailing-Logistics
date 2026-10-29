import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Navigate, useNavigate, useSearchParams } from 'react-router';
import { z } from 'zod';
import { messageFor } from '@/shared/api/messages';
import { homeFor } from '@/shared/auth/areas';
import { login, useSession } from '@/shared/auth/session';
import { Button } from '@/shared/ui/Button';
import { RevealBackdrop } from '@/shared/ui/RevealBackdrop';
import { Splash } from '@/shared/ui/Splash';

const schema = z.object({
  identifier: z.string().trim().min(1, 'Nhập email hoặc số điện thoại.').max(254),
  password: z.string().min(1, 'Nhập mật khẩu.').max(128),
});

type Form = z.infer<typeof schema>;

/** Only same-site paths, so a crafted ?next= cannot send people elsewhere after signing in. */
function safeNext(next: string | null): string | null {
  return next && next.startsWith('/') && !next.startsWith('//') ? next : null;
}

export default function LoginPage() {
  const { status, user } = useSession();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [failure, setFailure] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<Form>({ resolver: zodResolver(schema) });

  if (status === 'loading') return <Splash />;
  if (user) return <Navigate to={safeNext(params.get('next')) ?? homeFor(user.roles)} replace />;

  const onSubmit = handleSubmit(async ({ identifier, password }) => {
    setFailure(null);
    try {
      const signedIn = await login(identifier, password);
      navigate(safeNext(params.get('next')) ?? homeFor(signedIn.roles), { replace: true });
    } catch (error) {
      setFailure(messageFor(error));
    }
  });

  return (
    <div className="flex min-h-dvh items-center justify-center bg-concrete px-4 py-10">
      <RevealBackdrop />
      <div className="relative flex w-full max-w-[400px] flex-col gap-7 rounded-sheet bg-white px-6 py-8 shadow-[0_2px_12px_rgba(43,46,51,0.1)]">
        <header className="flex flex-col gap-3">
          <span
            aria-hidden="true"
            className="flex size-11 items-center justify-center rounded-full bg-asphalt"
          >
            <span className="size-7 rounded-full bg-sign" />
          </span>
          <h1 className="m-0 text-[28px] leading-tight font-bold">Đăng nhập</h1>
          <p className="m-0 text-ink-2">Đặt xe, gửi hàng và theo dõi chuyến đi trong thành phố.</p>
        </header>

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

          <Button type="submit" busy={isSubmitting}>
            Đăng nhập
          </Button>
        </form>
      </div>
    </div>
  );
}
