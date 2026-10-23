import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { homeRouteFor, resolveConsoleUser, type AuthAdapter } from '@store-app/auth';
import { Button, Card, CardContent, CardDescription, CardHeader, CardTitle } from '@store-app/ui';

export function LoginPage({ auth }: { auth: AuthAdapter }) {
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /**
   * Hands off to Keycloak's registration form.
   *
   * <p>Where the new account should land afterwards. `/shop` rather than `/`, because the app
   * shell reads the profile and forwards an unfinished owner to the wizard on its own -- routing
   * to `/` here would duplicate that decision in a second place, and the two would eventually
   * disagree.
   */
  async function startRegistration() {
    setBusy(true);
    setError(null);
    try {
      await auth.register('/shop');
    } catch (cause) {
      setError(
        cause instanceof Error
          ? `Could not reach the sign-up page: ${cause.message}`
          : 'Could not reach the sign-up page.',
      );
      setBusy(false);
    }
  }

  useEffect(() => {
    void auth.init().then((authenticated) => {
      if (authenticated) {
        navigate(homeRouteFor(resolveConsoleUser(auth.getSession())), { replace: true });
      }
    });
  }, [auth, navigate]);

  async function signIn() {
    setBusy(true);
    setError(null);
    try {
      await auth.login(window.location.origin + '/');
    } catch (cause) {
      setError(
        cause instanceof Error
          ? cause.message
          : 'Could not reach Keycloak. Check the console client exists and allows this origin.',
      );
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-dvh items-center justify-center p-4">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle>Sign in to the console</CardTitle>
          <CardDescription>
            Shop owners and platform admins sign in here. Customers use the storefront instead.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {error && (
            <p className="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm">
              {error}
            </p>
          )}
          <Button className="w-full" onClick={signIn} disabled={busy}>
            {busy ? 'Redirecting…' : 'Continue with Keycloak'}
          </Button>
          <p className="text-xs text-muted-foreground">
            Signs you in to the <code>ops</code> realm, which issues the SHOP and ADMIN roles.
          </p>

          {/* Registration lives in Keycloak, not here. Going through the adapter rather than a
              hand-built link is what makes it work: the realm requires PKCE, so Keycloak refuses
              a URL with no code_challenge and bounces straight back here with an error instead of
              showing a form. `auth.register()` mints the challenge and keeps the verifier for the
              token exchange. Linking Keycloak's own form rather than reimplementing it keeps
              password policy and email verification in one place instead of two. */}
          <div className="border-t pt-4">
            <Button
              variant="link"
              className="h-auto p-0"
              onClick={() => void startRegistration()}
              disabled={busy}
            >
              Start a new shop
            </Button>
            <p className="mt-1 text-xs text-muted-foreground">
              Creates an account in the <code>ops</code> realm. You will be asked for your shop name
              and what kind of shop it is next.
            </p>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
