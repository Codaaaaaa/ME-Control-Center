import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import type { Sort, TypeFilter } from '../api/resources';

/** Tile sizes for the appearance settings of spec section 39. */
export const DENSITIES = {
  COMPACT: 44,
  AE2: 56,
  COMFORTABLE: 72,
} as const;
export type Density = keyof typeof DENSITIES;

interface TerminalPrefs {
  sort: Sort;
  descending: boolean;
  type: TypeFilter;
  density: Density;
  setSort: (sort: Sort, descending: boolean) => void;
  setType: (type: TypeFilter) => void;
  setDensity: (density: Density) => void;
}

/** Per-device terminal preferences. */
export const useTerminalPrefs = create<TerminalPrefs>()(
  persist(
    (set) => ({
      sort: 'NAME',
      descending: false,
      type: 'ALL',
      density: 'AE2',
      setSort: (sort, descending) => set({ sort, descending }),
      setType: (type) => set({ type }),
      setDensity: (density) => set({ density }),
    }),
    { name: 'mecc.terminal', storage: createJSONStorage(() => window.localStorage) },
  ),
);
