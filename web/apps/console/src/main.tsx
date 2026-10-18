import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import * as React from 'react';
import { createRoot } from 'react-dom/client';
import { Navigate, RouterProvider, createBrowserRouter } from 'react-router-dom';
import { StrictMode, type ReactNode } from 'react';

import {
  consoleAuthConfig,
  createKeycloakAdapter,
  homeRouteFor,
  resolveConsoleUser,
  type AuthAdapter,
} from '@store-app/auth';

import '@store-app/ui/styles.css';
import { Toaster } from '@store-app/ui';
import { setTokenProvider } from '@store-app/api-client';
import './styles.css';

import { AppShell } from './layouts/app-shell';
import { NotFound } from './pages/not-found';
import { LoginPage } from './pages/login';
import { ForbiddenPage } from './pages/forbidden';
import { ShopTheme } from './pages/shop-theme';
import { ShopDashboard } from './pages/shop-dashboard';
import { ShopOrders } from './pages/shop-orders';
import { ShopProducts } from './pages/shop-products';
import { ShopOrderDetail } from './pages/shop-order-detail';
import { ShopProductForm } from './pages/shop-product-form';
import { ShopSettingsPage } from './pages/shop-settings';
import { ShopDiscounts } from './pages/shop-discounts';
import { ShopAudience } from './pages/shop-audience';
import { ShopMediaLibrary } from './pages/shop-media';
import { ShopSubscription } from './pages/shop-subscription';
import { ShopReturns } from './pages/shop-returns';
import { AdminRevenue } from './pages/admin-revenue';
import { AdminPlans } from './pages/admin-plans';
import { AdminWallet } from './pages/admin-wallet';
import { AdminDashboard } from './pages/admin-dashboard';
import { AdminShops } from './pages/admin-shops';
import { AdminUsers } from './pages/admin-users';
import { AdminAudit } from './pages/admin-audit';
import { AdminReports } from './pages/admin-reports';
import { AdminExplorer } from './pages/admin-explorer';
import { AdminSupport } from './pages/admin-support';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: (failureCount, error) => {
        // Retrying a 401 just burns attempts; the session needs a fresh login, not a retry.
        const status = (error as { status?: number })?.status;
        if (status === 401 || status === 403) return false;
        return failureCount < 2;
      },
      refetchOnWindowFocus: false,
    },
  },
});

const auth: AuthAdapter = createKeycloakAdapter(consoleAuthConfig);

/**
 * Placeholder routes. Phase 0 proves the shell, routing and the realm split; feature screens
 * arrive with their phases.
 *
 * Guards here are UX only. Every rule is enforced again by the backend's `@PreAuthorize` and
 * tenant filter, so editing local storage gains a user nothing.
 */
