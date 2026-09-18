import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';

export interface VirtualGrid {
  /** Attach to the scrolling element. */
  containerRef: React.RefObject<HTMLDivElement | null>;
  columns: number;
  rowHeight: number;
  totalHeight: number;
  /** First and last (exclusive) item index to render. */
  start: number;
  end: number;
  scrollToIndex: (index: number) => void;
}

/**
 * Minimal windowing for a fixed-size tile grid: only the visible rows (plus a small overscan) are
 * rendered, so tens of thousands of resources stay smooth and only visible icons are requested
 * (spec section 48).
 */
export function useVirtualGrid(itemCount: number, tileSize: number, gap: number, overscanRows = 3): VirtualGrid {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [width, setWidth] = useState(0);
  const [height, setHeight] = useState(0);
  const [scrollTop, setScrollTop] = useState(0);

  useLayoutEffect(() => {
    const element = containerRef.current;
    if (!element) return;
    const observer = new ResizeObserver(() => {
      setWidth(element.clientWidth);
      setHeight(element.clientHeight);
    });
    observer.observe(element);
    setWidth(element.clientWidth);
    setHeight(element.clientHeight);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const element = containerRef.current;
    if (!element) return;
    const onScroll = () => setScrollTop(element.scrollTop);
    element.addEventListener('scroll', onScroll, { passive: true });
    return () => element.removeEventListener('scroll', onScroll);
  }, []);

  const step = tileSize + gap;
  const columns = Math.max(1, Math.floor((width + gap) / step));
  const rows = Math.ceil(itemCount / columns);
  const visibleRows = Math.ceil(height / step) + overscanRows * 2;
  const firstRow = Math.max(0, Math.floor(scrollTop / step) - overscanRows);
  const start = firstRow * columns;
  const end = Math.min(itemCount, start + visibleRows * columns);

  const scrollToIndex = useCallback(
    (index: number) => {
      const element = containerRef.current;
      if (!element) return;
      const row = Math.floor(index / columns);
      const top = row * step;
      if (top < element.scrollTop) {
        element.scrollTo({ top });
      } else if (top + step > element.scrollTop + element.clientHeight) {
        element.scrollTo({ top: top + step - element.clientHeight });
      }
    },
    [columns, step],
  );

  return { containerRef, columns, rowHeight: step, totalHeight: rows * step, start, end, scrollToIndex };
}
