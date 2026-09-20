import { z } from 'zod';
import { getJson } from './client';
import { locationSchema, resourceLabelSchema } from './crafting';
import { gridStatusSchema } from './networks';

/** Mirrors io.github.codaaaaaa.mecc.core.explorer.ExplorerService (spec section 26). */
export const DEVICE_KINDS = [
  'STORAGE',
  'CRAFTING',
  'PATTERN_PROVIDER',
  'INTERFACE',
  'ACCESS_POINT',
  'CONTROLLER',
  'OTHER',
] as const;
export type DeviceKind = (typeof DEVICE_KINDS)[number];

export const deviceGroupSchema = z.object({
  kind: z.enum(DEVICE_KINDS),
  item: resourceLabelSchema.nullable(),
  count: z.number(),
  /** How many have no power or no channel. */
  offline: z.number(),
  channels: z.number().nullable(),
  idlePower: z.number().nullable(),
  locations: z.array(locationSchema),
  /** Whether `locations` was cut short. */
  truncated: z.boolean(),
});
export type DeviceGroup = z.infer<typeof deviceGroupSchema>;

export const networkMapSchema = z.object({
  capturedAt: z.string(),
  status: gridStatusSchema,
  devices: z.array(deviceGroupSchema),
  nodes: z.number(),
  offlineNodes: z.number(),
  assetVersion: z.string(),
});
export type NetworkMap = z.infer<typeof networkMapSchema>;

export function fetchNetworkMap(networkId: string, locale: string, signal?: AbortSignal): Promise<NetworkMap> {
  return getJson(`/api/v1/networks/${encodeURIComponent(networkId)}/explorer?locale=${encodeURIComponent(locale)}`,
    networkMapSchema, signal);
}
