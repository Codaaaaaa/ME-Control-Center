import { z } from 'zod';
import { cpuListSchema, orderSchema, type CpuList, type Order } from './crafting';

/**
 * Live updates over `/ws/v1` (spec section 31). Only improves freshness: REST stays the source of truth, and
 * everything is refetched after a reconnect. When the socket is down, pages fall back to polling.
 */

export const liveEventSchema = z.object({
  type: z.string(),
  timestamp: z.string(),
  networkId: z.string().nullable(),
  payload: z.unknown(),
});
export type LiveEvent = z.infer<typeof liveEventSchema>;

export type LiveStatus = 'idle' | 'connecting' | 'open' | 'closed';

/** What a live event means for cached data. Pure, so it can be tested without a socket. */
export type LiveEffect =
  | { kind: 'cpus'; networkId: string; cpus: CpuList }
  | { kind: 'order'; networkId: string; order: Order }
  | { kind: 'network'; networkId: string }
  | { kind: 'patterns'; networkId: string }
  | { kind: 'ended'; networkId: string };

export function liveEffects(event: LiveEvent): LiveEffect[] {
  const networkId = event.networkId;
  if (!networkId) return [];
  switch (event.type) {
    case 'cpu.updated': {
      const cpus = cpuListSchema.safeParse(event.payload);
      return cpus.success ? [{ kind: 'cpus', networkId, cpus: cpus.data }] : [];
    }
    case 'crafting.order.created':
    case 'crafting.order.updated':
    case 'crafting.order.completed':
    case 'crafting.order.failed': {
      const order = orderSchema.safeParse(event.payload);
      return order.success ? [{ kind: 'order', networkId, order: order.data }] : [];
    }
    case 'network.status.changed':
      return [{ kind: 'network', networkId }];
    case 'pattern.deployed':
      return [{ kind: 'patterns', networkId }];
    case 'subscription.ended':
      return [{ kind: 'ended', networkId }];
    default:
      return [];
  }
}

const PING_INTERVAL_MS = 25_000;
const MAX_BACKOFF_MS = 30_000;
/** The server closes with this code when the device is not (or no longer) paired. */
const POLICY_VIOLATION = 1008;

type Listener = (event: LiveEvent) => void;

class LiveClient {
  private socket: WebSocket | null = null;
  private status: LiveStatus = 'idle';
  private readonly subscriptions = new Map<string, { locale: string; count: number }>();
  private readonly listeners = new Set<Listener>();
  private readonly statusListeners = new Set<() => void>();
  private retryTimer: number | undefined;
  private pingTimer: number | undefined;
  private backoff = 1_000;

  getStatus = (): LiveStatus => this.status;

  onStatus = (listener: () => void): (() => void) => {
    this.statusListeners.add(listener);
    return () => this.statusListeners.delete(listener);
  };

  onEvent(listener: Listener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /** Watches a network; the returned function stops watching. Connects on first use. */
  subscribe(networkId: string, locale: string): () => void {
    const existing = this.subscriptions.get(networkId);
    if (existing && existing.locale === locale) {
      existing.count++;
    } else {
      this.subscriptions.set(networkId, { locale, count: (existing?.count ?? 0) + 1 });
      this.send({ type: 'subscribe', networkId, locale });
    }
    this.ensureConnected();
    return () => {
      const current = this.subscriptions.get(networkId);
      if (!current) return;
      current.count--;
      if (current.count <= 0) {
        this.subscriptions.delete(networkId);
        this.send({ type: 'unsubscribe', networkId });
      }
    };
  }

  private ensureConnected(): void {
    if (this.socket || this.retryTimer !== undefined || typeof WebSocket === 'undefined') return;
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const socket = new WebSocket(`${protocol}//${window.location.host}/ws/v1`);
    this.socket = socket;
    this.setStatus('connecting');

    socket.onmessage = (message) => {
      let parsed: LiveEvent;
      try {
        const result = liveEventSchema.safeParse(JSON.parse(String(message.data)));
        if (!result.success) return;
        parsed = result.data;
      } catch {
        return;
      }
      if (parsed.type === 'session.ready') {
        this.backoff = 1_000;
        this.setStatus('open');
        this.subscriptions.forEach((subscription, networkId) =>
          this.send({ type: 'subscribe', networkId, locale: subscription.locale }));
      }
      this.listeners.forEach((listener) => listener(parsed));
    };
    socket.onclose = (event) => {
      this.socket = null;
      window.clearInterval(this.pingTimer);
      this.setStatus('closed');
      // A revoked device would be refused again immediately; retry slowly and let REST show the sign-in.
      const delay = event.code === POLICY_VIOLATION ? MAX_BACKOFF_MS : this.backoff;
      this.backoff = Math.min(MAX_BACKOFF_MS, this.backoff * 2);
      if (this.subscriptions.size > 0) {
        this.retryTimer = window.setTimeout(() => {
          this.retryTimer = undefined;
          this.ensureConnected();
        }, delay);
      }
    };
    this.pingTimer = window.setInterval(() => this.send({ type: 'ping' }), PING_INTERVAL_MS);
  }

  private send(message: Record<string, string>): void {
    if (this.socket && this.socket.readyState === WebSocket.OPEN && this.status === 'open') {
      this.socket.send(JSON.stringify(message));
    }
  }

  private setStatus(status: LiveStatus): void {
    this.status = status;
    this.statusListeners.forEach((listener) => listener());
  }
}

export const liveClient = new LiveClient();
