import { describe, expect, it } from 'vitest';
import { adminOverviewSchema, auditPageSchema } from './admin';

describe('admin schemas', () => {
  it('accept the server payloads', () => {
    const overview = adminOverviewSchema.parse({
      meccVersion: '0.1.0',
      platform: { platformId: 'forge-1.20.1', minecraftVersion: '1.20.1', loader: 'forge', loaderVersion: '47.4.16' },
      configFile: 'config/mecc/mecc.toml',
      config: {
        web: { enabled: true, host: '127.0.0.1', port: 18181, maxThreads: 32, publicBaseUrl: '' },
        security: { trustedProxies: ['127.0.0.1'], rateLimitRequestsPerMinute: 1200 },
      },
      database: {
        file: 'world/mecc/mecc.db',
        schemaVersion: 4,
        sizeBytes: 4096,
        backups: [{ name: 'mecc-20260918-120000-000-manual.db', createdAt: '2026-09-18T12:00:00Z', sizeBytes: 2048 }],
      },
      contentPacks: [
        {
          name: 'mecc-content-pack-abc123.zip',
          fingerprint: 'abc123',
          createdAt: '2026-09-18T12:00:00Z',
          minecraft: '1.20.1',
          icons: 1200,
          locales: ['en_us', 'zh_cn'],
          status: 'MISMATCH',
          problems: ['1 mods have other versions on the server: gtceu 1.0 -> 1.1'],
        },
        { name: 'junk.zip', fingerprint: null, createdAt: null, minecraft: null, icons: null, locales: [], status: 'SKIPPED', problems: ['x'] },
      ],
    });
    expect(overview.config.security?.trustedProxies).toEqual(['127.0.0.1']);
    expect(overview.contentPacks[0]?.status).toBe('MISMATCH');

    const page = auditPageSchema.parse({
      entries: [
        {
          id: 7,
          at: '2026-09-18T12:00:00Z',
          actor: null,
          deviceId: null,
          networkId: null,
          networkName: null,
          action: 'SOMETHING_NEW',
          target: null,
          targetPlayer: null,
          result: 'SUCCESS',
          adminOverride: false,
          parameters: {},
        },
      ],
      nextBefore: null,
    });
    expect(page.entries[0]?.action).toBe('SOMETHING_NEW');
  });
});
