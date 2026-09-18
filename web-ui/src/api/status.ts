import { z } from 'zod';
import { getJson } from './client';

/** Mirrors io.github.codaaaaaa.mecc.core.status.StatusReport. */
export const statusReportSchema = z.object({
  timestamp: z.string(),
  mecc: z.object({
    version: z.string(),
    startedAt: z.string(),
    uptimeSeconds: z.number(),
  }),
  platform: z.object({
    platformId: z.string(),
    minecraftVersion: z.string(),
    loader: z.string(),
    loaderVersion: z.string(),
  }),
  state: z.enum(['RUNNING', 'BUSY', 'UNAVAILABLE']),
  server: z
    .object({
      dedicated: z.boolean(),
      playersOnline: z.number(),
      maxPlayers: z.number(),
      averageTickMillis: z.number(),
      motd: z.string().nullable(),
    })
    .nullable(),
  ae2: z.object({
    loaded: z.boolean(),
    version: z.string().nullable(),
    testedVersion: z.string(),
    tested: z.boolean(),
  }),
  gateway: z.object({
    roundTripMillis: z.number().nullable(),
    pendingTasks: z.number(),
    errorCode: z.string().nullable(),
  }),
});

export type StatusReport = z.infer<typeof statusReportSchema>;
export type ServerState = StatusReport['state'];

export function fetchStatus(signal?: AbortSignal): Promise<StatusReport> {
  return getJson('/api/v1/status', statusReportSchema, signal);
}
