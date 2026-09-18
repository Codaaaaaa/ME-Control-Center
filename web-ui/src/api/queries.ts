import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import * as auth from './auth';
import * as crafting from './crafting';
import * as networks from './networks';

export const queryKeys = {
  me: ['me'] as const,
  devices: ['devices'] as const,
  networks: ['networks'] as const,
  network: (id: string) => ['networks', id] as const,
  members: (id: string) => ['networks', id, 'members'] as const,
  candidates: ['networks', 'candidates'] as const,
  crafting: (networkId: string) => ['crafting', networkId] as const,
  cpus: (networkId: string, locale: string) => ['crafting', networkId, 'cpus', locale] as const,
  orders: (networkId: string) => ['crafting', networkId, 'orders'] as const,
  orderList: (networkId: string, filter: crafting.OrderFilter, locale: string) =>
    ['crafting', networkId, 'orders', filter, locale] as const,
  order: (networkId: string, orderId: string, locale: string) => ['crafting', networkId, 'order', orderId, locale] as const,
  plan: (networkId: string, planId: string, locale: string) => ['crafting', networkId, 'plan', planId, locale] as const,
};

/** Crafting data refresh: slow when live updates arrive over the WebSocket, fast when they do not. */
export function craftingRefreshMs(live: boolean): number {
  return live ? 30_000 : 3_000;
}

/** Live network status is refreshed on this cadence (discovery runs server-side every ~10 s). */
export const NETWORK_REFRESH_MS = 10_000;

export function useMe() {
  return useQuery({ queryKey: queryKeys.me, queryFn: ({ signal }) => auth.fetchMe(signal), staleTime: 60_000 });
}

export function useDevices() {
  return useQuery({ queryKey: queryKeys.devices, queryFn: ({ signal }) => auth.fetchDevices(signal) });
}

export function useNetworks() {
  return useQuery({
    queryKey: queryKeys.networks,
    queryFn: ({ signal }) => networks.fetchNetworks(signal),
    refetchInterval: NETWORK_REFRESH_MS,
  });
}

export function useNetwork(id: string | undefined) {
  return useQuery({
    queryKey: queryKeys.network(id ?? ''),
    queryFn: ({ signal }) => networks.fetchNetwork(id ?? '', signal),
    enabled: id !== undefined,
    refetchInterval: NETWORK_REFRESH_MS,
  });
}

export function useMembers(id: string) {
  return useQuery({ queryKey: queryKeys.members(id), queryFn: ({ signal }) => networks.fetchMembers(id, signal) });
}

export function useCandidates(enabled = true) {
  return useQuery({
    queryKey: queryKeys.candidates,
    queryFn: ({ signal }) => networks.fetchCandidates(signal),
    enabled,
  });
}

export function usePair() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ key, deviceName }: { key: string; deviceName: string }) => auth.pair(key, deviceName),
    onSuccess: (me) => {
      client.clear();
      client.setQueryData(queryKeys.me, me);
    },
  });
}

export function useLogout() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: auth.logout,
    onSettled: () => {
      client.clear();
      void client.invalidateQueries({ queryKey: queryKeys.me });
    },
  });
}

export function useRenameDevice() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, name }: { id: string; name: string }) => auth.renameDevice(id, name),
    onSuccess: () => client.invalidateQueries({ queryKey: queryKeys.devices }),
  });
}

export function useRevokeDevice() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: auth.revokeDevice,
    onSuccess: () => client.invalidateQueries({ queryKey: queryKeys.devices }),
  });
}

export function useRevokeOtherDevices() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: auth.revokeOtherDevices,
    onSuccess: () => client.invalidateQueries({ queryKey: queryKeys.devices }),
  });
}

export function useClaimNetwork() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ key, name }: { key: string; name: string }) => networks.claimNetwork(key, name),
    onSuccess: (detail) => {
      client.setQueryData(queryKeys.network(detail.network.id), detail);
      void client.invalidateQueries({ queryKey: queryKeys.networks });
    },
  });
}

export function useRenameNetwork(id: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => networks.renameNetwork(id, name),
    onSuccess: () => client.invalidateQueries({ queryKey: queryKeys.networks }),
  });
}

