import { useLayoutEffect, useRef, useState } from 'react';

/** Live height of an element, e.g. the sheet the map has to keep its stops clear of. */
export function useHeight<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const [height, setHeight] = useState(0);

  useLayoutEffect(() => {
    const element = ref.current;
    if (!element) return;
    setHeight(element.offsetHeight);
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(() => setHeight(element.offsetHeight));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  return [ref, height] as const;
}
