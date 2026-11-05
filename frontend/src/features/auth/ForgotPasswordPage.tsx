import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router';
import { z } from 'zod/mini';
import { ApiError } from '@/shared/api/ApiError';
import { messageFor } from '@/shared/api/messages';
import { homeFor } from '@/shared/auth/areas';
import { confirmPasswordReset, login, requestPasswordReset, useSession } from '@/shared/auth/session';
import { formatCountdown } from '@/shared/lib/format';
import { useCountdown } from '@/shared/lib/useCountdown';
import { Button } from '@/shared/ui/Button';
import { RevealBackdrop } from '@/shared/ui/RevealBackdrop';
import { Splash } from '@/shared/ui/Splash';

/** Wait before asking for another code; the server also limits requests per hour. */
const RESEND_AFTER_S = 60;
const utf8Bytes = (text: string) => new TextEncoder().encode(text).length;

const FIELD =
  'h-12 rounded-[10px] border-[1.5px] border-field px-3.5 text-base text-asphalt aria-invalid:border-stop';

const askSchema = z.object({
  identifier: z.string().check(z.trim(), z.minLength(1, 'Nhập email hoặc số điện thoại.'), z.maxLength(254)),
});

/** Same rules as user-service AuthService.validatePassword. */
function resetSchema(identifier: string) {
  return z
    .object({
      code: z.string().check(z.trim(), z.regex(/^\d{6}$/, 'Mã gồm 6 chữ số.')),
      password: z.string().check(
        z.minLength(8, 'Mật khẩu cần ít nhất 8 ký tự.'),
        z.refine((v) => utf8Bytes(v) <= 72, 'Mật khẩu quá dài.'),
        z.refine(
          (v) => v.toLowerCase() !== identifier.toLowerCase(),
          'Mật khẩu không được trùng email hoặc số điện thoại.',
        ),
      ),
      confirm: z.string(),
    })
    .check(
      z.refine((v) => v.password === v.confirm, {
        path: ['confirm'],
        error: 'Hai lần nhập mật khẩu chưa khớp.',
      }),
    );
}

type AskForm = z.infer<typeof askSchema>;
type ResetForm = z.infer<ReturnType<typeof resetSchema>>;

function resetFailure(error: unknown): string {
  if (error instanceof ApiError) {
    // A wrong, expired or used code, and an unknown address, all answer the same way on purpose.
    if (error.code === 'VALIDATION_ERROR')
      return 'Mã chưa đúng hoặc đã hết hạn. Kiểm tra lại, hoặc gửi mã mới.';
    if (error.code === 'BUSINESS_RULE_VIOLATION')
      return 'Mật khẩu quá yếu: cần ít nhất 8 ký tự và không trùng email hoặc số điện thoại.';
  }
  return messageFor(error);
}

/**
 * /forgot-password: a code goes to the email or phone typed, then the code and a new password.
 * Every session of the account is signed out by the server; this tab signs in again with the new
 * password straight away.
 */
export default function ForgotPasswordPage() {
  const { status, user } = useSession();
  const [params] = useSearchParams();
  const [identifier, setIdentifier] = useState<string | null>(null);

  if (status === 'loading') return <Splash />;
  if (user) return <Navigate to={homeFor(user.roles)} replace />;

  return (
    <div className="flex min-h-dvh items-center justify-center bg-concrete px-4 py-10">
      <RevealBackdrop />
      <div className="relative flex w-full max-w-[400px] flex-col gap-6 rounded-sheet bg-white px-6 py-8 shadow-[0_2px_12px_rgba(43,46,51,0.1)]">
        <header className="flex flex-col gap-2">
          <h1 className="m-0 text-[28px] leading-tight font-bold">Quên mật khẩu</h1>
          <p className="m-0 text-ink-2">
            Nhớ ra rồi? <Link to="/login">Đăng nhập</Link>
          </p>
        </header>
        {identifier === null ? (
          <AskStep initial={params.get('identifier') ?? ''} onSent={setIdentifier} />
        ) : (
          <ResetStep identifier={identifier} onChangeAddress={() => setIdentifier(null)} />
        )}
      </div>
    </div>
  );
}

