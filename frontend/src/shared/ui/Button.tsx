import type { ButtonHTMLAttributes } from 'react';

type Variant = 'primary' | 'dark' | 'secondary' | 'danger';

const VARIANTS: Record<Variant, string> = {
  // Sign yellow is the one primary action per screen.
  primary: 'bg-sign text-asphalt hover:brightness-95',
  dark: 'bg-asphalt text-white hover:bg-ink-2',
  secondary: 'border-[1.5px] border-field bg-white text-asphalt hover:bg-concrete-2',
  danger: 'bg-stop text-white hover:brightness-95',
};

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  busy?: boolean;
}

/** Full-width by default, at least 52 px tall: thumb-sized at the bottom of a phone screen. */
export function Button({
  variant = 'primary',
  busy = false,
  disabled,
  className = '',
  children,
  ...rest
}: ButtonProps) {
  return (
    <button
      type="button"
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      className={`flex min-h-13 w-full items-center justify-center gap-2 rounded-xl px-5 text-[17px] font-bold transition disabled:cursor-not-allowed disabled:opacity-50 ${VARIANTS[variant]} ${className}`}
      {...rest}
    >
      {busy && (
        <span
          aria-hidden="true"
          className="size-4 animate-spin rounded-full border-2 border-current border-r-transparent"
        />
      )}
      {children}
    </button>
  );
}
