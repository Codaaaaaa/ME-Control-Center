import { z } from 'zod';
import { getJson, sendJson, sendNoContent } from './client';
import { locationSchema, resourceLabelSchema } from './crafting';

/** Mirrors io.github.codaaaaaa.mecc.core.automation.RestockViews (spec section 25). */
export const restockRuleSchema = z.object({
  id: z.string(),
  networkId: z.string(),
  /** The player the crafting jobs are requested as. */
  createdBy: z.string().nullable(),
  resource: resourceLabelSchema,
  minimum: z.number(),
  restockTo: z.number(),
  cpuId: z.string().nullable(),
  cooldownMinutes: z.number(),
  enabled: z.boolean(),
  /** In storage right now, or null when the network could not be read. */
  stored: z.number().nullable(),
  lastRunAt: z.string().nullable(),
  lastOrderId: z.string().nullable(),
  failures: z.number(),
  pausedUntil: z.string().nullable(),
  lastError: z.string().nullable(),
  createdAt: z.string(),
});
export type RestockRule = z.infer<typeof restockRuleSchema>;

export const restockRuleListSchema = z.object({
  rules: z.array(restockRuleSchema),
  limit: z.number(),
  /** automation.auto_restock_enabled: without it no rule ever submits. */
  serverEnabled: z.boolean(),
  maxActiveJobs: z.number(),
  assetVersion: z.string(),
});
export type RestockRuleList = z.infer<typeof restockRuleListSchema>;

export interface RestockInput {
  resourceId?: string;
  minimum?: number;
  restockTo?: number;
  cpuId?: string | null;
  cooldownMinutes?: number;
  enabled?: boolean;
}

function base(networkId: string): string {
  return `/api/v1/networks/${encodeURIComponent(networkId)}/automation`;
}

export function fetchRestockRules(networkId: string, locale: string, signal?: AbortSignal): Promise<RestockRuleList> {
  return getJson(`${base(networkId)}/restock?locale=${encodeURIComponent(locale)}`, restockRuleListSchema, signal);
}

export function createRestockRule(networkId: string, input: RestockInput, locale: string): Promise<RestockRule> {
  return sendJson('POST', `${base(networkId)}/restock`, { ...input, locale }, restockRuleSchema);
}

export function updateRestockRule(
  networkId: string,
  id: string,
  input: RestockInput,
  locale: string,
): Promise<RestockRule> {
  return sendJson('PATCH', `${base(networkId)}/restock/${encodeURIComponent(id)}`, { ...input, locale }, restockRuleSchema);
}

export function deleteRestockRule(networkId: string, id: string): Promise<void> {
  return sendNoContent('DELETE', `${base(networkId)}/restock/${encodeURIComponent(id)}`);
}

/** Kill switch (spec section 25): turns every rule of the network off at once. */
export function stopAutomation(networkId: string, locale: string): Promise<RestockRuleList> {
  return sendJson('POST', `${base(networkId)}/stop`, { locale }, restockRuleListSchema);
}

/** One request slot of an in-game ME Requester; mirrors RestockViews.RequesterRequestView. */
export const requesterRequestSchema = z.object({
  slot: z.number(),
  resource: resourceLabelSchema,
  /** The stock level the requester keeps. */
  amount: z.number(),
  /** How much it asks for at a time. */
  batch: z.number(),
  enabled: z.boolean(),
  /** IDLE, MISSING, LINK, ... or null when the mod does not say. */
  status: z.string().nullable(),
  /** What the requester last saw in the network, or null. */
  stored: z.number().nullable(),
});
export type RequesterRequest = z.infer<typeof requesterRequestSchema>;

export const requesterSchema = z.object({
  id: z.string(),
  name: z.string().nullable(),
  location: locationSchema.nullable(),
  online: z.boolean(),
  requests: z.array(requesterRequestSchema),
});
export type Requester = z.infer<typeof requesterSchema>;

export const requesterListSchema = z.object({
  requesters: z.array(requesterSchema),
  /** False when the server has no ME Requester mod, or the network is offline. */
  supported: z.boolean(),
  assetVersion: z.string(),
});
export type RequesterList = z.infer<typeof requesterListSchema>;

export function fetchRequesters(networkId: string, locale: string, signal?: AbortSignal): Promise<RequesterList> {
  return getJson(`${base(networkId)}/requesters?locale=${encodeURIComponent(locale)}`, requesterListSchema, signal);
}

/** Empties one request slot, as taking its request out in game would. */
export function deleteRequesterRequest(networkId: string, requesterId: string, slot: number): Promise<void> {
  return sendNoContent('DELETE', `${base(networkId)}/requesters/${encodeURIComponent(requesterId)}/${slot}`);
}
