import { zodResolver } from '@hookform/resolvers/zod';
import { useState, type ReactNode } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { useNavigate } from 'react-router';
import { z } from 'zod';
import { ApiError } from '@/shared/api/ApiError';
import { messageFor } from '@/shared/api/messages';
import type { DeliveryInput, PackageSize, Quote } from '@/shared/api/types';
import { Button } from '@/shared/ui/Button';
import { newIdempotencyKey, useBookTrip, type BookRequest } from './trips';

const PACKAGE_SIZES: { value: PackageSize; label: string; hint: string }[] = [
  { value: 'SMALL', label: 'Nhỏ', hint: 'giấy tờ, hộp nhỏ' },
  { value: 'MEDIUM', label: 'Vừa', hint: 'hộp giày, túi đồ' },
  { value: 'LARGE', label: 'Lớn', hint: 'thùng, cần buộc' },
];

/** Limits from trip-service DeliveryRequest. */
const deliverySchema = z.object({
  recipientName: z.string().trim().min(1, 'Nhập tên người nhận.').max(120),
  recipientPhone: z
    .string()
    .trim()
    .regex(/^\+?[0-9 .-]{8,20}$/, 'Số điện thoại chưa đúng.'),
  packageDescription: z.string().trim().min(1, 'Ghi món hàng là gì.').max(200),
  packageSize: z.enum(['SMALL', 'MEDIUM', 'LARGE']),
  weightKg: z
    .number({ error: 'Nhập khối lượng.' })
    .positive('Nhập khối lượng.')
    .max(1000, 'Khối lượng quá lớn.'),
  instructions: z.string().trim().max(300, 'Tối đa 300 ký tự.'),
});

type DeliveryForm = z.infer<typeof deliverySchema>;

const multiplier = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 2 });

const field =
  'h-12 rounded-[10px] border-[1.5px] border-field px-3.5 text-base text-asphalt aria-invalid:border-stop';

function bookingMessage(error: unknown): string {
  if (error instanceof ApiError && error.code === 'BUSINESS_RULE_VIOLATION') {
    return 'Chưa đặt được chuyến. Có thể bạn đang có một chuyến chưa xong, hoặc giá vừa thay đổi: lấy báo giá mới rồi thử lại.';
  }
  return messageFor(error);
}

/**
 * The booking action under the quote: surge consent when the price is raised (BR-006), the
 * recipient and package for a delivery (UC-05), then POST /trips and on to the trip screen.
 */