export function useDeleteNetwork() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: networks.deleteNetwork,
    onSuccess: (_, id) => {
      client.removeQueries({ queryKey: queryKeys.network(id) });
      void client.invalidateQueries({ queryKey: queryKeys.networks });
    },
  });
}

export function useMemberMutations(id: string) {
  const client = useQueryClient();
  const refresh = () => {
    void client.invalidateQueries({ queryKey: queryKeys.members(id) });
    void client.invalidateQueries({ queryKey: queryKeys.networks });
  };
  return {
    add: useMutation({
      mutationFn: ({ player, role }: { player: string; role: networks.Role }) => networks.addMember(id, player, role),
      onSuccess: refresh,
    }),
    changeRole: useMutation({
      mutationFn: ({ playerUuid, role }: { playerUuid: string; role: networks.Role }) =>
        networks.changeMemberRole(id, playerUuid, role),
      onSuccess: refresh,
    }),
    remove: useMutation({
      mutationFn: (playerUuid: string) => networks.removeMember(id, playerUuid),
      onSuccess: refresh,
    }),
  };
}

// --- Crafting (spec sections 9-12) -----------------------------------------------------------------

export function useCpus(networkId: string | undefined, locale: string, live: boolean) {
  return useQuery({
    queryKey: queryKeys.cpus(networkId ?? '', locale),
    queryFn: ({ signal }) => crafting.fetchCpus(networkId ?? '', locale, signal),
    enabled: networkId !== undefined,
    refetchInterval: craftingRefreshMs(live),
  });
}

export function useOrders(networkId: string, filter: crafting.OrderFilter, locale: string, live: boolean) {
  return useInfiniteQuery({
    queryKey: queryKeys.orderList(networkId, filter, locale),
    queryFn: ({ pageParam, signal }) => crafting.fetchOrders(networkId, filter, locale, pageParam, signal),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor,
    refetchInterval: filter === 'ACTIVE' ? craftingRefreshMs(live) : false,
  });
}

export function useOrder(networkId: string, orderId: string | null, locale: string) {
  return useQuery({
    queryKey: queryKeys.order(networkId, orderId ?? '', locale),
    queryFn: ({ signal }) => crafting.fetchOrder(networkId, orderId ?? '', locale, signal),
    enabled: orderId !== null,
  });
}

/** A plan being calculated is polled until it is ready or failed. */
export function usePlan(networkId: string, planId: string | null, locale: string) {
  return useQuery({
    queryKey: queryKeys.plan(networkId, planId ?? '', locale),
    queryFn: ({ signal }) => crafting.fetchPlan(networkId, planId ?? '', locale, signal),
    enabled: planId !== null,
    refetchInterval: (query) => (query.state.data?.state === 'CALCULATING' ? 1_000 : false),
    staleTime: Infinity,
  });
}

export function useCraftingMutations(networkId: string, locale: string) {
  const client = useQueryClient();
  const refresh = () => {
    void client.invalidateQueries({ queryKey: queryKeys.crafting(networkId) });
    void client.invalidateQueries({ queryKey: queryKeys.network(networkId) });
  };
  return {
    calculate: useMutation({
      mutationFn: ({ resourceId, amount }: { resourceId: string; amount: number }) =>
        crafting.calculatePlan(networkId, resourceId, amount, locale),
      onSuccess: (plan) => client.setQueryData(queryKeys.plan(networkId, plan.id, locale), plan),
    }),
    submit: useMutation({
      mutationFn: ({ planId, cpuId }: { planId: string; cpuId: string | null }) =>
        crafting.submitPlan(networkId, planId, cpuId, locale),
      onSettled: refresh,
    }),
    cancelOrder: useMutation({
      mutationFn: (orderId: string) => crafting.cancelOrder(networkId, orderId, locale),
      onSettled: refresh,
    }),
    cancelCpuJob: useMutation({
      mutationFn: ({ cpuId, jobId }: { cpuId: string; jobId: string | null }) =>
        crafting.cancelCpuJob(networkId, cpuId, jobId),
      onSettled: refresh,
    }),
  };
}
