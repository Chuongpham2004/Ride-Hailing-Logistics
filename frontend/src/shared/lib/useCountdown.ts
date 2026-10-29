import { useEffect, useState } from 'react';

/** Whole seconds left until `until` (ISO instant from the server), ticking once a second. */
export function useCountdown(until: string | undefined): number {
  const target = until ? Date.parse(until) : NaN;
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (Number.isNaN(target)) return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [target]);

  if (Number.isNaN(target)) return 0;
  return Math.max(0, Math.ceil((target - now) / 1000));
}
