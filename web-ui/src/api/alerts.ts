import { z } from 'zod';
import { getJson, sendJson, sendNoContent } from './client';
import { resourceLabelSchema } from './crafting';

/** Mirrors io.github.codaaaaaa.mecc.core.alerts.AlertViews (spec section 23). */
export const ALERT_TYPES = [
  'RESOURCE_BELOW',
  'RESOURCE_ABOVE',
  'RESOURCE_DROP',
  'RESOURCE_RISE',
  'NETWORK_OFFLINE',
  'ENERGY_LOW',
  'CPU_SATURATED',
  'CRAFT_COMPLETED',
  'CRAFT_FAILED',
  'CRAFT_STALLED',
  'MACHINE_STUCK',
] as const;
export type AlertType = (typeof ALERT_TYPES)[number];

/** What each type asks for; mirrors AlertType on the server. */
export type ThresholdKind = 'amount' | 'percent' | 'minutes';

export const ALERT_SHAPE: Record<AlertType, {
  resource: 'required' | 'optional' | 'none';
  threshold: ThresholdKind | null;
  /** Compared with the amount this many minutes ago (percentage-change rules). */
  window: boolean;
  /** Condition rules have a cooldown; per-order craft rules report every order. */
  cooldown: boolean;
}> = {
  RESOURCE_BELOW: { resource: 'required', threshold: 'amount', window: false, cooldown: true },
  RESOURCE_ABOVE: { resource: 'required', threshold: 'amount', window: false, cooldown: true },
  RESOURCE_DROP: { resource: 'required', threshold: 'percent', window: true, cooldown: true },
  RESOURCE_RISE: { resource: 'required', threshold: 'percent', window: true, cooldown: true },
  NETWORK_OFFLINE: { resource: 'none', threshold: null, window: false, cooldown: true },
  ENERGY_LOW: { resource: 'none', threshold: 'percent', window: false, cooldown: true },
  CPU_SATURATED: { resource: 'none', threshold: null, window: false, cooldown: true },
  CRAFT_COMPLETED: { resource: 'optional', threshold: null, window: false, cooldown: false },
  CRAFT_FAILED: { resource: 'optional', threshold: null, window: false, cooldown: false },
  CRAFT_STALLED: { resource: 'optional', threshold: 'minutes', window: false, cooldown: false },
  MACHINE_STUCK: { resource: 'optional', threshold: 'minutes', window: false, cooldown: false },
};

/** Events report a percent (change or energy) for percentage rules, else an amount. */
function valueIsPercent(type: AlertType): boolean {
  return ALERT_SHAPE[type].threshold === 'percent';
}

export const alertRuleSchema = z.object({
  id: z.string(),
  networkId: z.string(),
  type: z.enum(ALERT_TYPES),
  resource: resourceLabelSchema.nullable(),
  /** Raw amount, percent, or minutes, as ALERT_SHAPE says. */
  threshold: z.number().nullable(),
  windowMinutes: z.number().nullable(),
  cooldownMinutes: z.number(),
  enabled: z.boolean(),
  /** The condition holds right now. */
  active: z.boolean(),
  notifiedAt: z.string().nullable(),
  createdAt: z.string(),
});
export type AlertRule = z.infer<typeof alertRuleSchema>;

export const alertRuleListSchema = z.object({
  rules: z.array(alertRuleSchema),
  limit: z.number(),
  assetVersion: z.string(),
});
export type AlertRuleList = z.infer<typeof alertRuleListSchema>;

export const alertEventSchema = z.object({
  id: z.number(),
  ruleId: z.string(),
  networkId: z.string(),
  networkName: z.string().nullable(),
  type: z.enum(ALERT_TYPES),
  kind: z.enum(['TRIGGERED', 'RESOLVED']),
  at: z.string(),
  resource: resourceLabelSchema.nullable(),
  value: z.number().nullable(),
  threshold: z.number().nullable(),
  orderId: z.string().nullable(),
});
export type AlertEvent = z.infer<typeof alertEventSchema>;

export const alertEventPageSchema = z.object({
  events: z.array(alertEventSchema),
  nextBefore: z.number().nullable(),
  assetVersion: z.string(),
});
export type AlertEventPage = z.infer<typeof alertEventPageSchema>;

export const alertSettingsSchema = z.object({
  discordWebhookUrl: z.string().nullable(),
  webhookUrl: z.string().nullable(),
  webhooksEnabled: z.boolean(),
});
export type AlertSettings = z.infer<typeof alertSettingsSchema>;

export const testResultSchema = z.object({ channels: z.record(z.string(), z.string()) });

export interface RuleInput {
  type: AlertType;
  resourceId: string | null;
  threshold: number | null;
  windowMinutes: number | null;
  cooldownMinutes: number;
}

export function fetchRules(networkId: string, locale: string, signal?: AbortSignal): Promise<AlertRuleList> {
  const parameters = new URLSearchParams({ networkId, locale });
  return getJson(`/api/v1/alerts/rules?${parameters}`, alertRuleListSchema, signal);
}

export function createRule(networkId: string, input: RuleInput, locale: string): Promise<AlertRule> {
  return sendJson('POST', '/api/v1/alerts/rules', { networkId, ...input, locale }, alertRuleSchema);
}

export function updateRule(
  id: string,
  change: Partial<Pick<AlertRule, 'enabled' | 'threshold' | 'windowMinutes' | 'cooldownMinutes'>>,
  locale: string,
): Promise<AlertRule> {
  return sendJson('PATCH', `/api/v1/alerts/rules/${encodeURIComponent(id)}`, { ...change, locale }, alertRuleSchema);
}

export function deleteRule(id: string): Promise<void> {
  return sendNoContent('DELETE', `/api/v1/alerts/rules/${encodeURIComponent(id)}`);
}

export function fetchEvents(networkId: string | null, locale: string, before: number | null, signal?: AbortSignal): Promise<AlertEventPage> {
  const parameters = new URLSearchParams({ locale, limit: '50' });
  if (networkId) parameters.set('networkId', networkId);
  if (before !== null) parameters.set('before', String(before));
  return getJson(`/api/v1/alerts?${parameters}`, alertEventPageSchema, signal);
}

export function fetchAlertSettings(signal?: AbortSignal): Promise<AlertSettings> {
  return getJson('/api/v1/alerts/settings', alertSettingsSchema, signal);
}

export function saveAlertSettings(discordWebhookUrl: string, webhookUrl: string, locale: string): Promise<AlertSettings> {
  return sendJson('PATCH', '/api/v1/alerts/settings', { discordWebhookUrl, webhookUrl, locale }, alertSettingsSchema);
}

export function testAlertChannels(): Promise<Record<string, string>> {
  return sendJson('POST', '/api/v1/alerts/test', {}, testResultSchema).then((result) => result.channels);
}

/** i18n key and values describing an event, e.g. for a browser notification. */
export function eventMessage(event: AlertEvent, formatAmount: (raw: number) => string): { key: string; values: Record<string, string> } {
  const shown = (value: number | null, plain: boolean) => (value === null ? '?' : plain ? `${value}` : formatAmount(value));
  return {
    key: `alerts.message.${event.type}.${event.kind}`,
    values: {
      resource: event.resource?.name ?? '',
      value: shown(event.value, valueIsPercent(event.type)),
      threshold: shown(event.threshold, ALERT_SHAPE[event.type].threshold !== 'amount'),
    },
  };
}
