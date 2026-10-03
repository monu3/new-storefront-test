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

/**
 * Reads an environment variable that works in **both** frontends.
 *
 * <p>`@store-app/auth` is a linked workspace package shared by the Vite console and the Next.js
 * storefront, and the two expose configuration differently:
 *
 * <ul>
 *   <li>Next.js provides `process.env`, including `NEXT_PUBLIC_*` at build time.</li>
 *   <li>Vite provides `import.meta.env` for `VITE_*`, and **does not define `process` in the
 *       browser at all**.</li>
 * </ul>
 *
 * <p>Referencing `process.env[key]` directly therefore threw `ReferenceError: process is not
 * defined` in the console, blanking the whole app — and Vite's `define` cannot rescue it, because
 * that only rewrites <em>static</em> `process.env.NAME` text, not a computed index. Both sources
 * are tried, with `typeof process` guarding the access so the bare reference is never evaluated in
 * a browser.
 *
 * <p>The Vite branch matters for a second reason: this file lives outside the app root, so it is
 * served through Vite's `/@fs/` path and is not subject to the app's usual env rewriting.
 */
export function readEnv(key: string): string | undefined {
  // Node / Next.js. `typeof` is safe where a bare reference would throw.
  if (typeof process !== 'undefined' && process.env) {
    const fromProcess = process.env[key];
    if (fromProcess) return fromProcess;
  }

  // Vite.
  const viteEnv = (import.meta as unknown as { env?: Record<string, string | undefined> }).env;
  return viteEnv?.[key];
}

function env(key: string, fallback: string): string {
  const value = readEnv(key);
  if (!value) {
    // A missing variable is a warning in development and a hard failure in production, where a
    // silently-defaulted Keycloak URL would point production auth at a development realm.
    if (readEnv('NODE_ENV') === 'production') {
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
