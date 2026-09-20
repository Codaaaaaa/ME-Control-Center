import { z } from 'zod';
import { userViewSchema } from './auth';
import { getJson, sendJson, sendNoContent } from './client';
import { nameSpanSchema, resourceUnitSchema } from './resources';

/** Mirrors io.github.codaaaaaa.mecc.core.crafting.CraftingViews. */

/** A referenced resource: enough to show its icon and name. */
export const resourceLabelSchema = z.object({
  id: z.string(),
  type: z.string(),
  name: z.string(),
  nameSpans: z.array(nameSpanSchema).nullable(),
  modId: z.string(),
  modName: z.string(),
  unit: resourceUnitSchema.nullable(),
  iconKey: z.string(),
});
export type ResourceLabel = z.infer<typeof resourceLabelSchema>;

/** Never a fabricated percentage: `percent` is null when there is no meaningful denominator. */
export const progressSchema = z.object({
  completed: z.number().nullable(),
  remaining: z.number().nullable(),
  requested: z.number(),
  percent: z.number().nullable(),
  confidence: z.enum(['AUTHORITATIVE', 'ESTIMATED', 'NONE']),
});
export type Progress = z.infer<typeof progressSchema>;

export const locationSchema = z.object({
  dimension: z.string(),
  x: z.number(),
  y: z.number(),
  z: z.number(),
});

export const cpuJobSchema = z.object({
  jobId: z.string().nullable(),
  output: resourceLabelSchema,
  amount: z.number(),
  progress: progressSchema,
  elapsedMillis: z.number().nullable(),
  orderId: z.string().nullable(),
  initiator: userViewSchema.nullable(),
  /** WEB: an ME Control Center order; IN_GAME: requested in game by `initiator`; UNKNOWN: a machine or unrecorded. */
  origin: z.enum(['WEB', 'IN_GAME', 'UNKNOWN']),
  /** Whether the initiator has paired a browser. */
  paired: z.boolean(),
  cancellable: z.boolean(),
});
export type CpuJob = z.infer<typeof cpuJobSchema>;

// --- Crafting tree of a running job (spec section 12) ---------------------------------------------------

export const STEP_STATUSES = ['CRAFTING', 'WAITING_MACHINE', 'READY', 'WAITING_INPUTS', 'DONE', 'FROM_STORAGE'] as const;
export type StepStatus = (typeof STEP_STATUSES)[number];

export interface Step {
  id: string;
  resource: ResourceLabel;
  perRun: number;
  runs: number;
  remaining: number;
  status: StepStatus;
  inMachines: number;
  stored: number;
  /** Machines holding this pattern; join with the machines endpoint for their status. */
  machineIds: string[];
  children: Step[];
}

export const stepSchema: z.ZodType<Step> = z.lazy(() => z.object({
  id: z.string(),
  resource: resourceLabelSchema,
  perRun: z.number(),
  runs: z.number(),
  remaining: z.number(),
  status: z.enum(STEP_STATUSES),
  inMachines: z.number(),
  stored: z.number(),
  machineIds: z.array(z.string()),
  children: z.array(stepSchema),
}));

export const jobTreeSchema = z.object({
  cpuId: z.string(),
  jobId: z.string().nullable(),
  output: resourceLabelSchema,
  amount: z.number(),
  elapsedMillis: z.number().nullable(),
  /** First seen after the job started: steps that finished before are missing. */
  partial: z.boolean(),
  root: stepSchema,
  counts: z.partialRecord(z.enum(STEP_STATUSES), z.number()),
  assetVersion: z.string(),
});
export type JobTree = z.infer<typeof jobTreeSchema>;

export function fetchJobTree(networkId: string, cpuId: string, locale: string, signal?: AbortSignal): Promise<JobTree> {
  return getJson(`${base(networkId)}/cpus/${encodeURIComponent(cpuId)}/tree?locale=${encodeURIComponent(locale)}`,
    jobTreeSchema, signal);
}

/** Steps worth showing: everything, or only what is still going on (finished branches and ingredients hidden). */
export function visibleSteps(step: Step, hideFinished: boolean): Step | null {
  if (!hideFinished) return step;
  if (step.status === 'DONE' || step.status === 'FROM_STORAGE') return null;
  return { ...step, children: step.children.map((child) => visibleSteps(child, true)).filter((child): child is Step => child !== null) };
}

