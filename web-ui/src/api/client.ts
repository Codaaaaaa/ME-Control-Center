import { z } from 'zod';

/** Structured error envelope returned by every ME Control Center API endpoint (spec section 30). */
const errorEnvelopeSchema = z.object({
  error: z.object({
    code: z.string(),
    message: z.string(),
    details: z.record(z.string(), z.unknown()).optional(),
  }),
});

export type ApiErrorKind = 'network' | 'http' | 'schema';

export class ApiError extends Error {
  constructor(
    readonly kind: ApiErrorKind,
    readonly status: number,
    readonly code: string,
    message: string,
    readonly details: Record<string, unknown> = {},
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export function isApiError(error: unknown, code?: string): error is ApiError {
  return error instanceof ApiError && (code === undefined || error.code === code);
}

type Method = 'GET' | 'POST' | 'PATCH' | 'DELETE';

async function request(method: Method, path: string, body: unknown, signal?: AbortSignal): Promise<unknown> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  const init: RequestInit = { method, headers, signal, credentials: 'same-origin' };
  if (method === 'POST' || method === 'PATCH') {
    // The server requires JSON for every state-changing request (CSRF defense).
    headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body ?? {});
  }

  let response: Response;
  try {
    response = await fetch(path, init);
  } catch (cause) {
    if (signal?.aborted) {
      throw cause;
    }
    throw new ApiError('network', 0, 'NETWORK_ERROR', 'Unable to reach the ME Control Center server');
  }

  if (response.status === 204) {
    return undefined;
  }
  const parsed: unknown = await response.json().catch(() => undefined);
  if (!response.ok) {
    const envelope = errorEnvelopeSchema.safeParse(parsed);
    if (envelope.success) {
      const { code, message, details } = envelope.data.error;
      throw new ApiError('http', response.status, code, message, details ?? {});
    }
    throw new ApiError('http', response.status, `HTTP_${response.status}`, response.statusText);
  }
  return parsed;
}

/** Fetches a JSON API resource and validates it at runtime so UI code never sees malformed data. */
export async function getJson<T>(path: string, schema: z.ZodType<T>, signal?: AbortSignal): Promise<T> {
  return parseResponse(schema, await request('GET', path, undefined, signal));
}

export async function sendJson<T>(method: Exclude<Method, 'GET'>, path: string, body: unknown, schema: z.ZodType<T>): Promise<T> {
  return parseResponse(schema, await request(method, path, body));
}

export async function sendNoContent(method: Exclude<Method, 'GET'>, path: string, body?: unknown): Promise<void> {
  await request(method, path, body);
}

export function parseResponse<T>(schema: z.ZodType<T>, body: unknown): T {
  const parsed = schema.safeParse(body);
  if (!parsed.success) {
    throw new ApiError('schema', 200, 'UNEXPECTED_RESPONSE', parsed.error.message);
  }
  return parsed.data;
}

/** User-facing failure categories (spec section 47): never collapse failures into "empty". */
export type FailureKind =
  | 'offline'
  | 'unauthenticated'
  | 'permission'
  | 'notFound'
  | 'networkOffline'
  | 'unavailable'
  | 'rateLimited'
  | 'error';

export function failureKind(error: unknown): FailureKind {
  if (!(error instanceof ApiError)) return 'error';
  if (error.kind === 'network') return 'offline';
  switch (error.code) {
    case 'UNAUTHENTICATED':
      return 'unauthenticated';
    case 'PERMISSION_DENIED':
    case 'ORIGIN_REJECTED':
      return 'permission';
    case 'NOT_FOUND':
    case 'NETWORK_NOT_FOUND':
    case 'DEVICE_NOT_FOUND':
    case 'PLAYER_NOT_FOUND':
    case 'NETWORK_CANDIDATE_NOT_FOUND':
    case 'RESOURCE_NOT_FOUND':
    case 'ORDER_NOT_FOUND':
    case 'CPU_NOT_FOUND':
      return 'notFound';
    case 'NETWORK_OFFLINE':
    case 'NETWORK_UNAVAILABLE':
      return 'networkOffline';
    case 'SERVER_UNAVAILABLE':
    case 'SERVICE_UNAVAILABLE':
    case 'GATEWAY_BUSY':
    case 'SERVER_THREAD_TIMEOUT':
      return 'unavailable';
    case 'RATE_LIMITED':
      return 'rateLimited';
    default:
      return 'error';
  }
}
