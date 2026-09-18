import { describe, expect, it } from 'vitest';
import { ApiError, failureKind, parseResponse } from './client';
import { cpuListSchema, orderFilterOf, orderPageSchema, planSchema } from './crafting';
import { liveEffects } from './live';

const label = {
  id: 'item:minecraft:iron_block',
  type: 'item',
  name: 'Block of Iron',
  nameSpans: null,
  modId: 'minecraft',
  modName: 'Minecraft',
  unit: null,
  iconKey: 'item/minecraft/iron_block',
};

const cpuList = {
  capturedAt: '2026-09-18T12:00:00Z',
  assetVersion: 'v1',
  cpus: [
    {
      id: 'c0123456789abcdef',
      name: 'Main CPU',
      location: { dimension: 'minecraft:overworld', x: 1, y: 64, z: 2 },
      busy: true,
      online: true,
      storageBytes: 65536,
      coProcessors: 16,
      selectionMode: 'ANY',
      job: {
        jobId: 'j1',
        output: label,
        amount: 8192,
        progress: { completed: null, remaining: null, requested: 8192, percent: 74.1, confidence: 'AUTHORITATIVE' },
        elapsedMillis: 872000,
        orderId: 'o1',
        initiator: { playerUuid: 'u1', playerName: 'Alex' },
        cancellable: true,
      },
    },
    // A CPU type that reports neither power nor a location.
    { id: 'x1', name: null, location: null, busy: false, online: null, storageBytes: 1024, coProcessors: 0, selectionMode: 'ANY', job: null },
  ],
};

const order = {
  id: 'o1',
  networkId: 'n1',
  creator: { playerUuid: 'u1', playerName: 'Alex' },
  target: label,
  amount: 8,
  state: 'RUNNING',
  source: 'MANUAL',
  createdAt: '2026-09-18T12:00:00Z',
  startedAt: '2026-09-18T12:00:01Z',
  endedAt: null,
  cpu: { id: 'c0123456789abcdef', name: 'Main CPU' },
  progress: { completed: null, remaining: null, requested: 8, percent: null, confidence: 'NONE' },
  elapsedMillis: 1000,
  failure: null,
  cancellable: true,
  observed: true,
  lastObservedAt: '2026-09-18T12:00:02Z',
};

describe('crafting schemas', () => {
  it('accepts CPUs with unsupported values as null', () => {
    const parsed = parseResponse(cpuListSchema, cpuList);
    expect(parsed.cpus[1]?.online).toBeNull();
    expect(parsed.cpus[0]?.job?.progress.percent).toBe(74.1);
  });

  it('accepts a plan that is still calculating', () => {
    const plan = parseResponse(planSchema, {
      id: 'p1', state: 'CALCULATING', networkId: 'n1', output: null, requestedAmount: 8, amount: null, complete: null,
      bytes: null, multiplePaths: false, entries: [], totalEntries: 0, cpus: [], errorCode: null, errorMessage: null,
      createdAt: '2026-09-18T12:00:00Z', expiresAt: '2026-09-18T12:10:00Z', assetVersion: 'v1',
    });
    expect(plan.state).toBe('CALCULATING');
  });

  it('rejects unknown order states', () => {
    expect(() => parseResponse(orderPageSchema, { orders: [{ ...order, state: 'EXPLODED' }], nextCursor: null, assetVersion: 'v1' }))
      .toThrow(ApiError);
  });

  it('files unknown outcomes under failed', () => {
    expect(orderFilterOf('RUNNING')).toBe('ACTIVE');
    expect(orderFilterOf('SUBMITTING')).toBe('ACTIVE');
    expect(orderFilterOf('UNKNOWN')).toBe('FAILED');
    expect(orderFilterOf('CANCELLED')).toBe('CANCELLED');
  });
});

describe('liveEffects', () => {
  const event = (type: string, payload: unknown, networkId: string | null = 'n1') => ({
    type, timestamp: '2026-09-18T12:00:00Z', networkId, payload,
  });

  it('turns CPU and order events into cache updates', () => {
    expect(liveEffects(event('cpu.updated', cpuList))).toEqual([
      { kind: 'cpus', networkId: 'n1', cpus: expect.objectContaining({ assetVersion: 'v1' }) },
    ]);
    expect(liveEffects(event('crafting.order.completed', { ...order, state: 'COMPLETED' }))[0]).toMatchObject({
      kind: 'order', order: { state: 'COMPLETED' },
    });
    expect(liveEffects(event('network.status.changed', { networkId: 'n1' }))).toEqual([{ kind: 'network', networkId: 'n1' }]);
  });

  it('ignores malformed payloads and events without a network', () => {
    expect(liveEffects(event('cpu.updated', { cpus: 'nope' }))).toEqual([]);
    expect(liveEffects(event('session.ready', {}, null))).toEqual([]);
    expect(liveEffects(event('pong', {}))).toEqual([]);
  });
});

describe('failureKind for crafting', () => {
  it('reports an unloaded network as offline, not as a generic error', () => {
    expect(failureKind(new ApiError('http', 409, 'NETWORK_OFFLINE', ''))).toBe('networkOffline');
    expect(failureKind(new ApiError('http', 409, 'NETWORK_UNAVAILABLE', ''))).toBe('networkOffline');
    expect(failureKind(new ApiError('http', 404, 'ORDER_NOT_FOUND', ''))).toBe('notFound');
  });
});
