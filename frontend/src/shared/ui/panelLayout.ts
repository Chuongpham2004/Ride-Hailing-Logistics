import { useHeight } from '@/shared/lib/useHeight';
import { useMediaQuery, WIDE } from '@/shared/lib/useMediaQuery';

/** Width of the panel docked on the left of the map on wide screens, in px. */
export const PANEL_WIDTH = 420;

/** For things floating over the map (back button, GPS chip): keep them right of the docked panel. */
export const BESIDE_PANEL = 'lg:left-[436px]';

/**
 * Where the map screens put their panel, and how much of the map it covers so the stops can be
 * fitted into the rest: on a phone a sheet over the bottom of the map, on a wide screen a column
 * docked on the left, full height (see MapPanel).
 */
export function useMapPanel() {
  const [ref, height] = useHeight<HTMLDivElement>();
  const wide = useMediaQuery(WIDE);
  const insets = wide ? { bottom: 0, left: PANEL_WIDTH } : { bottom: height, left: 0 };
  return { ref, insets };
}