const router = createBrowserRouter([
  {
    path: '/login',
    element: <LoginPage auth={auth} />,
  },
  {
    path: '/',
    element: <AppShell auth={auth} />,
    children: [
      { index: true, element: <Landing auth={auth} /> },
      {
        path: 'admin',
        element: (
          <RequireRole auth={auth} roles={['ROLE_ADMIN']}>
            <AdminDashboard auth={auth} />
          </RequireRole>
        ),
      },
      {
        path: 'admin/shops',
        element: (
          <RequireRole auth={auth} roles={['ROLE_ADMIN']}>
            <AdminShops auth={auth} />
          </RequireRole>
        ),
      },
      {
        path: 'admin/users',
        element: (
          <RequireRole auth={auth} roles={['ROLE_ADMIN']}>
            <AdminUsers auth={auth} />
          </RequireRole>
        ),
      },
      {
        path: 'admin/audit',
        element: (
          <RequireRole auth={auth} roles={['ROLE_ADMIN']}>
            <AdminAudit auth={auth} />
          </RequireRole>
        ),
      },
      {
        path: 'admin/reports',
        element: (
          <RequireRole auth={auth} roles={['ROLE_ADMIN']}>
            <AdminReports auth={auth} />
          </RequireRole>
        ),
      },
      {
        path: 'admin/explorer',
        element: (
          <RequireRole auth={auth} roles={['ROLE_ADMIN']}>
            <AdminExplorer auth={auth} />
          </RequireRole>
        ),
      },
      {
        path: 'admin/support',
        element: (
          <RequireRole auth={auth} roles={['ROLE_ADMIN']}>
            <AdminSupport auth={auth} />
          </RequireRole>
        ),
      },
      {
        path: 'shop',
        element: (
          <RequireRole auth={auth} roles={['ROLE_SHOP']}>
            <ShopDashboard auth={auth} />
          </RequireRole>
        ),
      },
      {
        path: 'shop/orders',
        element: (
          <RequireRole auth={auth} roles={['ROLE_SHOP']}>
            <ShopOrders auth={auth} />
          </RequireRole>
        ),
      },
      {
        path: 'shop/products',
        element: (
          <RequireRole auth={auth} roles={['ROLE_SHOP']}>
            <ShopProducts auth={auth} />
          </RequireRole>
        ),
      },
      {
        path: 'shop/orders/:orderId',
        element: (
          <RequireRole auth={auth} roles={['ROLE_SHOP']}>
            <ShopOrderDetail auth={auth} />
          </RequireRole>
        ),
      },
      {
        path: 'shop/products/new',
        element: (
          <RequireRole auth={auth} roles={['ROLE_SHOP']}>
            <ShopProductForm auth={auth} />
          </RequireRole>
        ),
      },
      {
        path: 'shop/discounts',
        element: (
          <RequireRole auth={auth} roles={['ROLE_SHOP']}>
            <ShopDiscounts auth={auth} />
          </RequireRole>
        ),
      },
      {
        path: 'shop/audience',
        element: (
          <RequireRole auth={auth} roles={['ROLE_SHOP']}>
            <ShopAudience auth={auth} />
          </RequireRole>
        ),
      },
      {
        path: 'shop/media',
        element: (
          <RequireRole auth={auth} roles={['ROLE_SHOP']}>
            <ShopMediaLibrary auth={auth} />
          </RequireRole>
        ),
      },
      {
        path: 'shop/settings',
        element: (
          <RequireRole auth={auth} roles={['ROLE_SHOP']}>
            <ShopSettingsPage auth={auth} />
          </RequireRole>
        ),
      },
      {
        path: 'shop/theme',
        element: (
          <RequireRole auth={auth} roles={['ROLE_SHOP']}>
            <ShopTheme auth={auth} />
          </RequireRole>
        ),
      },
      {
        path: 'shop/returns',
        element: (
          <RequireRole auth={auth} roles={['ROLE_SHOP']}>
            <ShopReturns auth={auth} />
          </RequireRole>
        ),
      },
      {
        path: 'shop/billing',
        element: (
          <RequireRole auth={auth} roles={['ROLE_SHOP']}>
            <ShopSubscription auth={auth} />
          </RequireRole>
        ),
      },
      {
        path: 'admin/revenue',
        element: (
          <RequireRole auth={auth} roles={['ROLE_ADMIN']}>
            <AdminRevenue auth={auth} />
          </RequireRole>
        ),
      },
      {
        path: 'admin/wallet',
        element: (
          <RequireRole auth={auth} roles={['ROLE_ADMIN']}>
            <AdminWallet auth={auth} />
          </RequireRole>
        ),
      },
      {
        path: 'admin/plans',
        element: (
          <RequireRole auth={auth} roles={['ROLE_ADMIN']}>
            <AdminPlans auth={auth} />
          </RequireRole>
        ),
      },
      { path: 'forbidden', element: <ForbiddenPage /> },
    ],
  },
  { path: '*', element: <NotFound /> },
]);

/**
 * Sends a signed-in visitor to the right home page.
 *
 * <p><b>Role-aware, which the hardcoded `{index: true}` redirect was not.</b> It always sent
 * everyone to `/shop`, so an admin whose Keycloak redirect landed on the app root was bounced to
 * `/forbidden` — while the login button, which uses `homeRouteFor`, sent them to `/admin`
 * correctly. Two entry points, two different answers, and the one Keycloak actually uses was the
 * broken one: the redirect returns to `redirectUri` (the root), not to the page the person pressed
 * the button on.
 *
 * <p>It has to wait for the session, for the same reason `RequireRole` does: deciding before
 * `init()` resolves would send a signed-in admin to `/login` on every hard refresh.
 */
