import { useState, type FormEvent } from 'react';
import { ApiError } from '@/shared/api/ApiError';
import { messageFor } from '@/shared/api/messages';
import { useCountdown } from '@/shared/lib/useCountdown';
import { Button } from '@/shared/ui/Button';
import { useConfirmCode, useSendCode, type Channel, type CodeSent } from './contacts';

/**
 * One contact of the signed-in user: its state, and when unverified, sending a code and confirming
 * it. `onVerified` runs after the user has been reloaded (e.g. to reload a driver profile).
 */
export function ContactVerification({
  channel,
  label,
  value,
  verified,
  onVerified,
}: {
  channel: Channel;
  label: string;
  value: string;
  verified: boolean;
  onVerified?: () => unknown;
}) {
  const send = useSendCode();
  const confirm = useConfirmCode(onVerified);
  const [sent, setSent] = useState<(CodeSent & { resendAt: string }) | null>(null);
  const [code, setCode] = useState('');
  const resendIn = useCountdown(sent?.resendAt);

  const sendCode = () =>
    send.mutate(channel, {
      onSuccess: (result) =>
        setSent({
          ...result,
          resendAt: new Date(Date.now() + result.resendAfterSeconds * 1000).toISOString(),
        }),
    });

  const onConfirm = (event: FormEvent) => {
    event.preventDefault();
    if (/^\d{6}$/.test(code)) confirm.mutate({ channel, code });
  };

  const confirmProblem =
    confirm.error instanceof ApiError &&
    (confirm.error.code === 'BUSINESS_RULE_VIOLATION' || confirm.error.code === 'VALIDATION_ERROR')
      ? 'Mã chưa đúng hoặc đã hết hạn.'
      : confirm.isError
        ? messageFor(confirm.error)
        : null;

  return (
    <div className="flex flex-col gap-2.5 rounded-[10px] border border-line px-3.5 py-3">
      <div className="flex items-baseline justify-between gap-3">
        <span className="flex min-w-0 flex-col">
          <span className="text-sm text-curb">{label}</span>
          <span className="truncate font-semibold">{value}</span>
        </span>
        <span className={`shrink-0 text-sm ${verified ? 'text-go' : 'text-curb'}`}>
          {verified ? 'Đã xác minh' : 'Chưa xác minh'}
        </span>
      </div>

      {!verified && !sent && (
        <>
          {send.isError && (
            <p role="alert" className="m-0 text-sm text-stop">
              {messageFor(send.error)}
            </p>
          )}
          <Button variant="secondary" busy={send.isPending} onClick={sendCode}>
            Gửi mã xác minh
          </Button>
        </>
      )}

      {!verified && sent && (
        <form onSubmit={onConfirm} className="flex flex-col gap-2.5">
          <label className="flex flex-col gap-1 text-sm text-curb">
            Mã 6 số đã gửi tới {sent.destination}
            <input
              className={`h-12 rounded-[10px] border-[1.5px] border-field bg-white px-3.5 text-asphalt text-center font-plate text-2xl tracking-[0.3em]`}
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={6}
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
            />
          </label>
          {confirmProblem && (
            <p role="alert" className="m-0 text-sm text-stop">
              {confirmProblem}
            </p>
          )}
          <Button type="submit" busy={confirm.isPending} disabled={code.length !== 6}>
            Xác nhận
          </Button>
          <button
            type="button"
            onClick={sendCode}
            disabled={resendIn > 0 || send.isPending}
            className="min-h-11 self-center text-sm font-semibold text-guide disabled:text-curb"
          >
            {resendIn > 0 ? `Gửi lại mã sau ${resendIn} giây` : 'Gửi lại mã'}
          </button>
        </form>
      )}
    </div>
  );
}
