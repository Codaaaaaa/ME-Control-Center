import { useEffect, useRef, useState } from 'react';
import type { ResourceWindow } from '../../hooks/useResourceWindow';
import { useVirtualGrid } from '../../hooks/useVirtualGrid';
import { ResourceTile } from './ResourceTile';

const GAP = 6;

/**
 * Virtualized item grid: the AE2 terminal's grid-first layout (spec section 39) for storages with tens of
 * thousands of entries. Supports mouse and keyboard navigation.
 */
export function ResourceGrid({
  window: resources,
  tileSize,
  selectedId,
  onSelect,
  onRangeChange,
}: {
  window: ResourceWindow;
  tileSize: number;
  selectedId: string | null;
  onSelect: (id: string | null) => void;
  onRangeChange: (start: number, end: number) => void;
}) {
  const grid = useVirtualGrid(resources.total, tileSize, GAP);
  const [focusIndex, setFocusIndex] = useState(0);
  const gridRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    onRangeChange(grid.start, grid.end);
  }, [grid.start, grid.end, onRangeChange]);

  useEffect(() => {
    if (focusIndex >= resources.total) {
      setFocusIndex(0);
    }
  }, [resources.total, focusIndex]);

  const move = (delta: number) => {
    const next = Math.max(0, Math.min(resources.total - 1, focusIndex + delta));
    setFocusIndex(next);
    grid.scrollToIndex(next);
    // The tile may not be mounted yet; focus it once it is.
    requestAnimationFrame(() => {
      const item = resources.itemAt(next);
      if (item) {
        gridRef.current?.querySelector<HTMLElement>(`[data-resource-tile="${CSS.escape(item.id)}"]`)?.focus();
      }
    });
  };

  const onKeyDown = (event: React.KeyboardEvent) => {
    const keys: Record<string, number> = {
      ArrowRight: 1,
      ArrowLeft: -1,
      ArrowDown: grid.columns,
      ArrowUp: -grid.columns,
      PageDown: grid.columns * 5,
      PageUp: -grid.columns * 5,
    };
    if (event.key in keys) {
      event.preventDefault();
      move(keys[event.key]!);
    } else if (event.key === 'Home') {
      event.preventDefault();
      move(-focusIndex);
    } else if (event.key === 'End') {
      event.preventDefault();
      move(resources.total - 1 - focusIndex);
    } else if (event.key === 'Escape' && selectedId) {
      onSelect(null);
    }
  };

  const tiles = [];
  for (let index = grid.start; index < grid.end; index++) {
    const row = Math.floor(index / grid.columns);
    const column = index % grid.columns;
    const resource = resources.itemAt(index);
    tiles.push(
      <div
        key={index}
        className="tile-slot"
        style={{ transform: `translate(${column * (tileSize + GAP)}px, ${row * (tileSize + GAP)}px)` }}
      >
        <ResourceTile
          resource={resource}
          assetVersion={resources.assetVersion ?? ''}
          size={tileSize}
          selected={resource?.id === selectedId}
          focusable={index === focusIndex}
          onSelect={() => {
            setFocusIndex(index);
            onSelect(resource ? resource.id : null);
          }}
        />
      </div>,
    );
  }

  return (
    <div className="terminal-scroll" ref={grid.containerRef}>
      <div
        className="terminal-grid"
        ref={gridRef}
        style={{ height: grid.totalHeight }}
        role="grid"
        aria-rowcount={Math.ceil(resources.total / grid.columns)}
        onKeyDown={onKeyDown}
      >
        {tiles}
      </div>
    </div>
  );
}
