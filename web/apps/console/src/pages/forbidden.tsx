import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';

import { APP_ROLE, type AuthAdapter } from '@store-app/auth';
import { Button, Card, CardContent, CardDescription, CardHeader, CardTitle } from '@store-app/ui';

/**
 * What a signed-in person is shown when their account has no role this app recognises.
 *
 * <p><b>Three different situations, three different pages.</b> Before this existed there was one
 * sentence — "your role does not permit this page" — which was correct for a customer who opened
 * the console by mistake, actively misleading for somebody who had just registered as a shop owner
 * and been refused every screen, and wrong in a third way for an admin who reached a shop route.
 *
 * <p>The middle case is the one worth writing about. A person who follows &ldquo;Start a new
 * shop&rdquo; creates a Keycloak account and lands here, and the most likely explanation by far is
 * that the realm's default client role was never set — which is a configuration mistake in
 * Keycloak, not something they did wrong. Saying so is the difference between them reporting a
 * real bug and them concluding the website is broken.
 *
 * <p>Roles come from the token and are therefore known before any request is made, so this decides
 * without a round trip and without guessing.
 */
export function ForbiddenPage({ auth }: { auth: AuthAdapter }) {
  const navigate = useNavigate();
  const [state, setState] = useState<'loading' | 'signedOut' | 'noRole' | 'wrongRole'>('loading');
  const [hasAdmin, setHasAdmin] = useState(false);

  useEffect(() => {
    let cancelled = false;

    // The adapter resolves the session, which is what every other role decision in this app is
    // made from. Guessing from localStorage here would mean two sources of truth for one fact.
    void auth
      .init()
      .then(() => {
        if (cancelled) return;

        const session = auth.getSession();

        if (!session.authenticated) {
          setState('signedOut');
          return;
        }

        setHasAdmin(session.roles.includes(APP_ROLE.ADMIN));

        // A brand-new registrant has an account and no recognised role. That is not "wrong area" —
        // it is "the thing that was supposed to give you a role did not".
        setState(
          session.roles.length === 0 ||
            (!session.roles.includes(APP_ROLE.SHOP) && !session.roles.includes(APP_ROLE.ADMIN))
            ? 'noRole'
            : 'wrongRole',
        );
      })
      .catch(() => {
        if (!cancelled) setState('signedOut');
      });

    return () => {
      cancelled = true;
    };
  }, [auth]);

  useEffect(() => {
    if (state === 'signedOut') {
      navigate('/login', { replace: true });
    }
  }, [state, navigate]);

  if (state === 'loading') {
    return (
      <div className="flex min-h-dvh items-center justify-center p-4">
        <Card className="w-full max-w-md text-center">
          <CardContent className="p-10 text-muted-foreground">Checking your access…</CardContent>
        </Card>
      </div>
    );
  }

  return (
    <div className="flex min-h-dvh items-center justify-center p-4">
      <Card className="w-full max-w-md text-center">
        <CardHeader>
          <CardTitle>
            {state === 'noRole' ? 'Your account has no access yet' : 'You do not have access here'}
          </CardTitle>
          <CardDescription>
            {state === 'noRole' ? (
              <>
                <p>
                  You are signed in, but this account has no shop or admin role attached to it — so
                  there is nothing here for you to open yet.
                </p>
                <p className="mt-3">
                  If you have just registered as a shop owner, this almost always means the account
                  was created without the shop role. It is a one-time setting on the
                  authentication server rather than anything you did, and it takes an administrator
                  to apply.
                </p>
              </>
            ) : hasAdmin ? (
              'You are signed in as an administrator, and this is a shop-owner page. Shop screens act on your shop, and an administrator does not have one.'
            ) : (
              'Your account is signed in as a shop owner, and this is an administrator page. Administrators are created separately.'
            )}
          </CardDescription>
        </CardHeader>

        <CardContent>
          <div className="flex justify-center gap-2">
            <Button asChild variant="outline">
              <a href="http://localhost:3000/shops">Browse shops</a>
            </Button>
            <Button asChild>
              <Link to={hasAdmin ? '/admin' : '/shop'}>
                {hasAdmin ? 'Go to the admin panel' : 'Go to my shop'}
              </Link>
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}