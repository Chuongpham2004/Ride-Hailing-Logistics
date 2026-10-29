import { useEffect, useRef } from 'react';

const ROWS = 32;
const ROW_TEXT = Array.from({ length: 40 }, () => 'RHL').join(' ');

/**
 * The ground around the app frame on wide screens: rows of "RHL" in the plate face, alternating
 * guide blue and sign yellow like the street signs the palette comes from, hidden until
 * the pointer comes near, then revealed in a soft circle that follows it. Only for devices that
 * hover; the position goes through CSS variables so moving the mouse never re-renders React.
 */
export function RevealBackdrop() {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const element = ref.current;
    if (!element || typeof window.matchMedia !== 'function' || !window.matchMedia('(hover: hover)').matches) {
      return;
    }

    let frame = 0;
    let x = 0;
    let y = 0;
    const paint = () => {
      frame = 0;
      element.style.setProperty('--reveal-x', `${x}px`);
      element.style.setProperty('--reveal-y', `${y}px`);
      element.style.setProperty('--reveal-size', '200px');
    };
    const move = (event: PointerEvent) => {
      if (event.pointerType === 'touch') return;
      x = event.clientX;
      y = event.clientY;
      if (!frame) frame = requestAnimationFrame(paint);
    };
    const hide = () => element.style.setProperty('--reveal-size', '0px');

    window.addEventListener('pointermove', move, { passive: true });
    document.documentElement.addEventListener('pointerleave', hide);
    return () => {
      window.removeEventListener('pointermove', move);
      document.documentElement.removeEventListener('pointerleave', hide);
      if (frame) cancelAnimationFrame(frame);
    };
  }, []);

  return (
    <div
      ref={ref}
      aria-hidden="true"
      className="reveal-backdrop pointer-events-none fixed inset-0 overflow-hidden select-none"
    >
      {Array.from({ length: ROWS }, (_, row) => (
        <div
          key={row}
          className={`font-plate text-[44px] leading-[64px] font-bold tracking-[0.08em] whitespace-nowrap [word-spacing:0.9em] ${
            row % 2 ? 'text-sign' : 'text-guide'
          }`}
          style={{ marginLeft: row % 2 ? '-38px' : '-96px' }}
        >
          {ROW_TEXT}
        </div>
      ))}
    </div>
  );
}
