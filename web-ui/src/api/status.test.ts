import { describe, expect, it } from 'vitest';
import { ApiError, parseResponse } from './client';
import { statusReportSchema } from './status';

const running = {
  timestamp: '2026-09-17T12:00:00Z',
  mecc: { version: '0.1.0', startedAt: '2026-09-17T11:00:00Z', uptimeSeconds: 3600 },
  platform: { platformId: 'forge-1.20.1', minecraftVersion: '1.20.1', loader: 'forge', loaderVersion: '47.4.16' },
  state: 'RUNNING',
  server: { dedicated: true, playersOnline: 2, maxPlayers: 20, averageTickMillis: 8.4, motd: 'A Minecraft Server' },
  ae2: { loaded: true, version: '15.4.10', testedVersion: '15.4.10', tested: true },
  gateway: { roundTripMillis: 12.5, pendingTasks: 0, errorCode: null },
};

describe('statusReportSchema', () => {
  it('accepts a running server report', () => {
    expect(parseResponse(statusReportSchema, running).server?.playersOnline).toBe(2);
  });

  it('accepts a degraded report without server snapshot', () => {
    const unavailable = {
      ...running,
      state: 'UNAVAILABLE',
      server: null,
      gateway: { roundTripMillis: null, pendingTasks: 0, errorCode: 'SERVER_UNAVAILABLE' },
    };
    expect(parseResponse(statusReportSchema, unavailable).state).toBe('UNAVAILABLE');
  });

  it('rejects malformed responses with a schema ApiError', () => {
    expect(() => parseResponse(statusReportSchema, { ...running, state: 'EXPLODED' })).toThrow(ApiError);
  });
});
