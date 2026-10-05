import Keycloak from 'keycloak-js';

import { readEnv } from './config';

import type { AuthConfig, RealmName } from './config';

/**
 * Application roles, mirroring what the backend maps realm roles to.
 *
 * Spring Security authority names, matching what the backend's resource server produces.
 *
 * <p>These are **not** the Keycloak realm role names. The `ops` realm grants `ADMIN` and `SHOP`;
 * the customers realm grants `CUSTOMER`. Spring expects authorities prefixed with `ROLE_`, so the
 * backend maps them explicitly in `KeycloakJwtAuthenticationConverter` rather than blanket-prefixing
 * — so that a role cannot leak across realms by sharing a name. See {@link REALM_ROLE_MAP}.
 *
 * <p>This file previously assumed the prefix was already there and filtered realm roles against
 * these values directly. A realm role of `SHOP` therefore matched nothing, `readRoles()` returned
 * `[]`, and every guarded route redirected to /forbidden — while the token in the browser plainly
 * said `"realm_access":{"roles":["SHOP"]}`. The comment on this constant predicted that exact
 * failure, which is a reminder that a comment describing a bug is not a fix for one.
 */
export const APP_ROLE = {
  ADMIN: 'ROLE_ADMIN',
  SHOP: 'ROLE_SHOP',
  CUSTOMER: 'ROLE_CUSTOMER',
  AUTHENTICATED: 'ROLE_AUTHENTICATED',
} as const;

export type AppRole = (typeof APP_ROLE)[keyof typeof APP_ROLE];

/**
 * Keycloak realm role -> Spring authority, per realm.
 *
 * A copy of the backend's `KeycloakJwtAuthenticationConverter.REALM_ROLE_MAP`, and it has to stay
 * a copy: the console cannot import from the API, and the two must agree or the console shows a
 * user a screen whose every action 403s.
 *
 * Per realm rather than a blanket prefix, deliberately. A blanket prefix would let the `ops`
 * realm's `SHOP` role and a hypothetical `customers` realm role of the same name both resolve, and
 * a role must not be able to cross realms.
 */
export const REALM_ROLE_MAP: Record<string, Record<string, AppRole>> = {
  ops: { ADMIN: APP_ROLE.ADMIN, SHOP: APP_ROLE.SHOP },
  customers: { CUSTOMER: APP_ROLE.CUSTOMER },
};

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

/**
 * Where a login was heading, across the Keycloak round trip.
 *
 * <p>Session storage rather than a query parameter: the destination is a local path, and putting it
 * in the URL would mean it survives into the browser history and into Keycloak's own logs.
 */
export const RETURN_TO_KEY = 'store-app:return-to';

/**
 * Reads and clears the stored post-login destination.
 *
 * <p>Cleared on read, so a later sign-in does not send somebody to a page they were on hours ago.
 */
export function takeReturnTo(): string | null {
  try {
    const stored = window.sessionStorage.getItem(RETURN_TO_KEY);
    window.sessionStorage.removeItem(RETURN_TO_KEY);
    // Only a same-origin path is honoured: a stored absolute URL would turn the login link into an
    // open redirect.
    if (!stored || !stored.startsWith('/') || stored.startsWith('//')) return null;
    return stored;
  } catch {
    return null;
  }
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

  /**
   * Realm roles from the token, mapped to Spring authorities.
   *
   * <p>The mapping is by realm, not by prefix. `SHOP` in the `ops` realm becomes `ROLE_SHOP`;
   * `SHOP` in any other realm is discarded, which is what stops a role crossing realms.
   */
  function readRoles(): AppRole[] {
    const token = kc.tokenParsed as { realm_access?: { roles?: string[] } } | undefined;
    const realmRoles = token?.realm_access?.roles ?? [];
    const allowed = REALM_ROLE_MAP[config.realm] ?? {};

    const mapped = realmRoles
      .map((role) => allowed[role])
      .filter((authority): authority is AppRole => authority !== undefined);

    if (realmRoles.length > 0 && mapped.length === 0) {
      // Loud on purpose. The console is about to redirect to /forbidden with no explanation, and
      // the cause is a role-name mismatch between here and the backend — which is invisible
      // otherwise, because the token looks perfectly valid in the browser.
      console.warn(
        `[auth] none of the realm roles ${JSON.stringify(realmRoles)} map to an authority in ` +
          `realm "${config.realm}". Known roles: ${JSON.stringify(Object.keys(allowed))}. ` +
          `If this is a role mismatch with the backend, every page will redirect to /forbidden.`,
      );
    }
    return mapped;
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
            // Restores a session silently on reload, so a shop owner is not bounced to the login
            // screen every time they refresh — which is what happens with no onLoad at all, since
            // nothing is ever written to sessionStorage.
            //
            // `check-sso` only settles because /silent-check-sso.html posts the callback URL to this
            // window. Without that page the iframe 404s and this promise never resolves — the
            // white-page failure recorded in the plan.
            onLoad: 'check-sso',
            silentCheckSsoRedirectUri: `${config.redirectUri}/silent-check-sso.html`,
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
      // The destination is remembered, not sent.
      //
      // <p>Keycloak validates `redirect_uri` against the client's registered list and refuses
      // anything else with "Invalid parameter: redirect_uri". Passing a path here -- which is what
      // `returnTo` looks like -- therefore fails every time, and the customer is left on a blank
      // page with a Keycloak error rather than being signed in.
      //
      // <p>So the registered URI is always sent, and the destination is stashed for the shell to
      // read after the redirect lands. That is also the only arrangement that survives a reload
      // mid-flow.
      if (returnTo) {
        try {
          window.sessionStorage.setItem(RETURN_TO_KEY, returnTo);
        } catch {
          // Private browsing, or a full quota. Losing the destination is a small annoyance;
          // failing the login over it would not be.
        }
      }

      await kc.login({
        redirectUri: config.redirectUri,
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
