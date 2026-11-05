import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { Link, Navigate, useNavigate } from 'react-router';
import { z } from 'zod';
import { ApiError } from '@/shared/api/ApiError';
import { messageFor } from '@/shared/api/messages';
import { homeFor } from '@/shared/auth/areas';
import { register as signUp, useSession } from '@/shared/auth/session';
import { Button } from '@/shared/ui/Button';
import { RevealBackdrop } from '@/shared/ui/RevealBackdrop';
import { Splash } from '@/shared/ui/Splash';

/** Same rules as user-service Identifiers / AuthService, so most mistakes are caught before sending. */
const VN_PHONE = /^(0[35789]\d{8}|\+[1-9]\d{7,14})$/;
const EMAIL = /^[^@\s]{1,64}@[^@\s]+\.[^@\s]{2,}$/;
const utf8Bytes = (text: string) => new TextEncoder().encode(text).length;

const schema = z
  .object({
    role: z.enum(['CUSTOMER', 'DRIVER']),
    fullName: z.string().trim().min(1, 'Nhập họ tên.').max(120, 'Họ tên tối đa 120 ký tự.'),
    email: z
      .string()
      .trim()
      .max(254)
      .refine((v) => v === '' || EMAIL.test(v), 'Email chưa đúng định dạng.'),
    phone: z
      .string()
      .transform((v) => v.replace(/[\s.()-]/g, ''))
      .refine((v) => v === '' || VN_PHONE.test(v), 'Số điện thoại chưa đúng (ví dụ 0912345678).'),
    password: z
      .string()
      .min(8, 'Mật khẩu cần ít nhất 8 ký tự.')
      .refine((v) => utf8Bytes(v) <= 72, 'Mật khẩu quá dài.'),
    confirm: z.string(),
  })
  .refine((v) => v.email !== '' || v.phone !== '', {
    path: ['email'],
    message: 'Nhập email hoặc số điện thoại (ít nhất một).',
  })
  .refine((v) => v.password === v.confirm, { path: ['confirm'], message: 'Hai lần nhập mật khẩu chưa khớp.' })
  .refine((v) => v.password.toLowerCase() !== v.email.toLowerCase() && v.password !== v.phone, {
    path: ['password'],
    message: 'Mật khẩu không được trùng email hoặc số điện thoại.',
  });

type FormIn = z.input<typeof schema>;
type FormOut = z.output<typeof schema>;

const ROLES = [
  { value: 'CUSTOMER', title: 'Đặt xe, gửi hàng', body: 'Tài khoản khách hàng' },
  { value: 'DRIVER', title: 'Chạy xe, giao hàng', body: 'Tài khoản tài xế: hoàn thiện hồ sơ rồi chờ duyệt' },
] as const;

const FIELD =
  'h-12 rounded-[10px] border-[1.5px] border-field px-3.5 text-base text-asphalt aria-invalid:border-stop';

/** Server answers worth their own wording on this screen. */
function failureText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'CONFLICT')
      return 'Email hoặc số điện thoại này đã có tài khoản. Đăng nhập thay vì đăng ký.';
    if (error.code === 'BUSINESS_RULE_VIOLATION')
      return 'Mật khẩu quá yếu hoặc thông tin chưa hợp lệ, thử mật khẩu khác.';
  }
  return messageFor(error);
}

