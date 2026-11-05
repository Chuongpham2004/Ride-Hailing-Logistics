import type { ReactNode } from 'react';

/** A titled white panel on the staff area's grey ground. */
export function Card({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-3 rounded-xl border border-line bg-white px-5 py-4">
      <h2 className="m-0 text-lg font-bold">{title}</h2>
      {children}
    </section>
  );
}
