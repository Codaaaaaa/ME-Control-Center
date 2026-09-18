import { z } from 'zod';
import { getJson, sendJson } from './client';
import { userViewSchema } from './auth';

/** Mirrors io.github.codaaaaaa.mecc.core.admin.AdminViews. */
export const auditEntrySchema = z.object({
  id: z.number(),
  at: z.string(),
  /** Null for the server console. */
  actor: userViewSchema.nullable(),
  deviceId: z.string().nullable(),
  networkId: z.string().nullable(),
  /** Null when the network was deleted. */
  networkName: z.string().nullable(),
  action: z.string(),
  target: z.string().nullable(),
  targetPlayer: userViewSchema.nullable(),
  result: z.enum(['SUCCESS', 'DENIED', 'FAILED']),
  adminOverride: z.boolean(),
  parameters: z.record(z.string(), z.string()),
});
export type AuditEntry = z.infer<typeof auditEntrySchema>;

export const auditPageSchema = z.object({ entries: z.array(auditEntrySchema), nextBefore: z.number().nullable() });
export type AuditPage = z.infer<typeof auditPageSchema>;

export const backupSchema = z.object({ name: z.string(), createdAt: z.string(), sizeBytes: z.number() });
export type Backup = z.infer<typeof backupSchema>;

/** Configuration sections are shown as they are, so new settings appear without UI changes. */
const configValueSchema = z.union([z.string(), z.number(), z.boolean(), z.array(z.string())]);

export const adminOverviewSchema = z.object({
  meccVersion: z.string(),
  platform: z.object({ platformId: z.string(), minecraftVersion: z.string(), loader: z.string(), loaderVersion: z.string() }),
  configFile: z.string(),
  config: z.record(z.string(), z.record(z.string(), configValueSchema)),
  database: z.object({
    file: z.string(),
    schemaVersion: z.number(),
    sizeBytes: z.number(),
    backups: z.array(backupSchema),
  }),
  /** Content packs in config/mecc/content-packs/ and whether they match this server (spec section 45). */
  contentPacks: z.array(
    z.object({
      name: z.string(),
      fingerprint: z.string().nullable(),
      createdAt: z.string().nullable(),
      minecraft: z.string().nullable(),
      icons: z.number().nullable(),
      locales: z.array(z.string()),
      status: z.enum(['MATCH', 'MISMATCH', 'SKIPPED']),
      problems: z.array(z.string()),
    }),
  ),
});
export type AdminOverview = z.infer<typeof adminOverviewSchema>;

export function fetchAdminOverview(signal?: AbortSignal): Promise<AdminOverview> {
  return getJson('/api/v1/admin', adminOverviewSchema, signal);
}

export function createBackup(): Promise<Backup> {
  return sendJson('POST', '/api/v1/admin/backups', {}, backupSchema);
}

/** A network's audit log, or the whole server's when `networkId` is null (server admins). */
export function fetchAuditLog(networkId: string | null, before: number | null, signal?: AbortSignal): Promise<AuditPage> {
  const parameters = new URLSearchParams({ limit: '50' });
  if (before !== null) {
    parameters.set('before', String(before));
  }
  const path = networkId === null ? '/api/v1/admin/audit' : `/api/v1/networks/${encodeURIComponent(networkId)}/audit`;
  return getJson(`${path}?${parameters}`, auditPageSchema, signal);
}
