import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import * as admin from './admin';
import * as alerts from './alerts';
import * as auth from './auth';
import * as crafting from './crafting';
import * as insights from './insights';
import * as networks from './networks';
import * as patterns from './patterns';

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
  savedOrders: (networkId: string, locale: string) => ['crafting', networkId, 'saved', locale] as const,
  insights: (networkId: string) => ['insights', networkId] as const,
  watchlist: (networkId: string, locale: string) => ['insights', networkId, 'watchlist', locale] as const,
  series: (networkId: string, range: insights.Range, resourceId: string | undefined) =>
    ['insights', networkId, 'series', range, resourceId ?? null] as const,
  admin: ['admin'] as const,
  auditLog: (networkId: string | null) => ['audit', networkId] as const,
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
      mutationFn: ({ planId, cpuId, source }: { planId: string; cpuId: string | null; source?: 'MANUAL' | 'SAVED_ORDER' }) =>
        crafting.submitPlan(networkId, planId, cpuId, locale, source),
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

export function useSavedOrders(networkId: string, locale: string) {
  return useQuery({
    queryKey: queryKeys.savedOrders(networkId, locale),
    queryFn: ({ signal }) => crafting.fetchSavedOrders(networkId, locale, signal),
  });
}

export function useSavedOrderMutations(networkId: string, locale: string) {
  const client = useQueryClient();
  const refresh = () => void client.invalidateQueries({ queryKey: ['crafting', networkId, 'saved'] });
  return {
    create: useMutation({
      mutationFn: (input: crafting.SavedOrderInput) => crafting.createSavedOrder(networkId, input, locale),
      onSuccess: refresh,
    }),
    update: useMutation({
      mutationFn: ({ id, input }: { id: string; input: crafting.SavedOrderInput }) =>
        crafting.updateSavedOrder(networkId, id, input, locale),
      onSuccess: refresh,
    }),
    remove: useMutation({ mutationFn: (id: string) => crafting.deleteSavedOrder(networkId, id), onSuccess: refresh }),
  };
}

// --- Pattern Studio (spec sections 13-17) -------------------------------------------------------------

export const patternKeys = {
  drafts: (locale: string) => ['patterns', 'drafts', locale] as const,
  allDrafts: ['patterns', 'drafts'] as const,
  network: (networkId: string) => ['patterns', 'network', networkId] as const,
  providers: (networkId: string, locale: string) => ['patterns', 'network', networkId, 'providers', locale] as const,
  deployments: (networkId: string, locale: string) => ['patterns', 'network', networkId, 'deployments', locale] as const,
  validation: (networkId: string, body: string, locale: string) =>
    ['patterns', 'network', networkId, 'validate', body, locale] as const,
  recipes: (type: patterns.PatternType, by: string, locale: string) => ['patterns', 'recipes', type, by, locale] as const,
};

export function useDrafts(locale: string) {
  return useQuery({ queryKey: patternKeys.drafts(locale), queryFn: ({ signal }) => patterns.fetchDrafts(locale, signal) });
}

export function useProviders(networkId: string | undefined, locale: string) {
  return useQuery({
    queryKey: patternKeys.providers(networkId ?? '', locale),
    queryFn: ({ signal }) => patterns.fetchProviders(networkId ?? '', locale, signal),
    enabled: networkId !== undefined,
    refetchInterval: 15_000,
  });
}

export function useDeployments(networkId: string, locale: string) {
  return useQuery({
    queryKey: patternKeys.deployments(networkId, locale),
    queryFn: ({ signal }) => patterns.fetchDeployments(networkId, locale, signal),
  });
}

export function useDraftMutations(locale: string) {
  const client = useQueryClient();
  const refresh = () => void client.invalidateQueries({ queryKey: patternKeys.allDrafts });
  return {
    create: useMutation({
      mutationFn: (input: patterns.DraftInput) => patterns.createDraft(input, locale),
      onSuccess: refresh,
    }),
    update: useMutation({
      mutationFn: ({ id, input }: { id: string; input: patterns.DraftInput }) => patterns.updateDraft(id, input, locale),
      onSuccess: refresh,
    }),
    remove: useMutation({ mutationFn: patterns.deleteDraft, onSuccess: refresh }),
  };
}

export function usePatternMutations(networkId: string, locale: string) {
  const client = useQueryClient();
  const refresh = () => {
    void client.invalidateQueries({ queryKey: patternKeys.network(networkId) });
    // Encoding takes a Blank Pattern out of storage and may put an encoded one in.
    void client.invalidateQueries({ queryKey: ['resources'] });
  };
  return {
    encode: useMutation({
      mutationFn: ({ definition, draftId }: { definition: patterns.DefinitionBody; draftId: string | null }) =>
        patterns.encodePattern(networkId, definition, draftId, locale),
      onSettled: refresh,
    }),
    configure: useMutation({
      mutationFn: ({ providerId, settings }: { providerId: string; settings: patterns.ProviderSettings }) =>
        patterns.configureProvider(networkId, providerId, settings),
      onSettled: refresh,
    }),
    rename: useMutation({
      mutationFn: ({ providerId, name }: { providerId: string; name: string }) =>
        patterns.renameProvider(networkId, providerId, name),
      onSettled: refresh,
    }),
    deploy: useMutation({
      mutationFn: ({ definition, draftId, providerId }: {
        definition: patterns.DefinitionBody;
        draftId: string | null;
        providerId: string;
      }) => patterns.deployPattern(networkId, definition, draftId, providerId, locale),
      onSettled: refresh,
    }),
  };
}

