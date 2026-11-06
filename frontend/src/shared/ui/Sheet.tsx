import type { ReactNode } from 'react';

/**
 * The white panel over the map: a sheet from the bottom edge on a phone, the full-height content of
 * the docked panel on a wide screen (see MapPanel).
 */
export function Sheet({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <section
      className={`relative flex flex-col gap-4 rounded-t-sheet bg-white px-5 pt-2.5 pb-5 shadow-[0_-2px_12px_rgba(43,46,51,0.12)] lg:min-h-full lg:rounded-none lg:px-7 lg:pt-7 lg:pb-8 lg:shadow-none ${className}`}
    >
      <div aria-hidden="true" className="h-1 w-10 self-center rounded-full bg-line lg:hidden" />
      {children}
    </section>
  );
}
