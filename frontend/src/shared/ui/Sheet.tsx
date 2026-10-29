import type { ReactNode } from 'react';

/** The white panel that slides over the map from the bottom edge. */
export function Sheet({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <section
      className={`relative flex flex-col gap-4 rounded-t-sheet bg-white px-5 pt-2.5 pb-5 shadow-[0_-2px_12px_rgba(43,46,51,0.12)] ${className}`}
    >
      <div aria-hidden="true" className="h-1 w-10 self-center rounded-full bg-line" />
      {children}
    </section>
  );
}
