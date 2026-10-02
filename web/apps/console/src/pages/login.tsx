import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { homeRouteFor, resolveConsoleUser, type AuthAdapter } from '@store-app/auth';
import { Button, Card, CardContent, CardDescription, CardHeader, CardTitle } from '@store-app/ui';

export function LoginPage({ auth }: { auth: AuthAdapter }) {
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

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
        </CardContent>
      </Card>
    </div>
  );
}
