import { useEffect, useEffectEvent, useId, useRef, type ReactNode } from 'react';
import { createPortal } from 'react-dom';

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Modal dialog: focus moves inside (to the [autofocus] field if there is one) and stays there,
 * Escape or a click on the backdrop closes it, and focus goes back where it was afterwards.
 */
export function Dialog({
  title,
  onClose,
  children,
  wide = false,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
  wide?: boolean;
}) {
  const titleId = useId();
  const panel = useRef<HTMLDivElement>(null);
  const close = useEffectEvent(() => onClose());

  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const root = panel.current!;
    const first =
      root.querySelector<HTMLElement>('[autofocus]') ?? root.querySelector<HTMLElement>(FOCUSABLE);
    (first ?? root).focus();

    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.stopPropagation();
        close();
        return;
      }
      if (event.key !== 'Tab') return;
      const items = [...root.querySelectorAll<HTMLElement>(FOCUSABLE)];
      if (items.length === 0) return;
      const [head, tail] = [items[0]!, items[items.length - 1]!];
      if (event.shiftKey && document.activeElement === head) {
        event.preventDefault();
        tail.focus();
      } else if (!event.shiftKey && document.activeElement === tail) {
        event.preventDefault();
        head.focus();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      previous?.focus();
    };
  }, []);

  return createPortal(
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-asphalt/45 p-4"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className={`flex max-h-[90dvh] w-full flex-col gap-4 overflow-y-auto rounded-2xl bg-white p-6 shadow-xl ${wide ? 'max-w-4xl' : 'max-w-md'}`}
      >
        <h2 id={titleId} className="m-0 text-xl font-bold">
          {title}
        </h2>
        {children}
      </div>
    </div>,
    document.body,
  );
}
