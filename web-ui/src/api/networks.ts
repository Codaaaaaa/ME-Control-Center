import { z } from 'zod';
import { userViewSchema } from './auth';
import { getJson, sendJson, sendNoContent } from './client';

/** Mirrors io.github.codaaaaaa.mecc.core.networks.NetworkViews. */
export const ROLES = ['VIEWER', 'OPERATOR', 'MANAGER', 'OWNER'] as const;
export const roleSchema = z.enum(ROLES);
export type Role = z.infer<typeof roleSchema>;

export const networkStateSchema = z.enum(['ONLINE', 'DEGRADED', 'OFFLINE', 'CONFLICT']);
export type NetworkState = z.infer<typeof networkStateSchema>;

export const stateReasonSchema = z.enum([
  'NOT_DISCOVERED_YET',
  'NOT_LOADED',
  'UNPOWERED',
  'BOOTING',
  'CONTROLLER_CONFLICT',
  'SPLIT',
  'MERGED',
]);
export type StateReason = z.infer<typeof stateReasonSchema>;

export const networkSummarySchema = z.object({
  id: z.string(),
  displayName: z.string(),
  owner: userViewSchema,
  role: roleSchema,
  adminOverride: z.boolean(),
  state: networkStateSchema,
  stateReason: stateReasonSchema.nullable(),
  lastSeenAt: z.string().nullable(),
  createdAt: z.string(),
});
export type NetworkSummary = z.infer<typeof networkSummarySchema>;

/** Nullable fields are optional capabilities: null means "not reliably available", never zero. */
export const gridStatusSchema = z.object({
  powered: z.boolean(),
  booting: z.boolean(),
  controllerState: z.string().nullable(),
  channelMode: z.string().nullable(),
  storedEnergy: z.number().nullable(),
  energyCapacity: z.number().nullable(),
  averageEnergyUsage: z.number().nullable(),
  averageEnergyInjection: z.number().nullable(),
  usedChannels: z.number().nullable(),
  nodeCount: z.number(),
  storedResourceTypes: z.number().nullable(),
  craftingCpus: z.number().nullable(),
  busyCraftingCpus: z.number().nullable(),
  patternProviders: z.number().nullable(),
});
export type GridStatus = z.infer<typeof gridStatusSchema>;

export const anchorSchema = z.object({
  key: z.string(),
  dimension: z.string(),
  x: z.number(),
  y: z.number(),
  z: z.number(),
  owner: userViewSchema.nullable(),
  active: z.boolean().nullable(),
});
export type Anchor = z.infer<typeof anchorSchema>;

export const networkDetailSchema = z.object({
  network: networkSummarySchema,
  status: gridStatusSchema.nullable(),
  statusCapturedAt: z.string().nullable(),
  anchors: z.array(anchorSchema),
  capabilities: z.array(z.string()),
});
export type NetworkDetail = z.infer<typeof networkDetailSchema>;

export const candidateSchema = z.object({
  key: z.string(),
  anchors: z.array(anchorSchema),
  ownedByYou: z.boolean(),
  powered: z.boolean(),
  nodeCount: z.number(),
});
export type Candidate = z.infer<typeof candidateSchema>;

export const memberSchema = z.object({
  user: userViewSchema,
  role: roleSchema,
  primaryOwner: z.boolean(),
  addedAt: z.string(),
  addedBy: userViewSchema.nullable(),
});
export type Member = z.infer<typeof memberSchema>;

const base = (id: string) => `/api/v1/networks/${encodeURIComponent(id)}`;

export function fetchNetworks(signal?: AbortSignal): Promise<NetworkSummary[]> {
  return getJson('/api/v1/networks', z.object({ networks: z.array(networkSummarySchema) }), signal).then((r) => r.networks);
}

export function fetchNetwork(id: string, signal?: AbortSignal): Promise<NetworkDetail> {
  return getJson(base(id), networkDetailSchema, signal);
}

export function fetchCandidates(signal?: AbortSignal): Promise<Candidate[]> {
  return getJson('/api/v1/networks/candidates', z.object({ candidates: z.array(candidateSchema) }), signal).then(
    (r) => r.candidates,
  );
}

export function claimNetwork(candidateKey: string, displayName: string): Promise<NetworkDetail> {
  return sendJson('POST', '/api/v1/networks', { candidateKey, displayName }, networkDetailSchema);
}

export function renameNetwork(id: string, name: string): Promise<NetworkSummary> {
  return sendJson('PATCH', base(id), { name }, networkSummarySchema);
}

export function deleteNetwork(id: string): Promise<void> {
  return sendNoContent('DELETE', base(id));
}

export function fetchMembers(id: string, signal?: AbortSignal): Promise<Member[]> {
  return getJson(`${base(id)}/members`, z.object({ members: z.array(memberSchema) }), signal).then((r) => r.members);
}

export function addMember(id: string, player: string, role: Role): Promise<Member> {
  return sendJson('POST', `${base(id)}/members`, { player, role }, memberSchema);
}

export function changeMemberRole(id: string, playerUuid: string, role: Role): Promise<Member> {
  return sendJson('PATCH', `${base(id)}/members/${encodeURIComponent(playerUuid)}`, { role }, memberSchema);
}

export function removeMember(id: string, playerUuid: string): Promise<void> {
  return sendNoContent('DELETE', `${base(id)}/members/${encodeURIComponent(playerUuid)}`);
}

export function can(detail: NetworkDetail | undefined, capability: string): boolean {
  return detail?.capabilities.includes(capability) ?? false;
}
