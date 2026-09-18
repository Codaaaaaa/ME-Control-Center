import { z } from 'zod';
import { getJson, sendJson, sendNoContent } from './client';

/** Mirrors io.github.codaaaaaa.mecc.core.auth.AuthViews. */
export const userViewSchema = z.object({
  playerUuid: z.string(),
  playerName: z.string().nullable(),
});

export const deviceSchema = z.object({
  id: z.string(),
  name: z.string(),
  createdAt: z.string(),
  lastUsedAt: z.string(),
  lastAddress: z.string().nullable(),
  current: z.boolean(),
});

export const meSchema = z.object({
  user: userViewSchema,
  device: deviceSchema,
  serverAdmin: z.boolean(),
  adminOverride: z.boolean(),
});

export type UserView = z.infer<typeof userViewSchema>;
export type Device = z.infer<typeof deviceSchema>;
export type Me = z.infer<typeof meSchema>;

export function fetchMe(signal?: AbortSignal): Promise<Me> {
  return getJson('/api/v1/me', meSchema, signal);
}

export function pair(key: string, deviceName: string): Promise<Me> {
  return sendJson('POST', '/api/v1/auth/pair', { key, deviceName }, meSchema);
}

export function logout(): Promise<void> {
  return sendNoContent('POST', '/api/v1/auth/logout');
}

export function fetchDevices(signal?: AbortSignal): Promise<Device[]> {
  return getJson('/api/v1/devices', z.object({ devices: z.array(deviceSchema) }), signal).then((r) => r.devices);
}

export function renameDevice(id: string, name: string): Promise<Device> {
  return sendJson('PATCH', `/api/v1/devices/${encodeURIComponent(id)}`, { name }, deviceSchema);
}

export function revokeDevice(id: string): Promise<void> {
  return sendNoContent('DELETE', `/api/v1/devices/${encodeURIComponent(id)}`);
}

export function revokeOtherDevices(): Promise<number> {
  return sendJson('POST', '/api/v1/devices/revoke-others', {}, z.object({ revoked: z.number() })).then((r) => r.revoked);
}
