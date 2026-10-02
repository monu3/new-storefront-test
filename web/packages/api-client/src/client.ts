import { HttpError, NetworkError } from './errors';

export const API_URL =
  process.env.NEXT_PUBLIC_API_URL ?? process.env.VITE_API_URL ?? 'http://localhost:8080';

export interface RequestOptions extends Omit<RequestInit, 'body'> {
  body?: unknown;
  /**
   * Key for safe retries on anything that moves money or creates an order. The backend stores
   * it in a unique index, so a duplicate submit is a no-op rather than a double charge.
   * See plan section 5.5.
   */
  idempotencyKey?: string;
}

/** Generates a key for one logical user action. Stable across retries of that action. */
export function newIdempotencyKey(prefix = 'idem'): string {
  const rand =
    typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  return `${prefix}_${rand}`;
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { body, idempotencyKey, headers, ...rest } = options;

  const requestHeaders = new Headers(headers);
  requestHeaders.set('Accept', 'application/json');
  if (body !== undefined) {
    requestHeaders.set('Content-Type', 'application/json');
  }
  if (idempotencyKey) {
    requestHeaders.set('Idempotency-Key', idempotencyKey);
  }

  let response: Response;
  try {
    response = await fetch(`${API_URL}${path}`, {
      ...rest,
      headers: requestHeaders,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (cause) {
    // A thrown fetch is always a transport failure, never an HTTP status. Reporting it as
    // such is what lets the UI say "backend not running" instead of "request failed".
    throw new NetworkError(cause);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  let payload: unknown = undefined;
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch {
      payload = undefined;
    }
  }

  if (!response.ok) {
    throw new HttpError(response.status, (payload ?? {}) as Record<string, unknown>);
  }

  return payload as T;
}

export const api = {
  get: <T>(path: string, options?: RequestOptions) => request<T>(path, { ...options, method: 'GET' }),
  post: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>(path, { ...options, method: 'POST', body }),
  put: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>(path, { ...options, method: 'PUT', body }),
  patch: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>(path, { ...options, method: 'PATCH', body }),
  delete: <T>(path: string, options?: RequestOptions) =>
    request<T>(path, { ...options, method: 'DELETE' }),
};

/**
 * Cursor pagination, matching the backend's `?cursor=&limit=`.
 *
 * Offset pagination degrades badly on the wallet ledger and order lists, which grow without
 * bound; a cursor keeps every page equally cheap.
 */
export interface Page<T> {
  items: T[];
  nextCursor: string | null;
  hasMore: boolean;
}

export async function fetchPage<T>(
  path: string,
  params: { cursor?: string; limit?: number } = {},
  options?: RequestOptions,
): Promise<Page<T>> {
  const query = new URLSearchParams();
  if (params.cursor) query.set('cursor', params.cursor);
  if (params.limit) query.set('limit', String(params.limit));
  const suffix = query.toString();
  return api.get<Page<T>>(`${path}${suffix ? `?${suffix}` : ''}`, options);
}
