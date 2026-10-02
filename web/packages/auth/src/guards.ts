import { APP_ROLE, type AppRole, type AuthAdapter, type AuthSession } from './adapter';

/**
 * Route guards for the console.
 *
 * These are a UX affordance, not a security boundary. Hiding a menu item does not protect the
 * data behind it — the backend enforces every rule through `@PreAuthorize` and the tenant
 * filter, and a user who edits local storage gains nothing. Guards exist so a user without the
 * role is not shown a screen that would only 403.
 */

export interface ConsoleUser {
  kind: 'admin' | 'shop' | 'none';
  session: AuthSession;
}

export function resolveConsoleUser(session: AuthSession): ConsoleUser {
  if (!session.authenticated) {
    return { kind: 'none', session };
  }
  if (session.roles.includes(APP_ROLE.ADMIN)) {
    return { kind: 'admin', session };
  }
  if (session.roles.includes(APP_ROLE.SHOP)) {
    return { kind: 'shop', session };
  }
  return { kind: 'none', session };
}

/** The console only accepts tokens from the `ops` realm (D3). */
export function isOpsSession(session: AuthSession): boolean {
  return session.authenticated && session.realm === 'ops';
}

export function homeRouteFor(user: ConsoleUser): string {
  switch (user.kind) {
    case 'admin':
      return '/admin';
    case 'shop':
      return '/shop';
    default:
      return '/login';
  }
}

/**
 * Wraps a react-query / fetch call so the Authorization header is attached and the token is
 * refreshed first if it is near expiry. Centralised so no caller can forget the header.
 */
export async function authorizedFetch(
  adapter: AuthAdapter,
  input: string,
  init: RequestInit = {},
): Promise<Response> {
  const token = await adapter.ensureFreshToken();
  const headers = new Headers(init.headers);
  headers.set('Authorization', `Bearer ${token}`);
  if (init.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  return fetch(input, { ...init, headers });
}

export type { AppRole };
