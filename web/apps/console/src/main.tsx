import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createRoot } from 'react-dom/client';
import { Navigate, RouterProvider, createBrowserRouter } from 'react-router-dom';
import { StrictMode, type ReactNode } from 'react';

import { consoleAuthConfig, createKeycloakAdapter, type AuthAdapter } from '@store-app/auth';

import '@store-app/ui/styles.css';
import './styles.css';

import { AppShell } from './layouts/app-shell';
import { NotFound } from './pages/not-found';
import { LoginPage } from './pages/login';
import { ForbiddenPage } from './pages/forbidden';
import { AdminHome } from './pages/admin-home';
import { ShopHome } from './pages/shop-home';
import { ShopTheme } from './pages/shop-theme';

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
      { index: true, element: <Navigate to="/shop" replace /> },
      { path: 'admin', element: <RequireRole auth={auth} roles={['ROLE_ADMIN']}><AdminHome /></RequireRole> },
      { path: 'shop', element: <RequireRole auth={auth} roles={['ROLE_SHOP']}><ShopHome /></RequireRole> },
      {
        path: 'shop/theme',
        element: (
          <RequireRole auth={auth} roles={['ROLE_SHOP']}>
            <ShopTheme />
          </RequireRole>
        ),
      },
      { path: 'forbidden', element: <ForbiddenPage /> },
    ],
  },
  { path: '*', element: <NotFound /> },
]);

function RequireRole({
  auth: adapter,
  roles,
  children,
}: {
  auth: AuthAdapter;
  roles: Array<'ROLE_ADMIN' | 'ROLE_SHOP'>;
  children: ReactNode;
}) {
  const session = adapter.getSession();
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
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);

void auth.init().catch((error: unknown) => {
  console.error('[auth] initialisation failed; the console will require an explicit sign-in', error);
});
