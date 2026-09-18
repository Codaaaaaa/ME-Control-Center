import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useSyncExternalStore } from 'react';
import { liveClient, liveEffects, type LiveStatus } from '../api/live';
import { patternKeys, queryKeys } from '../api/queries';

/**
 * Keeps a network's cached data fresh from `/ws/v1` while the component is mounted. Returns whether live
 * updates are flowing, so callers can poll instead when they are not.
 */
export function useLiveNetwork(networkId: string | undefined, locale: string): boolean {
  const client = useQueryClient();
  const status: LiveStatus = useSyncExternalStore(liveClient.onStatus, liveClient.getStatus);

  useEffect(() => {
    if (!networkId) return;
    const stopEvents = liveClient.onEvent((event) => {
      for (const effect of liveEffects(event)) {
        if (effect.networkId !== networkId) continue;
        switch (effect.kind) {
          case 'cpus':
            client.setQueryData(queryKeys.cpus(networkId, locale), effect.cpus);
            // Running orders show the same progress; refresh them with it.
            void client.invalidateQueries({ queryKey: queryKeys.orderList(networkId, 'ACTIVE', locale) });
            break;
          case 'order':
            void client.invalidateQueries({ queryKey: queryKeys.orders(networkId) });
            void client.invalidateQueries({ queryKey: ['crafting', networkId, 'order', effect.order.id] });
            break;
          case 'patterns':
            // Someone encoded or deployed a pattern: providers and history changed.
            void client.invalidateQueries({ queryKey: patternKeys.network(networkId) });
            break;
          case 'network':
          case 'ended':
            void client.invalidateQueries({ queryKey: queryKeys.network(networkId) });
            void client.invalidateQueries({ queryKey: queryKeys.networks });
            break;
        }
      }
    });
    const stopSubscription = liveClient.subscribe(networkId, locale);
    return () => {
      stopSubscription();
      stopEvents();
    };
  }, [client, networkId, locale]);

  // After a reconnect, anything may have been missed: recover from REST.
  useEffect(() => {
    if (status === 'open' && networkId) {
      void client.invalidateQueries({ queryKey: queryKeys.crafting(networkId) });
    }
  }, [client, networkId, status]);

  return status === 'open';
}
