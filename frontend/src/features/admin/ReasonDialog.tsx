import { useId, useState, type ReactNode } from 'react';
import { Button } from '@/shared/ui/Button';
import { Dialog } from '@/shared/ui/Dialog';

/**
 * Confirms a staff action that changes someone else's account or trip, with the reason that goes
 * into the audit trail (frontend-design.md §4.4). `minLength` 0 makes the reason optional.
 */
export function ReasonDialog({
  title,
  children,
  confirmLabel,
  danger = false,
  minLength = 1,
  maxLength = 500,
  busy,
  error,
  onConfirm,
  onClose,
}: {
  title: string;
  children?: ReactNode;
  confirmLabel: string;
  danger?: boolean;
  minLength?: number;
  maxLength?: number;
  busy: boolean;
  error?: string;
  onConfirm: (reason: string) => void;
  onClose: () => void;
}) {
  const [reason, setReason] = useState('');
  const fieldId = useId();
  const hintId = useId();
  const length = reason.trim().length;
  const ready = length >= minLength;

  return (
    <Dialog title={title} onClose={onClose}>
      {children}
      <form
        className="flex flex-col gap-3"
        onSubmit={(event) => {
          event.preventDefault();
          if (ready && !busy) onConfirm(reason.trim());
        }}
      >
        <label htmlFor={fieldId} className="text-sm font-semibold">
          {minLength > 0 ? 'Lý do' : 'Ghi chú (không bắt buộc)'}
        </label>
        <textarea
          id={fieldId}
          autoFocus
          rows={4}
          maxLength={maxLength}
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          aria-describedby={hintId}
          className="resize-y rounded-lg border-[1.5px] border-field px-3 py-2 text-[15px]"
        />
        <span id={hintId} className="text-sm text-curb">
          {minLength > 1
            ? `Ít nhất ${minLength} ký tự (${length}/${minLength}). Người liên quan và nhật ký kiểm tra sẽ thấy lý do này.`
            : 'Người liên quan và nhật ký kiểm tra sẽ thấy lý do này.'}
        </span>
        {error && (
          <p role="alert" className="m-0 text-[15px] text-stop">
            {error}
          </p>
        )}
        <div className="flex justify-end gap-2">
          <Button size="compact" variant="secondary" onClick={onClose}>
            Đóng
          </Button>
          <Button
            size="compact"
            type="submit"
            variant={danger ? 'danger' : 'dark'}
            disabled={!ready}
            busy={busy}
          >
            {confirmLabel}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
