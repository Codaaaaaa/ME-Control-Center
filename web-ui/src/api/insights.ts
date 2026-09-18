import { z } from 'zod';
import { getJson, sendJson, sendNoContent } from './client';
import { resourceLabelSchema } from './crafting';

/** Mirrors io.github.codaaaaaa.mecc.core.insights.InsightsViews. */
export const RANGES = ['1h', '6h', '1d', '7d', '30d', '180d', '360d', 'max'] as const;
export type Range = (typeof RANGES)[number];
export type ChartMode = 'quantity' | 'change';

export const watchEntrySchema = z.object({
  id: z.string(),
  networkId: z.string(),
  resource: resourceLabelSchema,
  createdAt: z.string(),
  /** Null while the network cannot be read. */
  amount: z.number().nullable(),
  craftable: z.boolean().nullable(),
  crafting: z.number().nullable(),
});
export type WatchEntry = z.infer<typeof watchEntrySchema>;

export const watchlistSchema = z.object({
  entries: z.array(watchEntrySchema),
  capturedAt: z.string().nullable(),
  limit: z.number(),
  assetVersion: z.string(),
});
export type Watchlist = z.infer<typeof watchlistSchema>;

/** `[epochMillis, avg, min, max]`; a gap has null values. */
const pointSchema = z.tuple([z.number(), z.number().nullable(), z.number().nullable(), z.number().nullable()]);
export type Point = z.infer<typeof pointSchema>;

export const seriesSetSchema = z.object({
  range: z.string(),
  from: z.string(),
  to: z.string(),
  stepSeconds: z.number(),
  resolution: z.string(),
  sampling: z.boolean(),
  series: z.array(z.object({ entryId: z.string(), resourceId: z.string(), points: z.array(pointSchema) })),
});
export type SeriesSet = z.infer<typeof seriesSetSchema>;

export function fetchWatchlist(networkId: string, locale: string, signal?: AbortSignal): Promise<Watchlist> {
  const parameters = new URLSearchParams({ networkId, locale });
  return getJson(`/api/v1/watchlist?${parameters}`, watchlistSchema, signal);
}

export function watch(networkId: string, resourceId: string, locale: string): Promise<WatchEntry> {
  return sendJson('POST', '/api/v1/watchlist', { networkId, resourceId, locale }, watchEntrySchema);
}

export function unwatch(entryId: string): Promise<void> {
  return sendNoContent('DELETE', `/api/v1/watchlist/${encodeURIComponent(entryId)}`);
}

export function fetchSeries(networkId: string, range: Range, resourceId: string | undefined, signal?: AbortSignal): Promise<SeriesSet> {
  const parameters = new URLSearchParams({ networkId, range });
  if (resourceId) {
    parameters.set('resource', resourceId);
  }
  return getJson(`/api/v1/insights/series?${parameters}`, seriesSetSchema, signal);
}

/**
 * Change % over the visible range (spec section 21.4): `(value - first) / first * 100`, where `first` is the
 * first valid value. Gaps stay gaps. When the range starts at zero there is no meaningful percentage, so the
 * result is `null` and the UI says why instead of dividing by zero.
 */
export function changePercent(points: readonly Point[]): Point[] | null {
  const first = points.find((point) => point[1] !== null)?.[1];
  if (first === undefined || first === null) return [];
  if (first === 0) return null;
  const percent = (value: number | null) => (value === null ? null : ((value - first) / first) * 100);
  return points.map(([at, avg, min, max]) => [at, percent(avg), percent(min), percent(max)]);
}

/** Percent change between the first and last valid value, or null when there is none. */
export function overallChange(points: readonly Point[]): number | null {
  const change = changePercent(points);
  if (!change) return null;
  const last = [...change].reverse().find((point) => point[1] !== null);
  return last ? last[1] : null;
}
