'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';

import { Button, Card, CardContent } from '@store-app/ui';
import { RegisterButton } from '../../components/register-button';

import { auth } from '../../lib/auth';

/**
 * Sign in, and hand off to Keycloak.
 *
 * <p><b>An explicit login, not `check-sso`.</b> Keycloak's silent check loads a hidden iframe and
 * waits for it to post a callback back; this app does not serve that route, so the promise never
 * settles and the page hangs on a spinner forever. An explicit redirect is also the honest
 * behaviour for a shop: a visitor should be asked who they are rather than silently relying on an
 * ambient session.
 *
 * <p><b>The button is disabled until the adapter is ready</b> rather than firing a login from a
 * half-initialised Keycloak instance, which fails in a way that looks like a login problem.
 */
export default function LoginPage() {
  const router = useRouter();
  const [alreadyIn, setAlreadyIn] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // Already signed in? Go where they were going rather than making them log in twice.
    //
    // <p>This only *reports*; it never gates the button. An earlier version disabled the button
    // until the adapter finished initialising, and since `isAuthenticated()` is itself `init'd &&
    // authenticated`, that flag never turned true on a fresh page load and the button stayed
    // permanently disabled. keycloak-js's own `login()` initialises as it goes, so there is nothing
    // to wait for.
    if (auth.isAuthenticated()) {
      setAlreadyIn(true);
      router.replace('/account/orders');
    }
  }, [router]);

  const go = async () => {
    setBusy(true);
    setError(null);
    try {
      // Remembered for after the round trip. The adapter does not send it to Keycloak -- the
      // redirect_uri has to be a registered URI, and a path is not one.
      await auth.login('/account/orders');
    } catch (e) {
      setBusy(false);
      setError(
        e instanceof Error
          ? e.message
          : 'Could not reach the sign-in service. Please try again.',
      );
    }
  };

  return (
    <div className="mx-auto max-w-md py-10">
      <Card>
        <CardContent className="p-6">
          <h1 className="text-xl font-semibold">Sign in</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            Sign in to see your orders, track a delivery, and use your wishlist.
          </p>

          <Button className="mt-5 w-full" disabled={busy || alreadyIn} onClick={() => void go()}>
            {busy ? 'Redirecting…' : alreadyIn ? 'Already signed in' : 'Continue with Keycloak'}
          </Button>

          {error ? (
            <p role="alert" className="mt-3 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">
              {error}
            </p>
          ) : null}

          <p className="mt-4 text-xs text-muted-foreground">
            You do not need an account to order — guest checkout is available on every shop. Signing
            in adds order history, saved addresses and a wishlist.
          </p>

          {/* Registration is Keycloak's own form, and going through the adapter is what makes it
              work: the realm requires PKCE, so a link with no code_challenge is bounced straight
              back here with an error rather than opening a form. The client id it carries is also
              what puts the customers realm's default client role on the new account. */}
          <div className="mt-4 flex justify-center border-t pt-4">
            <RegisterButton variant="default" />
          </div>
        </CardContent>
      </Card>
    </div>
  );
}