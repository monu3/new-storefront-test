import Keycloak from 'keycloak-js';

import { readEnv } from './config';

import type { AuthConfig, RealmName } from './config';

/**
 * Application roles, mirroring what the backend maps realm roles to.
 *
 * The backend is the authority; these names must match the `ROLE_*` values the resource server
 * produces. A mismatch shows up as a 403 rather than a compile error, so the mapping is
 * asserted in one place and reused by the guards below.
 */
export const APP_ROLE = {
  ADMIN: 'ROLE_ADMIN',
  SHOP: 'ROLE_SHOP',
  CUSTOMER: 'ROLE_CUSTOMER',
  AUTHENTICATED: 'ROLE_AUTHENTICATED',
} as const;

export type AppRole = (typeof APP_ROLE)[keyof typeof APP_ROLE];

export interface AuthSession {
  authenticated: boolean;
  subject?: string;
  email?: string;
  fullName?: string;
  realm?: RealmName;
  /** Authorities exactly as the backend sees them. */
  roles: AppRole[];
  accessToken?: string;
}

export interface AuthAdapter {
  init(): Promise<boolean>;
  login(returnTo?: string): Promise<void>;
  logout(): Promise<void>;
  isAuthenticated(): boolean;
  getSession(): AuthSession;
  getAccessToken(): Promise<string>;
  /** Re-authenticates silently when the access token is about to expire. */
  ensureFreshToken(): Promise<string>;
  hasRole(role: AppRole): boolean;
  hasAnyRole(...roles: AppRole[]): boolean;
}

/**
 * Keycloak PKCE adapter.
 *
 * <p><b>No silent SSO check, deliberately.</b> `onLoad: 'check-sso'` makes keycloak-js load a
 * hidden iframe at `silentCheckSsoRedirectUri` and wait for Keycloak to post a callback back.
 * Neither frontend serves that route — the Vite console answers it with the SPA shell (200, HTML)
 * and the Next.js storefront answers 404 — so neither is a callback and the promise never
 * settles. An explicit login is also the honest behaviour for a console that only shop owners and
 * admins use: they should be asked who they are rather than silently relying on an ambient
 * session.
 *
 * <p>Silent SSO can return with a real `/silent-check-sso` page per app. It needs that page to
 * actually exist first.
 *
 * Two deliberate choices:
 *
 * - `pkceMethod: 'S256'`, never `'plain'`. Both realms advertise `plain`, but the code verifier
 *   would travel in the URL, where it can leak through logs and referrers.
 * - `refreshTokenEnabled`. The realms issue 300-second access tokens, so without a silent
 *   refresh a user would be kicked out every five minutes mid-task.
 */
export function createKeycloakAdapter(config: AuthConfig): AuthAdapter {
  const kc = new Keycloak({
    url: config.baseUrl,
    realm: config.realm,
    clientId: config.clientId,
  });

  let ready = false;
  /**
   * The in-flight initialisation, memoised.
   *
   * <p>See {@link AuthAdapter.init}: caching the promise rather than the result is what makes
   * concurrent calls safe, because `keycloak-js` permits exactly one `init()` per instance.
   */
  let initPromise: Promise<boolean> | null = null;
  let roles: AppRole[] = [];

  function readRoles(): AppRole[] {
    const token = kc.tokenParsed as { realm_access?: { roles?: string[] } } | undefined;
    const realmRoles = token?.realm_access?.roles ?? [];
    return realmRoles.filter((r): r is AppRole =>
      Object.values(APP_ROLE).includes(r as AppRole),
    );
  }

  function getSession(): AuthSession {
    if (!ready || !kc.authenticated) {
      return { authenticated: false, roles: [] };
    }
    const parsed = kc.tokenParsed as
      | { sub?: string; email?: string; name?: string; preferred_username?: string }
      | undefined;
    return {
      authenticated: true,
      subject: parsed?.sub,
      email: parsed?.email,
      fullName: parsed?.name ?? parsed?.preferred_username,
      realm: config.realm,
      roles,
      accessToken: kc.token,
    };
  }

  return {
    /**
     * Initialises the adapter. Safe to call any number of times, from anywhere, concurrently.
     *
     * <p><b>No silent SSO check</b> — see the note on {@link createKeycloakAdapter}.
     *
     * <p><b>The in-flight promise is memoised, not the result.</b> The original guard was
     * `if (ready) return ...; ready = await kc.init(...)`, which is not concurrency-safe: `ready`
     * stays `false` for the whole await, so a second caller passes the guard and calls
     * `kc.init()` again. `keycloak-js` rejects that with
     *
     * <pre>Error: A 'Keycloak' instance can only be initialized once.</pre>
     *
     * <p>Two callers reach here: `main.tsx` initialises so the session is ready, and
     * `AppShell` initialises so it knows whether to redirect to `/login`. React's StrictMode
     * also double-invokes effects in development, so the race fires on every reload. Caching the
     * promise makes both callers await the *same* initialisation.
     *
     * <p>A rejected initialisation is cached too, deliberately: retrying would call `kc.init()`
     * again, which is the exact thing that throws. One failure should not be retried into a
     * second, louder failure — it should just fail, and `login()` can still be called explicitly.
     */
    init() {
      if (!initPromise) {
        initPromise = kc
          .init({
            // No onLoad, so the adapter only initialises. It does not try to authenticate.
            pkceMethod: 'S256',
            checkLoginIframe: false,
            // Guarded read: this runs in the browser, where the console's Vite build has no
            // `process`. See config.ts for the full explanation and plan Appendix P.
            enableLogging: readEnv('NODE_ENV') === 'development',
          })
          .then((authenticated) => {
            ready = authenticated;
            roles = readRoles();
            return authenticated;
          });
      }
      return initPromise;
    },

    async login(returnTo?: string) {
      await kc.login({
        redirectUri: returnTo ?? config.redirectUri,
        maxAge: 60 * 30,
        // Asking for these puts the roles in the token the backend reads.
        scope: 'openid profile email',
      });
    },

    async logout() {
      await kc.logout({ redirectUri: config.postLogoutRedirectUri });
    },

    isAuthenticated() {
      return ready && kc.authenticated;
    },

    getSession,

    async getAccessToken() {
      if (!ready) await this.init();
      return kc.token ?? '';
    },

    async ensureFreshToken() {
      if (!ready) await this.init();
      // keycloak-js refreshes automatically once minValidity elapses.
      await kc.updateToken(30);
      roles = readRoles();
      return kc.token ?? '';
    },

    hasRole(role) {
      return roles.includes(role);
    },

    hasAnyRole(...wanted) {
      return wanted.some((r) => roles.includes(r));
    },
  };
}
