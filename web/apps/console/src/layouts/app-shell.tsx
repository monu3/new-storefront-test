import { useEffect, useState } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';

import {
  APP_ROLE,
  homeRouteFor,
  resolveConsoleUser,
  type AuthAdapter,
} from '@store-app/auth';
import { Button } from '@store-app/ui';
import { UI_ICONS } from '@store-app/icons';

const NAV = [
  { to: '/admin', label: 'Admin', role: APP_ROLE.ADMIN },
  { to: '/shop', label: 'Shop', role: APP_ROLE.SHOP },
] as const;

/**
 * Console shell.
 *
 * The sidebar only shows the section the signed-in role can actually use. This is a UX
 * affordance, not a security boundary: the backend re-checks every role, and hiding a link
 * protects no data.
 */
export function AppShell({ auth }: { auth: AuthAdapter }) {
  const navigate = useNavigate();
  const [ready, setReady] = useState(false);
  const [user, setUser] = useState(() => resolveConsoleUser(auth.getSession()));

  useEffect(() => {
    // `.catch` rather than a bare `void …then(…)`: an init failure would otherwise surface as an
    // unhandled promise rejection with no route to a sensible screen, and `ready` would never be
    // set — leaving the shell stuck on "Loading…" forever.
    //
    // Failing closed, to the login screen: an auth adapter that cannot initialise has no session
    // to vouch for, and treating that as "signed in" would render a shell full of links that all
    // 401. main.tsx logs the underlying error.
    let cancelled = false;

    void auth
      .init()
      .then((authenticated) => {
        if (cancelled) return;
        setUser(resolveConsoleUser(auth.getSession()));
        setReady(true);
        if (!authenticated) {
          navigate('/login');
        }
      })
      .catch((error: unknown) => {
        console.error('[auth] initialisation failed', error);
        if (cancelled) return;
        setReady(true);
        navigate('/login');
      });

    // React StrictMode mounts, unmounts and remounts effects in development, so a second init
    // would otherwise call setState after unmount and warn. Harmless, but it hides real warnings.
    return () => {
      cancelled = true;
    };
  }, [auth, navigate]);

  if (!ready) {
    return (
      <div className="flex min-h-dvh items-center justify-center text-muted-foreground">
        Loading…
      </div>
    );
  }

  if (!user.session.authenticated) {
    return <p className="p-8">Redirecting to sign in…</p>;
  }

  const visibleNav = NAV.filter((item) => user.session.roles.includes(item.role));

  return (
    <div className="flex min-h-dvh">
      <aside className="w-56 shrink-0 border-r bg-card">
        <div className="border-b p-4">
          <p className="text-sm font-semibold">store.app console</p>
          <p className="truncate text-xs text-muted-foreground">
            {user.session.email ?? user.session.fullName}
          </p>
        </div>
        <nav className="p-2">
          {visibleNav.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                `block rounded-md px-3 py-2 text-sm ${
                  isActive ? 'bg-accent font-medium' : 'hover:bg-accent/50'
                }`
              }
            >
              {item.label}
            </NavLink>
          ))}
        </nav>
        <div className="border-t p-2">
          <Button
            variant="ghost"
            size="sm"
            className="w-full justify-start"
            onClick={async () => {
              await auth.logout();
              navigate('/login');
            }}
          >
            <UI_ICONS.refresh className="h-4 w-4" aria-hidden />
            Sign out
          </Button>
        </div>
      </aside>

      <main className="min-w-0 flex-1 p-6">
        <Outlet />
      </main>
    </div>
  );
}

export { homeRouteFor };
