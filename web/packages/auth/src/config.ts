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

/**
 * The development Keycloak, used when no URL is configured.
 *
 * <p>Named once because two apps' configs fall back to it and the two previously repeated the
 * literal independently.
 */
const DEFAULT_KEYCLOAK_URL = 'https://auth-master.yenyasoft.com';

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
  // Getters, not plain values.
  //
  // <p>Both configs live in this shared package, so importing `@store-app/auth` evaluates *both*.
  // With plain values the customer app's build threw `Missing required env var: VITE_KEYCLOAK_URL` —
  // a variable it has no use for — because evaluating `consoleAuthConfig` ran `env()` for the
  // console's own variable, and `env()` is a hard failure in production by design.
  //
  // <p>Deferring each read to first access means an app only ever resolves its own variables, while
  // a missing one still fails hard. The object shape is unchanged, so no call site moves.
  get baseUrl() {
    return env('VITE_KEYCLOAK_URL', DEFAULT_KEYCLOAK_URL);
  },
  realm: REALM.OPS,
  get clientId() {
    return env('VITE_KEYCLOAK_OPS_CLIENT_ID', 'store-app-console');
  },
  get redirectUri() {
    return env('VITE_APP_URL', 'http://localhost:5173');
  },
  get postLogoutRedirectUri() {
    return env('VITE_APP_URL', 'http://localhost:5173');
  },
};

/** Customer portal config. */
export const customerAuthConfig: AuthConfig = {
  // Lazy for the same reason as the console config above: the shared package must not require the
  // other app's variables to be present.
  get baseUrl() {
    return env('NEXT_PUBLIC_KEYCLOAK_URL', DEFAULT_KEYCLOAK_URL);
  },
  realm: REALM.CUSTOMERS,
  get clientId() {
    return env('NEXT_PUBLIC_KEYCLOAK_CLIENT_ID', 'store-app-customer-web');
  },
  get redirectUri() {
    return env('NEXT_PUBLIC_APP_URL', 'http://localhost:3000');
  },
  get postLogoutRedirectUri() {
    return env('NEXT_PUBLIC_APP_URL', 'http://localhost:3000');
  },
};