/** Steps being worked on or blocked, one per pattern, for the "what is happening now" list. */
export function activeSteps(root: Step): Step[] {
  const seen = new Map<string, Step>();
  const walk = (step: Step) => {
    if (['CRAFTING', 'WAITING_MACHINE', 'READY'].includes(step.status) && !seen.has(step.id)) seen.set(step.id, step);
    step.children.forEach(walk);
  };
  walk(root);
  const order: StepStatus[] = ['WAITING_MACHINE', 'CRAFTING', 'READY'];
  return [...seen.values()].sort((a, b) => order.indexOf(a.status) - order.indexOf(b.status));
}

export const cpuSchema = z.object({
  id: z.string(),
  name: z.string().nullable(),
  location: locationSchema.nullable(),
  busy: z.boolean(),
  online: z.boolean().nullable(),
  storageBytes: z.number(),
  coProcessors: z.number(),
  selectionMode: z.string(),
  job: cpuJobSchema.nullable(),
});
export type Cpu = z.infer<typeof cpuSchema>;

export const cpuListSchema = z.object({
  capturedAt: z.string(),
  cpus: z.array(cpuSchema),
  assetVersion: z.string(),
});
export type CpuList = z.infer<typeof cpuListSchema>;

export const PLAN_STATES = ['CALCULATING', 'READY', 'FAILED'] as const;
export const UNSUITABLE_REASONS = ['BUSY', 'OFFLINE', 'TOO_SMALL', 'EXCLUDED'] as const;

export const planSchema = z.object({
  id: z.string(),
  state: z.enum(PLAN_STATES),
  networkId: z.string(),
  output: resourceLabelSchema.nullable(),
  requestedAmount: z.number(),
  amount: z.number().nullable(),
  complete: z.boolean().nullable(),
  bytes: z.number().nullable(),
  multiplePaths: z.boolean(),
  entries: z.array(
    z.object({
      resource: resourceLabelSchema,
      stored: z.number(),
      toCraft: z.number(),
      missing: z.number(),
    }),
  ),
  totalEntries: z.number(),
  cpus: z.array(
    z.object({
      id: z.string(),
      name: z.string().nullable(),
      busy: z.boolean(),
      online: z.boolean().nullable(),
      storageBytes: z.number(),
      coProcessors: z.number(),
      selectionMode: z.string(),
      reason: z.enum(UNSUITABLE_REASONS).nullable(),
    }),
  ),
  errorCode: z.string().nullable(),
  errorMessage: z.string().nullable(),
  createdAt: z.string(),
  expiresAt: z.string(),
  assetVersion: z.string(),
});
export type Plan = z.infer<typeof planSchema>;

export const ORDER_STATES = ['SUBMITTING', 'RUNNING', 'COMPLETED', 'CANCELLED', 'FAILED', 'UNKNOWN'] as const;
export type OrderState = (typeof ORDER_STATES)[number];
export const ORDER_FILTERS = ['ACTIVE', 'COMPLETED', 'FAILED', 'CANCELLED'] as const;
export type OrderFilter = (typeof ORDER_FILTERS)[number];

export const orderSchema = z.object({
  id: z.string(),
  networkId: z.string(),
  creator: userViewSchema,
  target: resourceLabelSchema,
  amount: z.number(),
  state: z.enum(ORDER_STATES),
  source: z.string(),
  createdAt: z.string(),
  startedAt: z.string().nullable(),
  endedAt: z.string().nullable(),
  cpu: z.object({ id: z.string(), name: z.string().nullable() }).nullable(),
  progress: progressSchema.nullable(),
  elapsedMillis: z.number().nullable(),
  failure: z.object({ code: z.string(), message: z.string().nullable() }).nullable(),
  cancellable: z.boolean(),
  observed: z.boolean(),
  lastObservedAt: z.string().nullable(),
});
export type Order = z.infer<typeof orderSchema>;

export const orderPageSchema = z.object({
  orders: z.array(orderSchema),
  nextCursor: z.string().nullable(),
  assetVersion: z.string(),
});
export type OrderPage = z.infer<typeof orderPageSchema>;

export const orderDetailSchema = z.object({
  order: orderSchema,
  events: z.array(
    z.object({
      at: z.string(),
      type: z.string(),
      actor: userViewSchema.nullable(),
      details: z.record(z.string(), z.string()),
    }),
  ),
  assetVersion: z.string(),
});
export type OrderDetail = z.infer<typeof orderDetailSchema>;

/** Which tab an order belongs in. */
export function orderFilterOf(state: OrderState): OrderFilter {
  switch (state) {
    case 'SUBMITTING':
    case 'RUNNING':
      return 'ACTIVE';
    case 'COMPLETED':
      return 'COMPLETED';
    case 'CANCELLED':
      return 'CANCELLED';
    default:
      return 'FAILED';
  }
}