export function BookPanel({ quote }: { quote: Quote }) {
  const navigate = useNavigate();
  const book = useBookTrip();
  const [surgeAccepted, setSurgeAccepted] = useState(false);
  // One key per booking attempt; kept after a network failure so the retry cannot book twice.
  const [key, setKey] = useState<string | null>(null);
  const delivery = quote.serviceType === 'DELIVERY';

  const {
    register,
    handleSubmit,
    control,
    setValue,
    formState: { errors },
  } = useForm<DeliveryForm>({
    resolver: zodResolver(deliverySchema),
    defaultValues: { packageSize: 'SMALL', instructions: '' },
  });
  const size = useWatch({ control, name: 'packageSize' });

  const submit = (details?: DeliveryInput) => {
    const request: BookRequest = { quoteId: quote.id };
    if (quote.surgeConfirmationRequired) request.acceptedSurgeMultiplier = quote.surgeMultiplier;
    if (details) request.delivery = details;
    const attemptKey = key ?? newIdempotencyKey();
    setKey(attemptKey);
    book.mutate(
      { key: attemptKey, request },
      {
        onSuccess: (trip) => navigate(`/app/trips/${trip.id}`, { replace: true }),
        onError: (error) => {
          if (!(error instanceof ApiError && error.code === 'NETWORK_ERROR')) setKey(null);
        },
      },
    );
  };

  const onDelivery = handleSubmit((form) =>
    submit({
      recipientName: form.recipientName,
      recipientPhone: form.recipientPhone.replace(/[ .-]/g, ''),
      packageDescription: form.packageDescription,
      packageSize: form.packageSize,
      packageWeightGrams: Math.round(form.weightKg * 1000),
      ...(form.instructions ? { instructions: form.instructions } : {}),
    }),
  );

  const blocked = quote.surgeConfirmationRequired && !surgeAccepted;

  const surge = quote.surgeConfirmationRequired && (
    <label className="flex items-start gap-3 text-[15px]">
      <input
        type="checkbox"
        checked={surgeAccepted}
        onChange={(event) => setSurgeAccepted(event.target.checked)}
        className="mt-1 size-5 accent-asphalt"
      />
      Tôi đồng ý giá cao điểm x{multiplier.format(quote.surgeMultiplier)}.
    </label>
  );

  const failure = book.isError && (
    <p role="alert" className="m-0 rounded-[10px] bg-stop-tint px-3.5 py-3 text-[15px]">
      {bookingMessage(book.error)}
    </p>
  );

  if (!delivery) {
    return (
      <div className="flex flex-col gap-3">
        {surge}
        {failure}
        <Button onClick={() => submit()} busy={book.isPending} disabled={blocked}>
          Đặt chuyến
        </Button>
      </div>
    );
  }

  return (
    <form onSubmit={onDelivery} noValidate className="flex flex-col gap-3.5">
      <h2 className="m-0 text-base font-bold">Người nhận và món hàng</h2>

      <Field id="recipient-name" label="Tên người nhận" error={errors.recipientName?.message}>
        {(a11y) => <input autoComplete="off" className={field} {...a11y} {...register('recipientName')} />}
      </Field>

      <Field id="recipient-phone" label="Số điện thoại người nhận" error={errors.recipientPhone?.message}>
        {(a11y) => (
          <input
            type="tel"
            inputMode="tel"
            autoComplete="off"
            className={field}
            {...a11y}
            {...register('recipientPhone')}
          />
        )}
      </Field>

      <Field id="package-description" label="Món hàng" error={errors.packageDescription?.message}>
        {(a11y) => (
          <input
            placeholder="Ví dụ: hộp bánh, hồ sơ"
            className={field}
            {...a11y}
            {...register('packageDescription')}
          />
        )}
      </Field>

      <fieldset className="m-0 flex flex-col gap-1 border-0 p-0">
        <legend className="mb-1 p-0 text-sm text-curb">Kích thước</legend>
        <div className="grid grid-cols-3 gap-2">
          {PACKAGE_SIZES.map((option) => (
            <button
              key={option.value}
              type="button"
              aria-pressed={size === option.value}
              onClick={() => setValue('packageSize', option.value)}
              className={`flex flex-col items-center rounded-[10px] border-[1.5px] px-2 py-2 text-[15px] font-semibold ${
                size === option.value ? 'border-asphalt bg-asphalt text-white' : 'border-field bg-white'
              }`}
            >
              {option.label}
              <span
                className={`text-xs font-normal ${size === option.value ? 'text-white/80' : 'text-curb'}`}
              >
                {option.hint}
              </span>
            </button>
          ))}
        </div>
      </fieldset>

      <Field id="package-weight" label="Khối lượng (kg)" error={errors.weightKg?.message}>
        {(a11y) => (
          <input
            type="number"
            inputMode="decimal"
            step="0.1"
            min="0"
            className={field}
            {...a11y}
            {...register('weightKg', { valueAsNumber: true })}
          />
        )}
      </Field>

      <Field id="instructions" label="Lưu ý cho tài xế (không bắt buộc)" error={errors.instructions?.message}>
        {(a11y) => (
          <textarea
            rows={2}
            className="rounded-[10px] border-[1.5px] border-field px-3.5 py-2.5 text-base text-asphalt aria-invalid:border-stop"
            {...a11y}
            {...register('instructions')}
          />
        )}
      </Field>

      {surge}
      {failure}
      <Button type="submit" busy={book.isPending} disabled={blocked}>
        Đặt giao hàng
      </Button>
    </form>
  );
}

interface FieldA11y {
  id: string;
  'aria-invalid'?: true;
  'aria-describedby'?: string;
}

/** A labelled control whose error is announced with it but is not part of its name. */
function Field({
  id,
  label,
  error,
  children,
}: {
  id: string;
  label: string;
  error?: string;
  children: (a11y: FieldA11y) => ReactNode;
}) {
  const errorId = `${id}-error`;
  return (
    <div className="flex flex-col gap-1 text-sm text-curb">
      <label htmlFor={id}>{label}</label>
      {children({
        id,
        'aria-invalid': error ? true : undefined,
        'aria-describedby': error ? errorId : undefined,
      })}
      {error && (
        <span id={errorId} className="text-sm text-stop">
          {error}
        </span>
      )}
    </div>
  );
}
