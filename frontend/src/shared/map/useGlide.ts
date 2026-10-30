import { useEffect, useRef, useState } from 'react';
import type { MapPoint } from './MapView';

const GLIDE_MS = 1000;

/**
 * Moves a marker smoothly from where it is to each new position instead of jumping
 * (frontend-design.md §6): linear over one second, shorter than the 3 s between reports.
 * The first position, and every position when reduced motion is asked for, is shown at once.
 */
export function useGlide(target: MapPoint | null | undefined): MapPoint | null {
  const [shown, setShown] = useState<MapPoint | null>(target ?? null);
  const shownRef = useRef<MapPoint | null>(shown);

  useEffect(() => {
    if (!target) {
      shownRef.current = null;
      return;
    }
    const from = shownRef.current;
    const instant = !from || window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    const start = performance.now();
    let frame = 0;
    const step = (now: number) => {
      const t = instant || !from ? 1 : Math.min(1, (now - start) / GLIDE_MS);
      const point =
        t === 1 || !from
          ? target
          : {
              latitude: from.latitude + (target.latitude - from.latitude) * t,
              longitude: from.longitude + (target.longitude - from.longitude) * t,
            };
      shownRef.current = point;
      setShown(point);
      if (t < 1) frame = requestAnimationFrame(step);
    };
    frame = requestAnimationFrame(step);
    return () => cancelAnimationFrame(frame);
  }, [target]);

  return target ? shown : null;
}
