import { useSyncExternalStore } from 'react';

/** Whether a CSS media query matches now; false where matchMedia does not exist (tests). */
export function useMediaQuery(query: string): boolean {
  return useSyncExternalStore(
    (onChange) => {
      if (typeof window.matchMedia !== 'function') return () => {};
      const list = window.matchMedia(query);
      list.addEventListener('change', onChange);
      return () => list.removeEventListener('change', onChange);
    },
    () => (typeof window.matchMedia === 'function' ? window.matchMedia(query).matches : false),
  );
}

/** Tailwind's lg breakpoint: from here the map screens dock their panel on the left. */
export const WIDE = '(min-width: 1024px)';
