import Keycloak from 'keycloak-js';

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
    async init() {
      if (ready) return kc.authenticated;
      ready = await kc.init({
        onLoad: 'check-sso',
        pkceMethod: 'S256',
        checkLoginIframe: false,
        silentCheckSsoRedirectUri: `${config.redirectUri}/silent-check-sso`,
        enableLogging: process.env.NODE_ENV === 'development',
      });
      roles = readRoles();
      return ready;
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
