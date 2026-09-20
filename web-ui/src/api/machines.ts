import { z } from 'zod';
import { getJson } from './client';
import { locationSchema, resourceLabelSchema } from './crafting';

/** Mirrors io.github.codaaaaaa.mecc.core.machines.MachineService. */
export const MACHINE_STATUSES = ['STUCK', 'WAITING', 'WORKING', 'IDLE', 'DISABLED'] as const;
export type MachineStatus = (typeof MACHINE_STATUSES)[number];

export const machineSchema = z.object({
  id: z.string(),
  block: resourceLabelSchema.nullable(),
  location: locationSchema.nullable(),
  providers: z.number(),
  status: z.enum(MACHINE_STATUSES),
  reason: z.enum(['OUTPUT_BLOCKED', 'MACHINE_WAITING', 'NO_CHANGE']).nullable(),
  /** The machine's own status (GregTech), when it has one. */
  reported: z.string().nullable(),
  /** Why it says it cannot run, in its own words, when it says so. */
  waitingReason: z.string().nullable(),
  progress: z.number().nullable(),
  awaited: z.boolean(),
  pendingSends: z.number(),
  lastChange: z.string(),
});
export type Machine = z.infer<typeof machineSchema>;

export const machineListSchema = z.object({
  capturedAt: z.string(),
  machines: z.array(machineSchema),
  stuckAfterSeconds: z.number(),
  assetVersion: z.string(),
});
export type MachineList = z.infer<typeof machineListSchema>;

export function fetchMachines(networkId: string, locale: string, signal?: AbortSignal): Promise<MachineList> {
  return getJson(`/api/v1/networks/${encodeURIComponent(networkId)}/machines?locale=${encodeURIComponent(locale)}`,
    machineListSchema, signal);
}
