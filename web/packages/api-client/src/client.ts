import { HttpError, NetworkError } from './errors';

/**
 * Reads an environment variable in both frontends.
 *
 * // NOTE: this package is shared by the Vite console and the Next.js storefront, and only
 *
 * <p>`NEXT_PUBLIC_API_URL` first because the Next.js storefront is the one that needs it here;
 * the console passes `VITE_API_URL`. Neither prefix is filtered, so the two apps can be pointed at
 * the same backend without the shared package having to know which bundler will read it.
 */
function readEnv(key: string): string | undefined {
  if (typeof process !== 'undefined' && process.env) {
    return process.env[key];
  }
  return (import.meta as unknown as { env?: Record<string, string | undefined> }).env?.[key];
}

export const API_URL =
  readEnv('NEXT_PUBLIC_API_URL') ?? readEnv('VITE_API_URL') ?? 'http://localhost:8080';

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

/**
 * Supplies the bearer token, if the caller has one.
 *
 * <p>A function rather than a token string, because the Keycloak access token expires and has to
 * be refreshed on use. Registering the string once would mean every request after the first
 * refresh carried an expired token, and the symptom — a page that worked for ten minutes and then
 * started 401ing — is maddening to diagnose.
 *
 * <p>Left unset, requests are sent anonymously. That is correct for the SSR storefront, which reads
 * public endpoints server-side and has no interactive session, and for the shop-type and category
 * pickers.
 */
let tokenProvider: (() => Promise<string | null>) | null = null;

/** Registers the token supplier. Called once by each app at startup. */
export function setTokenProvider(provider: (() => Promise<string | null>) | null): void {
  tokenProvider = provider;
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { body, idempotencyKey, headers, ...rest } = options;

  const requestHeaders = new Headers(headers);
  requestHeaders.set('Accept', 'application/json');

  // Previously absent entirely, which meant every call this package made against a protected
  // endpoint answered 401. The theme editor was the visible symptom: its list, get, save and
  // publish calls all went out without a token and the page silently rendered nothing.
  const token = await tokenProvider?.();
  if (token) {
    requestHeaders.set('Authorization', `Bearer ${token}`);
  }
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
