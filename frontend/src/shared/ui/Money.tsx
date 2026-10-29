import { formatVnd } from '@/shared/lib/format';

/** Every amount on screen goes through here (frontend-design.md §9). */
export function Money({ amount, className = '' }: { amount: number; className?: string }) {
  return <span className={`tabular-nums ${className}`}>{formatVnd(amount)}</span>;
}