function AskStep({ initial, onSent }: { initial: string; onSent: (identifier: string) => void }) {
  const [failure, setFailure] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<AskForm>({ resolver: zodResolver(askSchema), defaultValues: { identifier: initial } });

  const onSubmit = handleSubmit(async ({ identifier }) => {
    setFailure(null);
    try {
      await requestPasswordReset(identifier);
      onSent(identifier);
    } catch (error) {
      setFailure(messageFor(error));
    }
  });

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
      <p className="m-0 text-[15px] text-ink-2">
        Nhập email hoặc số điện thoại của tài khoản, chúng tôi gửi mã 6 số.
      </p>
      <label className="flex flex-col gap-1 text-sm text-curb">
        Email hoặc số điện thoại
        <input
          type="text"
          autoComplete="username"
          inputMode="email"
          autoFocus
          aria-invalid={errors.identifier ? true : undefined}
          aria-describedby={errors.identifier ? 'identifier-error' : undefined}
          className={FIELD}
          {...register('identifier')}
        />
        {errors.identifier && (
          <span id="identifier-error" className="text-sm text-stop">
            {errors.identifier.message}
          </span>
        )}
      </label>
      {failure && (
        <p role="alert" className="m-0 rounded-[10px] bg-stop-tint px-3.5 py-3 text-[15px]">
          {failure}
        </p>
      )}
      <Button type="submit" busy={isSubmitting}>
        Gửi mã
      </Button>
    </form>
  );
}

function ResetStep({ identifier, onChangeAddress }: { identifier: string; onChangeAddress: () => void }) {
  const navigate = useNavigate();
  const [failure, setFailure] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [resendAt, setResendAt] = useState(() => new Date(Date.now() + RESEND_AFTER_S * 1000).toISOString());
  const [resending, setResending] = useState(false);
  const wait = useCountdown(resendAt);
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<ResetForm>({
    resolver: zodResolver(resetSchema(identifier)),
    defaultValues: { code: '', password: '', confirm: '' },
  });

  const onSubmit = handleSubmit(async ({ code, password }) => {
    setFailure(null);
    try {
      await confirmPasswordReset(identifier, code, password);
    } catch (error) {
      setFailure(resetFailure(error));
      return;
    }
    try {
      const signedIn = await login(identifier, password);
      navigate(homeFor(signedIn.roles), { replace: true });
    } catch {
      // The password is changed even if signing in right now fails (network, say).
      navigate(`/login?identifier=${encodeURIComponent(identifier)}&reset=1`, { replace: true });
    }
  });

  const resend = async () => {
    setResending(true);
    setFailure(null);
    try {
      await requestPasswordReset(identifier);
      setNotice('Đã gửi mã mới. Mã cũ không dùng được nữa.');
      setResendAt(new Date(Date.now() + RESEND_AFTER_S * 1000).toISOString());
    } catch (error) {
      setFailure(messageFor(error));
    } finally {
      setResending(false);
    }
  };

  const fieldError = (name: keyof ResetForm) =>
    errors[name] && (
      <span id={`${name}-error`} className="text-sm text-stop">
        {errors[name]?.message}
      </span>
    );
  const aria = (name: keyof ResetForm) => ({
    'aria-invalid': errors[name] ? true : undefined,
    'aria-describedby': errors[name] ? `${name}-error` : undefined,
  });

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
      <p role="status" className="m-0 text-[15px] text-ink-2">
        Nếu <strong className="text-asphalt">{identifier}</strong> thuộc một tài khoản đang hoạt động, mã 6 số
        vừa được gửi tới đó (hết hạn sau ít phút).{' '}
        <button type="button" onClick={onChangeAddress} className="font-semibold text-guide">
          Đổi địa chỉ
        </button>
      </p>
      <label className="flex flex-col gap-1 text-sm text-curb">
        Mã xác nhận
        <input
          type="text"
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={6}
          autoFocus
          className={`${FIELD} font-plate text-2xl tracking-[0.3em]`}
          {...aria('code')}
          {...register('code')}
        />
        {fieldError('code')}
      </label>
      <label className="flex flex-col gap-1 text-sm text-curb">
        Mật khẩu mới
        <input
          type="password"
          autoComplete="new-password"
          className={FIELD}
          {...aria('password')}
          {...register('password')}
        />
        {fieldError('password')}
      </label>
      <label className="flex flex-col gap-1 text-sm text-curb">
        Nhập lại mật khẩu mới
        <input
          type="password"
          autoComplete="new-password"
          className={FIELD}
          {...aria('confirm')}
          {...register('confirm')}
        />
        {fieldError('confirm')}
      </label>
      {notice && !failure && <p className="m-0 text-sm text-go">{notice}</p>}
      {failure && (
        <p role="alert" className="m-0 rounded-[10px] bg-stop-tint px-3.5 py-3 text-[15px]">
          {failure}
        </p>
      )}
      <Button type="submit" busy={isSubmitting}>
        Đặt mật khẩu mới
      </Button>
      <Button variant="secondary" busy={resending} disabled={wait > 0} onClick={() => void resend()}>
        {wait > 0 ? `Gửi lại mã sau ${formatCountdown(wait)}` : 'Gửi lại mã'}
      </Button>
      <p className="m-0 text-sm text-curb">
        Đặt lại xong, mọi thiết bị đang đăng nhập tài khoản này sẽ bị đăng xuất.
      </p>
    </form>
  );
}