/** /register: one form for both self-service roles; a new driver goes straight to onboarding. */
export default function RegisterPage() {
  const { status, user } = useSession();
  const navigate = useNavigate();
  const [failure, setFailure] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const {
    register,
    handleSubmit,
    control,
    formState: { errors, isSubmitting },
  } = useForm<FormIn, unknown, FormOut>({
    resolver: zodResolver(schema),
    defaultValues: { role: 'CUSTOMER', fullName: '', email: '', phone: '', password: '', confirm: '' },
  });
  const role = useWatch({ control, name: 'role' });

  if (status === 'loading') return <Splash />;
  if (user) return <Navigate to={homeFor(user.roles)} replace />;

  const onSubmit = handleSubmit(async (form) => {
    setFailure(null);
    setConflict(false);
    try {
      const signedIn = await signUp({
        role: form.role,
        fullName: form.fullName,
        email: form.email || undefined,
        phone: form.phone || undefined,
        password: form.password,
      });
      navigate(homeFor(signedIn.roles), { replace: true });
    } catch (error) {
      setConflict(error instanceof ApiError && error.code === 'CONFLICT');
      setFailure(failureText(error));
    }
  });

  const fieldError = (name: keyof FormIn) =>
    errors[name] && (
      <span id={`${name}-error`} className="text-sm text-stop">
        {errors[name]?.message}
      </span>
    );
  const aria = (name: keyof FormIn) => ({
    'aria-invalid': errors[name] ? true : undefined,
    'aria-describedby': errors[name] ? `${name}-error` : undefined,
  });

  return (
    <div className="flex min-h-dvh items-center justify-center bg-concrete px-4 py-10">
      <RevealBackdrop />
      <div className="relative flex w-full max-w-[420px] flex-col gap-6 rounded-sheet bg-white px-6 py-8 shadow-[0_2px_12px_rgba(43,46,51,0.1)]">
        <header className="flex flex-col gap-2">
          <h1 className="m-0 text-[28px] leading-tight font-bold">Tạo tài khoản</h1>
          <p className="m-0 text-ink-2">
            Đã có tài khoản? <Link to="/login">Đăng nhập</Link>
          </p>
        </header>

        <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
          <fieldset className="m-0 grid grid-cols-2 gap-2 border-0 p-0">
            <legend className="mb-1.5 p-0 text-sm text-curb">Bạn muốn</legend>
            {ROLES.map((option) => (
              <label
                key={option.value}
                className={`flex cursor-pointer flex-col gap-0.5 rounded-xl border-[1.5px] p-3 ${
                  role === option.value ? 'border-asphalt bg-concrete-2' : 'border-field'
                }`}
              >
                <input type="radio" value={option.value} className="sr-only" {...register('role')} />
                <span className="font-semibold">{option.title}</span>
                <span className="text-sm text-curb">{option.body}</span>
              </label>
            ))}
          </fieldset>

          <label className="flex flex-col gap-1 text-sm text-curb">
            Họ và tên
            <input
              type="text"
              autoComplete="name"
              className={FIELD}
              {...aria('fullName')}
              {...register('fullName')}
            />
            {fieldError('fullName')}
          </label>
          <label className="flex flex-col gap-1 text-sm text-curb">
            Email
            <input
              type="email"
              autoComplete="email"
              inputMode="email"
              className={FIELD}
              {...aria('email')}
              {...register('email')}
            />
            {fieldError('email')}
          </label>
          <label className="flex flex-col gap-1 text-sm text-curb">
            Số điện thoại
            <input
              type="tel"
              autoComplete="tel"
              inputMode="tel"
              className={FIELD}
              {...aria('phone')}
              {...register('phone')}
            />
            {fieldError('phone')}
          </label>
          <p className="m-0 -mt-2 text-sm text-curb">
            Cần ít nhất một trong hai để đăng nhập và nhận mã xác minh.
          </p>
          <label className="flex flex-col gap-1 text-sm text-curb">
            Mật khẩu
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
            Nhập lại mật khẩu
            <input
              type="password"
              autoComplete="new-password"
              className={FIELD}
              {...aria('confirm')}
              {...register('confirm')}
            />
            {fieldError('confirm')}
          </label>

          {failure && (
            <p role="alert" className="m-0 rounded-[10px] bg-stop-tint px-3.5 py-3 text-[15px] text-asphalt">
              {failure} {conflict && <Link to="/login">Đến trang đăng nhập</Link>}
            </p>
          )}

          <Button type="submit" busy={isSubmitting}>
            Đăng ký
          </Button>
        </form>
      </div>
    </div>
  );
}
