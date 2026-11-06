import type { ReactNode, Ref } from 'react';

const MAX_HEIGHT = { 70: 'max-h-[70%]', 75: 'max-h-[75%]', 80: 'max-h-[80%]' } as const;

/**
 * The map screens' panel: a sheet over the bottom of the map on a phone, a 420 px column docked on
 * the left on a wide screen. Pair it with useMapPanel (shared/ui/panelLayout).
 */
export function MapPanel({
  panelRef,
  maxHeight = 75,
  children,
}: {
  panelRef: Ref<HTMLDivElement>;
  /** Share of the screen the sheet may take on a phone, in %. */
  maxHeight?: keyof typeof MAX_HEIGHT;
  children: ReactNode;
}) {
  return (
    <div
      ref={panelRef}
      className={`absolute inset-x-0 bottom-0 overflow-y-auto ${MAX_HEIGHT[maxHeight]} lg:inset-y-0 lg:right-auto lg:max-h-none lg:w-[420px] lg:border-r lg:border-line lg:bg-white`}
    >
      {children}
    </div>
  );
}