function Landing({ auth: adapter }: { auth: AuthAdapter }) {
  const [ready, setReady] = React.useState(false);
  const [session, setSession] = React.useState(() => adapter.getSession());

  React.useEffect(() => {
    let cancelled = false;
    void adapter
      .init()
      .then(() => {
        if (cancelled) return;
        setSession(adapter.getSession());
        setReady(true);
      })
      .catch(() => {
        // Fail to /login rather than to a role guess built on nothing.
        if (cancelled) return;
        setSession({ authenticated: false, roles: [] });
        setReady(true);
      });
    return () => {
      cancelled = true;
    };
  }, [adapter]);

  if (!ready) {
    return <div className="p-8 text-sm text-muted-foreground">Checking your access…</div>;
  }
  return <Navigate to={homeRouteFor(resolveConsoleUser(session))} replace />;
}

/**
 * Blocks a route until the role is known, then redirects if it is wrong.
 *
 * <p><b>This must wait for auth to initialise before it decides anything.</b> It used to read
 * `getSession()` synchronously, which returns `{authenticated: false}` until `kc.init()` resolves.
 * On a soft navigation that was harmless — init had already run — but on a full page load it sent
 * the signed-in owner straight back to `/login`. The symptom was that the dashboard worked when
 * you clicked into it, but refreshing or opening a deep link bounced you out.
 *
 * <p>Two states, not one: "still loading" and "loaded, and the role is wrong". Collapsing them is
 * what produced the bug — there is no difference between "not signed in yet" and "not signed in"
 * until init has actually answered.
 */
function RequireRole({
  auth: adapter,
  roles,
  children,
}: {
  auth: AuthAdapter;
  roles: Array<'ROLE_ADMIN' | 'ROLE_SHOP'>;
  children: ReactNode;
}) {
  const [ready, setReady] = React.useState(false);
  const [session, setSession] = React.useState(() => adapter.getSession());

  React.useEffect(() => {
    let cancelled = false;
    void adapter
      .init()
      .then(() => {
        if (cancelled) return;
        setSession(adapter.getSession());
        setReady(true);
      })
      .catch((error: unknown) => {
        // Fail closed to the login screen; an adapter that cannot init has no session to trust.
        console.error('[auth] initialisation failed', error);
        if (cancelled) return;
        setSession({ authenticated: false, roles: [] });
        setReady(true);
      });
    return () => {
      cancelled = true;
    };
  }, [adapter]);

  if (!ready) {
    return <div className="p-8 text-sm text-muted-foreground">Checking your access…</div>;
  }
  if (!session.authenticated) {
    return <Navigate to="/login" replace />;
  }
  if (!roles.some((r) => session.roles.includes(r))) {
    return <Navigate to="/forbidden" replace />;
  }
  return <>{children}</>;
}

const container = document.getElementById('root');
if (!container) {
  throw new Error('#root not found');
}

/**
 * Render first, initialise auth second.
 *
 * <p>The original order was `await auth.init()` then render, so that an already-signed-in user
 * would not see the login screen flash on a reload. In exchange, **any** auth failure left a
 * permanently white page — `init()` rejecting, or never settling, meant React never mounted and
 * there was no error to look at. That is the worst possible symptom, and the flash it was
 * avoiding is cosmetic.
 *
 * <p>So: mount immediately and let `RequireRole` sort out the session. It already redirects an
 * unauthenticated visitor to `/login`, so the user sees the login screen a few milliseconds
 * later instead of a blank page.
 *
 * <p>The error is logged rather than swallowed. A silent catch here is what made this take an
 * hour to find.
 */
createRoot(container).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      {/* Mounted once at the root so any page can raise a toast without owning the outlet.
          Without it `toast()` queues a message that is never rendered and the call is silently
          lost, which reads as "the app ignores my errors". */}
      <Toaster />
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);

// The shared API client needs to know how to get a token. Registering it here means every page
// that uses `api` is authenticated without each one remembering to pass a header -- which is
// exactly how the theme editor ended up silently unauthenticated.
setTokenProvider(async () => {
  try {
    return await auth.ensureFreshToken();
  } catch {
    // No session, or the refresh failed. An anonymous request is the correct outcome: the server
    // answers 401 and the page's own error handling decides what to show.
    return null;
  }
});

void auth.init().catch((error: unknown) => {
  console.error('[auth] initialisation failed; the console will require an explicit sign-in', error);
});