const base = (networkId: string) => `/api/v1/networks/${encodeURIComponent(networkId)}/crafting`;

export function fetchCpus(networkId: string, locale: string, signal?: AbortSignal): Promise<CpuList> {
  return getJson(`${base(networkId)}/cpus?locale=${encodeURIComponent(locale)}`, cpuListSchema, signal);
}

export function cancelCpuJob(networkId: string, cpuId: string, jobId: string | null): Promise<void> {
  return sendNoContent('POST', `${base(networkId)}/cpus/${encodeURIComponent(cpuId)}/cancel`, { jobId });
}

export function calculatePlan(networkId: string, resourceId: string, amount: number, locale: string): Promise<Plan> {
  return sendJson('POST', `${base(networkId)}/plan`, { resourceId, amount, locale }, planSchema);
}

export function fetchPlan(networkId: string, planId: string, locale: string, signal?: AbortSignal): Promise<Plan> {
  return getJson(`${base(networkId)}/plans/${encodeURIComponent(planId)}?locale=${encodeURIComponent(locale)}`, planSchema, signal);
}

/** `source` is `SAVED_ORDER` when the player ran a saved order (spec section 24). */
export function submitPlan(
  networkId: string,
  planId: string,
  cpuId: string | null,
  locale: string,
  source: 'MANUAL' | 'SAVED_ORDER' = 'MANUAL',
): Promise<Order> {
  return sendJson('POST', `${base(networkId)}/orders`, { planId, cpuId, source, locale }, orderSchema);
}

// --- Saved Craft Orders (spec section 24) -------------------------------------------------------------

export const savedOrderSchema = z.object({
  id: z.string(),
  networkId: z.string(),
  name: z.string(),
  target: resourceLabelSchema,
  /** Raw, like order amounts. */
  amount: z.number(),
  /** Preferred CPU, or null for automatic. */
  cpuId: z.string().nullable(),
  notes: z.string(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type SavedOrder = z.infer<typeof savedOrderSchema>;

export const savedOrderListSchema = z.object({
  orders: z.array(savedOrderSchema),
  limit: z.number(),
  assetVersion: z.string(),
});
export type SavedOrderList = z.infer<typeof savedOrderListSchema>;

export interface SavedOrderInput {
  name: string;
  resourceId: string;
  amount: number;
  cpuId: string | null;
  notes: string;
}

export function fetchSavedOrders(networkId: string, locale: string, signal?: AbortSignal): Promise<SavedOrderList> {
  return getJson(`${base(networkId)}/saved-orders?locale=${encodeURIComponent(locale)}`, savedOrderListSchema, signal);
}

export function createSavedOrder(networkId: string, input: SavedOrderInput, locale: string): Promise<SavedOrder> {
  return sendJson('POST', `${base(networkId)}/saved-orders`, { ...input, locale }, savedOrderSchema);
}

export function updateSavedOrder(networkId: string, id: string, input: SavedOrderInput, locale: string): Promise<SavedOrder> {
  return sendJson('PATCH', `${base(networkId)}/saved-orders/${encodeURIComponent(id)}`, { ...input, locale }, savedOrderSchema);
}

export function deleteSavedOrder(networkId: string, id: string): Promise<void> {
  return sendNoContent('DELETE', `${base(networkId)}/saved-orders/${encodeURIComponent(id)}`);
}

export function fetchOrders(
  networkId: string,
  filter: OrderFilter,
  locale: string,
  before: string | null,
  signal?: AbortSignal,
): Promise<OrderPage> {
  const parameters = new URLSearchParams({ status: filter, locale, limit: '30' });
  if (before) parameters.set('before', before);
  return getJson(`${base(networkId)}/orders?${parameters}`, orderPageSchema, signal);
}

export function fetchOrder(networkId: string, orderId: string, locale: string, signal?: AbortSignal): Promise<OrderDetail> {
  return getJson(`${base(networkId)}/orders/${encodeURIComponent(orderId)}?locale=${encodeURIComponent(locale)}`,
    orderDetailSchema, signal);
}

export function cancelOrder(networkId: string, orderId: string, locale: string): Promise<Order> {
  return sendJson('POST', `${base(networkId)}/orders/${encodeURIComponent(orderId)}/cancel?locale=${encodeURIComponent(locale)}`,
    {}, orderSchema);
}
