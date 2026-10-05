import { customerAuthConfig, createKeycloakAdapter, type AuthAdapter } from '@store-app/auth';

/**
 * The customer app's single Keycloak adapter.
 *
 * <p><b>One instance, deliberately.</b> The shell and the login page each building their own looked
 * harmless and is not: two `keycloak-js` instances means two initialisations, two silent-check
 * lifecycles, and two copies of `isAuthenticated()` that can disagree. What that produced was a
 * Sign-in button that stayed disabled and a header that never showed the account links, with no
 * error anywhere — because each instance was internally consistent and they were simply talking
 * past each other.
 *
 * <p>The app's `login` and `logout` are full-page redirects, so there is no "navigate" to keep in
 * sync; the one thing that must be shared is the adapter, and this module is where that lives.
 */
export const auth: AuthAdapter = createKeycloakAdapter(customerAuthConfig);

/** Whether somebody is signed in. Safe to call before initialisation: it answers false. */
export function isSignedIn(): boolean {
  return auth.isAuthenticated();
}

/** The current session, or a signed-out stand-in when nobody is. */
export function currentSession() {
  const session = auth.getSession();
  return session.authenticated ? session : null;
}

/**
 * Initialises the adapter once.
 *
 * <p>Called from the shell so the session is resolved before any page asks for a token. Safe to
 * call repeatedly: the adapter memoises its own initialisation promise.
 */
export async function initAuth(): Promise<boolean> {
  return auth.init();
}