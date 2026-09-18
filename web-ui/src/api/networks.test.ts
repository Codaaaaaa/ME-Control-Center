import { describe, expect, it } from 'vitest';
import { ApiError, failureKind, parseResponse } from './client';
import { networkDetailSchema } from './networks';

const detail = {
  network: {
    id: 'a1b2',
    displayName: 'Main Base',
    owner: { playerUuid: 'u1', playerName: 'Steve' },
    role: 'OWNER',
    adminOverride: false,
    state: 'ONLINE',
    stateReason: null,
    lastSeenAt: '2026-09-17T12:00:00Z',
    createdAt: '2026-09-17T11:00:00Z',
  },
  status: {
    powered: true,
    booting: false,
    controllerState: 'CONTROLLER_ONLINE',
    channelMode: 'DEFAULT',
    storedEnergy: 1000,
    energyCapacity: 16000,
    averageEnergyUsage: 12.5,
    averageEnergyInjection: 40,
    usedChannels: 17,
    nodeCount: 64,
    storedResourceTypes: 1234,
    craftingCpus: 4,
    busyCraftingCpus: 1,
    patternProviders: null,
  },
  statusCapturedAt: '2026-09-17T12:00:00Z',
  anchors: [{ key: 'minecraft:overworld@1,64,0', dimension: 'minecraft:overworld', x: 1, y: 64, z: 0, owner: null, active: true }],
  capabilities: ['VIEW_NETWORK', 'MANAGE_MEMBERS'],
};

describe('networkDetailSchema', () => {
  it('accepts a live network with unsupported capabilities as null', () => {
    const parsed = parseResponse(networkDetailSchema, detail);
    expect(parsed.status?.patternProviders).toBeNull();
  });

  it('accepts a conflicted network without live status', () => {
    const conflicted = {
      ...detail,
      network: { ...detail.network, state: 'CONFLICT', stateReason: 'MERGED' },
      status: null,
      statusCapturedAt: null,
    };
    expect(parseResponse(networkDetailSchema, conflicted).network.stateReason).toBe('MERGED');
  });

  it('rejects unknown roles', () => {
    expect(() => parseResponse(networkDetailSchema, { ...detail, network: { ...detail.network, role: 'GOD' } })).toThrow(
      ApiError,
    );
  });
});

describe('failureKind', () => {
  it('distinguishes failure categories instead of treating them as empty', () => {
    expect(failureKind(new ApiError('network', 0, 'NETWORK_ERROR', ''))).toBe('offline');
    expect(failureKind(new ApiError('http', 401, 'UNAUTHENTICATED', ''))).toBe('unauthenticated');
    expect(failureKind(new ApiError('http', 403, 'PERMISSION_DENIED', ''))).toBe('permission');
    expect(failureKind(new ApiError('http', 404, 'NETWORK_NOT_FOUND', ''))).toBe('notFound');
    expect(failureKind(new ApiError('http', 503, 'GATEWAY_BUSY', ''))).toBe('unavailable');
    expect(failureKind(new Error('boom'))).toBe('error');
  });
});
