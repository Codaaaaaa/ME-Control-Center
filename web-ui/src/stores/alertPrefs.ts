import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

interface AlertPrefs {
  /** Show a browser notification when one of the player's alerts fires (spec section 23). */
  browserNotifications: boolean;
  setBrowserNotifications: (enabled: boolean) => void;
}

/** Per-device: each browser asks for its own notification permission. */
export const useAlertPrefs = create<AlertPrefs>()(
  persist(
    (set) => ({
      browserNotifications: false,
      setBrowserNotifications: (browserNotifications) => set({ browserNotifications }),
    }),
    { name: 'mecc.alerts', storage: createJSONStorage(() => window.localStorage) },
  ),
);
