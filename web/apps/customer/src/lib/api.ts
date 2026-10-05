import { auth } from './auth';
import type { AuthAdapter } from '@store-app/auth';

/**
 * The customer app's API helpers.
 *
 * <p>Bound once, here, over the shared adapter from {@code ./auth}. Every page imports these helpers
 * rather than threading an adapter through, because a page that built its own would be a second
 * Keycloak instance — and two instances that each answer {@code isAuthenticated()} consistently but
 * disagree with each other produce a sign-in button that never enables and no error to explain it.
 *
 * <p><b>Reads the token fresh on every call.</b> The adapter refreshes it when it is close to
 * expiry, so a long-lived tab does not start failing at the one hour mark.
 */
/** A GET with the Bearer token attached. Throws the problem detail rather than the status code. */
export async function apiGet<T>(path: string): Promise<T> {
  const response = await authorizedFetch(auth, path);
  if (!response.ok) throw new Error(await problem(response));
  return (await response.json()) as T;
}

/** A POST with a JSON body. */
export async function apiPost<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(await bearerHeaders()),
    },
    body: JSON.stringify(body ?? {}),
  });
  if (!response.ok) throw new Error(await problem(response));
  return (await response.json()) as T;
}

/** A PUT with a JSON body. */
export async function apiPut<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(path, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      ...(await bearerHeaders()),
    },
    body: JSON.stringify(body ?? {}),
  });
  if (!response.ok) throw new Error(await problem(response));
  return (await response.json()) as T;
}

/** A PATCH with a JSON body. */
export async function apiPatch<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(path, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
      ...(await bearerHeaders()),
    },
    body: JSON.stringify(body ?? {}),
  });
  if (!response.ok) throw new Error(await problem(response));
  return (await response.json()) as T;
}

/** A DELETE, optionally with a body — which the address and wishlist endpoints both use. */
export async function apiDelete<T>(path: string, body?: unknown): Promise<T> {
  const response = await fetch(path, {
    method: 'DELETE',
    headers: {
      'Content-Type': 'application/json',
      ...(await bearerHeaders()),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) throw new Error(await problem(response));
  return (response.status === 204 ? (undefined as T) : ((await response.json()) as T));
}

/** Whether somebody is signed in, for the shell's navigation. */
export function isSignedIn(): boolean {
  return auth.isAuthenticated();
}

export function session() {
  return auth.getSession();
}

/** Registers the token with the shared API client, for anything that uses it. */
export async function registerTokenProvider(): Promise<void> {
  const { setTokenProvider } = await import('@store-app/api-client');
  setTokenProvider(async () => {
    try {
      return await auth.ensureFreshToken();
    } catch {
      // No session. An anonymous request is the correct outcome; the server answers 401 and the
      // page decides what to show.
      return null;
    }
  });
}

async function bearerHeaders(): Promise<Record<string, string>> {
  try {
    return { Authorization: `Bearer ${await auth.ensureFreshToken()}` };
  } catch {
    return {};
  }
}

async function authorizedFetch(adapter: AuthAdapter, path: string): Promise<Response> {
  try {
    const token = await adapter.ensureFreshToken();
    return await fetch(path, { headers: { Authorization: `Bearer ${token}` } });
  } catch {
    // Not signed in. Sent anonymously so the server's own 401 reaches the caller, rather than this
    // layer inventing an error message the API never produced.
    return fetch(path);
  }
}

/** Turns a problem response into one readable line. */
async function problem(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { detail?: string; title?: string };
    if (body.detail) return body.detail;
    if (body.title) return body.title;
  } catch {
    // Not a problem+ body.
  }
  return `The server rejected this (HTTP ${response.status})`;
}