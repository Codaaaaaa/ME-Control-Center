import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

interface SelectedNetworkState {
  /** Network last chosen on this device, or null. It may no longer be accessible; callers must validate it. */
  networkId: string | null;
  select: (networkId: string | null) => void;
}

/** Per-device UI preference: which ME network the Overview shows. */
export const useSelectedNetwork = create<SelectedNetworkState>()(
  persist(
    (set) => ({
      networkId: null,
      select: (networkId) => set({ networkId }),
    }),
    { name: 'mecc.selectedNetwork', storage: createJSONStorage(() => window.localStorage) },
  ),
);

/** Picks the network to show: the stored choice if still accessible, else the first one. */
export function resolveSelectedNetwork<T extends { id: string }>(networks: readonly T[], stored: string | null): T | undefined {
  return networks.find((network) => network.id === stored) ?? networks[0];
}
