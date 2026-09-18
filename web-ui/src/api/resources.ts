import { z } from 'zod';
import { getJson } from './client';

/** Mirrors io.github.codaaaaaa.mecc.core.resources.ResourceViews. */
export const SORTS = ['NAME', 'AMOUNT', 'MOD', 'CRAFTABLE'] as const;
export const TYPE_FILTERS = ['ALL', 'ITEM', 'FLUID', 'OTHER'] as const;
export type Sort = (typeof SORTS)[number];
export type TypeFilter = (typeof TYPE_FILTERS)[number];

export const resourceUnitSchema = z.object({
  symbol: z.string(),
  amountPerUnit: z.number(),
});

/** One styled run of a display name. Colours come from the game, so they are opaque hex strings. */
export const nameSpanSchema = z.object({
  text: z.string(),
  color: z.string().nullable(),
  bold: z.boolean(),
  italic: z.boolean(),
  underlined: z.boolean(),
  strikethrough: z.boolean(),
});
export type NameSpan = z.infer<typeof nameSpanSchema>;

export const resourceSchema = z.object({
  id: z.string(),
  type: z.string(),
  name: z.string(),
  /** Present only when the name carries styling; otherwise `name` says everything. */
  nameSpans: z.array(nameSpanSchema).nullable(),
  modId: z.string(),
  modName: z.string(),
  amount: z.number(),
  craftable: z.boolean(),
  /** Amount requested by running crafting jobs, or null. */
  crafting: z.number().nullable(),
  unit: resourceUnitSchema.nullable(),
  iconKey: z.string(),
});
export type Resource = z.infer<typeof resourceSchema>;

export const resourcePageSchema = z.object({
  snapshotId: z.string(),
  capturedAt: z.string(),
  total: z.number(),
  offset: z.number(),
  entries: z.array(resourceSchema),
  assetVersion: z.string(),
});
export type ResourcePage = z.infer<typeof resourcePageSchema>;

export const resourceDetailSchema = z.object({
  resource: resourceSchema,
  registryId: z.string(),
  variant: z.string().nullable(),
  descriptionKey: z.string().nullable(),
  tags: z.array(z.string()),
  snapshotId: z.string(),
  capturedAt: z.string(),
  assetVersion: z.string(),
});
export type ResourceDetail = z.infer<typeof resourceDetailSchema>;

export interface ResourceQuery {
  networkId: string;
  search: string;
  sort: Sort;
  descending: boolean;
  type: TypeFilter;
  locale: string;
}

export function fetchResourcePage(
  query: ResourceQuery,
  offset: number,
  limit: number,
  snapshotId: string | undefined,
  signal?: AbortSignal,
): Promise<ResourcePage> {
  const parameters = new URLSearchParams({
    q: query.search,
    sort: query.sort,
    desc: String(query.descending),
    type: query.type,
    offset: String(offset),
    limit: String(limit),
    locale: query.locale,
  });
  if (snapshotId) {
    parameters.set('snapshot', snapshotId);
  }
  return getJson(
    `/api/v1/networks/${encodeURIComponent(query.networkId)}/resources?${parameters}`,
    resourcePageSchema,
    signal,
  );
}

export function fetchResourceDetail(
  networkId: string,
  resourceId: string,
  snapshotId: string | undefined,
  locale: string,
  signal?: AbortSignal,
): Promise<ResourceDetail> {
  const parameters = new URLSearchParams({ id: resourceId, locale });
  if (snapshotId) {
    parameters.set('snapshot', snapshotId);
  }
  return getJson(
    `/api/v1/networks/${encodeURIComponent(networkId)}/resources/detail?${parameters}`,
    resourceDetailSchema,
    signal,
  );
}

/** Icon URL. The asset version makes it safe for the browser to cache aggressively. */
export function iconUrl(iconKey: string, assetVersion: string): string {
  return `/api/v1/icons?key=${encodeURIComponent(iconKey)}&v=${encodeURIComponent(assetVersion)}`;
}