// --- Insights (spec sections 21-22) ----------------------------------------------------------------

/** Watched amounts follow the storage snapshot; history gains a point per sampling interval (15 s by default). */
export const INSIGHTS_REFRESH_MS = 15_000;

export function useWatchlist(networkId: string | undefined, locale: string) {
  return useQuery({
    queryKey: queryKeys.watchlist(networkId ?? '', locale),
    queryFn: ({ signal }) => insights.fetchWatchlist(networkId ?? '', locale, signal),
    enabled: networkId !== undefined,
    refetchInterval: INSIGHTS_REFRESH_MS,
  });
}

export function useSeries(networkId: string, range: insights.Range, resourceId?: string) {
  return useQuery({
    queryKey: queryKeys.series(networkId, range, resourceId),
    queryFn: ({ signal }) => insights.fetchSeries(networkId, range, resourceId, signal),
    refetchInterval: INSIGHTS_REFRESH_MS,
    // Switching ranges keeps the old lines on screen until the new ones arrive.
    placeholderData: (previous) => previous,
  });
}

export function useWatchMutations(networkId: string, locale: string) {
  const client = useQueryClient();
  const refresh = () => client.invalidateQueries({ queryKey: queryKeys.insights(networkId) });
  return {
    watch: useMutation({
      mutationFn: (resourceId: string) => insights.watch(networkId, resourceId, locale),
      onSuccess: refresh,
    }),
    unwatch: useMutation({ mutationFn: insights.unwatch, onSuccess: refresh }),
  };
}

// --- Alerts (spec section 23) ----------------------------------------------------------------------

export const alertKeys = {
  all: ['alerts'] as const,
  rules: (networkId: string, locale: string) => ['alerts', 'rules', networkId, locale] as const,
  events: (networkId: string | null, locale: string) => ['alerts', 'events', networkId, locale] as const,
  settings: ['alerts', 'settings'] as const,
};

/** Rule states follow the server's checks (every 15 s by default); live events refresh sooner. */
export const ALERTS_REFRESH_MS = 30_000;

export function useAlertRules(networkId: string | undefined, locale: string) {
  return useQuery({
    queryKey: alertKeys.rules(networkId ?? '', locale),
    queryFn: ({ signal }) => alerts.fetchRules(networkId ?? '', locale, signal),
    enabled: networkId !== undefined,
    refetchInterval: ALERTS_REFRESH_MS,
  });
}

export function useAlertEvents(networkId: string | null, locale: string) {
  return useInfiniteQuery({
    queryKey: alertKeys.events(networkId, locale),
    queryFn: ({ pageParam, signal }) => alerts.fetchEvents(networkId, locale, pageParam, signal),
    initialPageParam: null as number | null,
    getNextPageParam: (page) => page.nextBefore,
    refetchInterval: ALERTS_REFRESH_MS,
  });
}

export function useAlertRuleMutations(networkId: string, locale: string) {
  const client = useQueryClient();
  const refresh = () => void client.invalidateQueries({ queryKey: alertKeys.all });
  return {
    create: useMutation({ mutationFn: (input: alerts.RuleInput) => alerts.createRule(networkId, input, locale), onSuccess: refresh }),
    update: useMutation({
      mutationFn: ({ id, change }: { id: string; change: Parameters<typeof alerts.updateRule>[1] }) =>
        alerts.updateRule(id, change, locale),
      onSuccess: refresh,
    }),
    remove: useMutation({ mutationFn: alerts.deleteRule, onSuccess: refresh }),
  };
}

export function useAlertSettings() {
  return useQuery({ queryKey: alertKeys.settings, queryFn: ({ signal }) => alerts.fetchAlertSettings(signal) });
}

export function useAlertSettingsMutations(locale: string) {
  const client = useQueryClient();
  return {
    save: useMutation({
      mutationFn: ({ discord, webhook }: { discord: string; webhook: string }) => alerts.saveAlertSettings(discord, webhook, locale),
      onSuccess: (settings) => client.setQueryData(alertKeys.settings, settings),
    }),
    test: useMutation({ mutationFn: alerts.testAlertChannels }),
  };
}

// --- Administration and audit log (milestone 6) ---------------------------------------------------

export function useAdminOverview(enabled: boolean) {
  return useQuery({ queryKey: queryKeys.admin, queryFn: ({ signal }) => admin.fetchAdminOverview(signal), enabled });
}

export function useBackup() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: admin.createBackup,
    onSettled: () => {
      void client.invalidateQueries({ queryKey: queryKeys.admin });
      void client.invalidateQueries({ queryKey: queryKeys.auditLog(null) });
    },
  });
}

export function useAuditLog(networkId: string | null) {
  return useInfiniteQuery({
    queryKey: queryKeys.auditLog(networkId),
    queryFn: ({ pageParam, signal }) => admin.fetchAuditLog(networkId, pageParam, signal),
    initialPageParam: null as number | null,
    getNextPageParam: (page) => page.nextBefore,
  });
}
