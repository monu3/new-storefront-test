/**
 * Realm-aware auth configuration.
 *
 * The platform deliberately runs two Keycloak realms (D3): `ops` issues ADMIN and SHOP tokens,
 * `customers` issues CUSTOMER tokens. A single adapter is parameterised by realm so the two
 * frontends cannot accidentally authenticate against the wrong one — the console cannot receive
 * a customer token, and the storefront cannot receive a shop token.
 */

export const REALM = {
  /** Realm holding ADMIN and SHOP. Used by the Vite console only. */
  OPS: 'ops',
  /** Realm holding CUSTOMER. Used by the Next.js portal only. */
  CUSTOMERS: 'customers',
} as const;

export type RealmName = (typeof REALM)[keyof typeof REALM];

export interface AuthConfig {
  baseUrl: string;
  realm: RealmName;
  clientId: string;
  /** Where the app lives after login. */
  redirectUri: string;
  /** Where to land after logout. */
  postLogoutRedirectUri: string;
}

function env(key: string, fallback: string): string {
  const value = process.env[key];
  if (!value) {
    if (process.env.NODE_ENV === 'production') {
      throw new Error(`Missing required env var: ${key}`);
    }
    return fallback;
  }
  return value;
}

/**
 * Console config. Only `http://localhost:5173` is a valid origin for this client; Keycloak
 * enforces the redirect URI list server-side, so a customer token cannot be obtained here.
 */
export const consoleAuthConfig: AuthConfig = {
  baseUrl: env('VITE_KEYCLOAK_URL', 'https://auth-master.yenyasoft.com'),
  realm: REALM.OPS,
  clientId: env('VITE_KEYCLOAK_OPS_CLIENT_ID', 'store-app-console'),
  redirectUri: env('VITE_APP_URL', 'http://localhost:5173'),
  postLogoutRedirectUri: env('VITE_APP_URL', 'http://localhost:5173'),
};

/** Customer portal config. */
export const customerAuthConfig: AuthConfig = {
  baseUrl: env('NEXT_PUBLIC_KEYCLOAK_URL', 'https://auth-master.yenyasoft.com'),
  realm: REALM.CUSTOMERS,
  clientId: env('NEXT_PUBLIC_KEYCLOAK_CLIENT_ID', 'store-app-customer-web'),
  redirectUri: env('NEXT_PUBLIC_APP_URL', 'http://localhost:3000'),
  postLogoutRedirectUri: env('NEXT_PUBLIC_APP_URL', 'http://localhost:3000'),
};
