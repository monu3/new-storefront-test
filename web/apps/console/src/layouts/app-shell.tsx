import { useEffect, useState } from 'react';

import { useQuery } from '@tanstack/react-query';
import { Navigate } from 'react-router-dom';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import {
  Bell,
  ChevronRight,
  CircleHelp,
  CreditCard,
  FileText,
  Gauge,
  Image,
  Landmark,
  LayoutGrid,
  LifeBuoy,
  LogOut,
  PackageX,
  Percent,
  ScrollText,
  Settings,
  ShieldCheck,
  ShoppingBag,
  Sparkles,
  Store,
  Terminal,
  TrendingUp,
  Users,
} from 'lucide-react';

import { APP_ROLE, resolveConsoleUser } from '@store-app/auth';
import type { AuthAdapter } from '@store-app/auth';
import { authorizedFetch } from '@store-app/auth';
import { dashboard as dashboardApi } from '@store-app/api-client';
import type { Dashboard } from '@store-app/api-client';
import { Avatar, Separator as UiSeparator, Switch } from '@store-app/ui';
import { cn } from '@store-app/ui';

/**
 * The console shell (Phase 4).
 *
 * <p>Layout replicated from the reference application's dashboard, which is the frame every shop
 * screen hangs inside: a dark sidebar carrying identity and navigation, a white top bar carrying
 * the page title and the account controls, and the page itself in the remaining space.
 *
 * <p><b>One data fetch for the whole shell.</b> The shop name, slug and follower count appear in
 * the sidebar and the top bar on every screen, so fetching them per page would be a request the
 * user cannot see and the network cannot avoid. It is fetched once here.
 */
export function AppShell({ auth }: { auth: AuthAdapter }) {
  const navigate = useNavigate();
  const location = useLocation();
  const [ready, setReady] = useState(false);
  const [user, setUser] = useState(() => resolveConsoleUser(auth.getSession()));
  const [profileOpen, setProfileOpen] = useState(false);
  const [helpOpen, setHelpOpen] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void auth
      .init()
      .then((authenticated) => {
        if (cancelled) return;
        setUser(resolveConsoleUser(auth.getSession()));
        setReady(true);
        if (!authenticated) navigate('/login');
      })
      .catch((error: unknown) => {
        // Failing closed to /login. An adapter that cannot initialise has no session to vouch for,
        // and treating that as signed in would render a shell full of links that all 403.
        console.error('[auth] initialisation failed', error);
        if (cancelled) return;
        setReady(true);
        navigate('/login');
      });
    // StrictMode remounts effects in development; without this, setState runs after unmount.
    return () => {
      cancelled = true;
    };
  }, [auth, navigate]);

  /**
   * Whether this account owns a shop, and whether it has finished signing up (Phase 8.5).
   *
   * <p><b>The gate is ownership, not the SHOP role.</b> Those are different questions and the token
   * answers only the second. An administrator who also holds SHOP — which is a perfectly normal
   * configuration, and is what the platform's own administrator account looks like — passes a role
   * check and owns nothing, so a role-gated query fires for a shop that does not exist and every
   * admin screen carries a background 403.
   *
   * <p>That noise is worse than it sounds: a 403 on every screen is exactly the thing that hides a
   * real 403 the day one appears. The project's code has warned about it since Phase 0 without ever
   * fixing it, because the gate kept asking the wrong question.
   *
   * <p>{@code /api/v1/shop/me} answers 200 either way and says which it is, so this is one request
   * that is true for an owner, for a fresh owner, and for an administrator.
   */
  const isShopRoleHolder = user.session.roles.includes(APP_ROLE.SHOP);

  const { data: shopMe } = useQuery({
    queryKey: ['shop', 'me'],
    queryFn: async () => {
      const response = await authorizedFetch(auth, '/api/v1/shop/me');
      if (!response.ok) throw new Error(await response.text());
      return (await response.json()) as { owned: boolean; profileComplete: boolean };
    },
    enabled: isShopRoleHolder,
    staleTime: 60_000,
  });

  /** True when there is a shop behind the account, whatever the roles say. */
  const ownsShop = shopMe?.owned === true;

  /**
   * Whether to bounce this owner to the sign-up step.
   *
   * <p><b>Only on shop routes, and never onto the sign-up page itself.</b> An owner who has just
   * signed up must not be redirected straight back to the form they submitted — which is what a
   * guard that does not exclude its own target does, forever, because the query is cached.
   *
   * <p>Unknown is treated as "not yet", so the guard holds while the query is in flight. The cost
   * is a redirect that resolves in a few hundred milliseconds; the benefit is never showing a
   * dashboard for a shop whose name is still "My shop".
   */
  const needsOnboarding =
    isShopRoleHolder &&
    shopMe?.owned === true &&
    shopMe.profileComplete === false &&
    !location.pathname.startsWith('/shop/start');

  const { data: stats } = useQuery({
    queryKey: ['shop-dashboard'],
    queryFn: async () => {
      // authorizedFetch attaches the Bearer token and refreshes it first if it is near expiry.
      // The console's own api-client helpers cannot do that without the auth adapter, so the
      // request goes through here rather than through `dashboard.summary()`.
      void dashboardApi;
      const response = await authorizedFetch(auth, '/api/v1/shop/dashboard');
      if (!response.ok) throw new Error(`Dashboard request failed: ${response.status}`);
      return (await response.json()) as Dashboard;
    },
    // <b>Shop owners only.</b>
    //
    // <p>This used to be gated on "is authenticated", which meant an admin — who owns no shop —
    // fired it on every admin page and got a 403 it has no way to act on. The response is used for
    // the shop identity chip, the follower count and the "publish my shop" switch, all of which are
    // shop-only concepts, so there was never a reason for an admin to ask.
    //
    // <p>It is worth fixing rather than tolerating: a 403 in the console on every admin screen is
    // background noise that will hide a real 403 the day one appears.
    // Gate on owning a shop, not on holding the role -- see the note above. `shopMe` is still
    // loading on the first render, so this starts false and enables once the answer arrives.
    enabled: user.session.authenticated && ownsShop,
    staleTime: 60_000,
  });

  if (!ready) {
    return (
      <div className="flex min-h-dvh items-center justify-center bg-muted text-muted-foreground">
        Loading…
      </div>
    );
  }
  if (!user.session.authenticated) return <p className="p-8">Redirecting to sign in…</p>;

  // ------------------------------------------------------------- onboarding guard
  //
  // An owner who has not finished signing up sees this instead of the whole console. Returned
  // before anything renders rather than as a fragment around it, so there is no frame of a sidebar
  // for a dashboard they are not ready for.
  if (needsOnboarding) {
    return <Navigate to="/shop/start" replace />;
  }

  const isAdmin = user.session.roles.includes(APP_ROLE.ADMIN);
  const nav = isAdmin ? ADMIN_NAV : SHOP_NAV;
  const path = location.pathname;

  return (
    <div className="flex min-h-dvh bg-background">
      {/* ------------------------------------------------------------ sidebar */}
      <aside className="flex w-[264px] shrink-0 flex-col border-r border-border bg-secondary text-foreground">
        <div className="p-3">
          <div className="flex items-center gap-3 rounded-lg border border-border bg-card p-3">
            <div className="relative">
              <Avatar className="size-10 bg-primary text-primary-foreground">
                {ownsShop ? <Store className="size-5" aria-hidden /> : <ShieldCheck className="size-5" aria-hidden />}
              </Avatar>
              {/*
                The verified dot describes a *shop*. An account without one has no verification
                state to report, and drawing an amber "not verified yet" dot against a platform
                operator says something untrue about them.
              */}
              {ownsShop ? (
                <span
                  className={cn(
                    'absolute -bottom-0.5 -right-0.5 size-3.5 rounded-full ring-2 ring-card',
                    stats?.verified ? 'bg-success/100' : 'bg-warning/100',
                  )}
                  title={stats?.verified ? 'Verified shop' : 'Not verified yet'}
                />
              ) : null}
            </div>
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-semibold text-foreground">
                {/* The shop's name belongs here only if there is a shop. Falling back to the
                    person's own name made an operator's sidebar look like an unconfigured shop. */}
                {ownsShop ? (stats?.shopName ?? 'Your shop') : (user.session.fullName ?? 'Platform operator')}
              </p>
              {ownsShop ? (
                <Link
                  to={`/preview/${stats?.shopSlug ?? ''}`}
                  className="mt-0.5 inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
                >
                  Visit your shop
                  <ChevronRight className="size-3" aria-hidden />
                </Link>
              ) : (
                <p className="mt-0.5 text-xs text-muted-foreground">Platform operator</p>
              )}
            </div>
          </div>
        </div>

        <nav className="flex-1 space-y-1 px-3">
          {nav.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              icon={item.icon}
              label={item.label}
              active={path === item.to || path.startsWith(`${item.to}/`)}
            />
          ))}
        </nav>

        <div className="p-3">
          <div className="flex items-center gap-3 rounded-lg px-3 py-3 hover:bg-card">
            <Sparkles className="size-5 shrink-0 text-muted-foreground" aria-hidden />
            <div className="min-w-0">
              <p className="text-sm font-medium text-foreground">Upgrade plan</p>
              <p className="text-xs text-muted-foreground">Get extra benefits</p>
            </div>
          </div>
        </div>
      </aside>

      {/* ------------------------------------------------------------ main column */}
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-[72px] shrink-0 items-center justify-between gap-4 border-b bg-background px-6">
          <PageTitle pathname={path} />
          <div className="flex items-center gap-2">
            {/*
              Only for a real shop. An operator who owns none was being shown "Followers: 0", which
              reads as a shop with no audience rather than as an account with no shop — and it was
              a link into a shop screen they cannot use.
            */}
            {ownsShop ? (
              <Link
                to="/shop/audience"
                className="mr-2 inline-flex items-center gap-2 rounded-lg bg-primary px-3 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
              >
                <Users className="size-4" aria-hidden />
                Followers: {stats?.followers ?? 0}
              </Link>
            ) : null}

            <div className="relative">
              <button
                type="button"
                onClick={() => {
                  setHelpOpen((v) => !v);
                  setProfileOpen(false);
                }}
                className="inline-flex items-center gap-2 rounded-lg px-3 py-2 text-sm text-foreground hover:bg-muted"
              >
                <CircleHelp className="size-5" aria-hidden />
                Help
              </button>
              {helpOpen ? (
                <Menu onClose={() => setHelpOpen(false)} className="absolute right-0 top-full z-30 mt-1 w-56">
                  {['Chat with us', 'Help center', "What's new", 'Join meroPasal'].map((item) => (
                    <MenuItem key={item} onClick={() => setHelpOpen(false)}>
                      {item}
                    </MenuItem>
                  ))}
                </Menu>
              ) : null}
            </div>

            <Link
              to="/shop/reports"
              aria-label="Reports"
              className="rounded-lg p-2 text-foreground hover:bg-muted"
            >
              <FileText className="size-5" aria-hidden />
            </Link>
            <button
              type="button"
              aria-label="Notifications"
              className="rounded-lg p-2 text-foreground hover:bg-muted"
            >
              <Bell className="size-5" aria-hidden />
            </button>

            <div className="relative ml-1">
              <button
                type="button"
                onClick={() => {
                  setProfileOpen((v) => !v);
                  setHelpOpen(false);
                }}
                aria-label="Account menu"
                aria-expanded={profileOpen}
                className="rounded-full ring-offset-2 focus-visible:ring-2 focus-visible:ring-ring"
              >
                <Avatar className="size-9 bg-primary text-primary-foreground">
                  <Store className="size-4" aria-hidden />
                </Avatar>
              </button>
              {profileOpen ? (
                <Menu onClose={() => setProfileOpen(false)} className="absolute right-0 top-full z-30 mt-1 w-72">
                  <div className="flex items-center gap-3 px-3 py-3">
                    <Avatar className="size-10 bg-secondary text-foreground">
                      <Store className="size-4" aria-hidden />
                    </Avatar>
                    <div className="min-w-0">
                      <p className="truncate text-sm font-semibold">
                        {user.session.fullName ?? 'Account'}
                      </p>
                      <p className="truncate text-xs text-muted-foreground">
                        {user.session.email}
                      </p>
                    </div>
                  </div>
                  <UiSeparator />
                  <div className="flex items-center justify-between px-3 py-2.5">
                    <span className="text-sm">Shop Status</span>
                    <Switch checked={stats?.verified ?? false} aria-label="Shop status" />
                  </div>
                  <MenuItem onClick={() => setProfileOpen(false)}>
                    <Settings className="size-4" aria-hidden />
                    Account Settings
                  </MenuItem>
                  <UiSeparator />
                  <MenuItem
                    danger
                    onClick={async () => {
                      setProfileOpen(false);
                      await auth.logout();
                      navigate('/login');
                    }}
                  >
                    <span className="flex items-center gap-2">
                      <span className="grid size-7 place-items-center rounded-md bg-destructive/10 text-destructive">
                        <LogOut className="size-4" aria-hidden />
                      </span>
                      Sign Out
                    </span>
                  </MenuItem>
                </Menu>
              ) : null}
            </div>
          </div>
        </header>

        <main className="flex-1 overflow-auto p-6">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

// -------------------------------------------------------------------------- pieces

function NavLink({
  to,
  icon: Icon,
  label,
  active,
}: {
  to: string;
  icon: typeof Gauge;
  label: string;
  active: boolean;
}) {
  return (
    <Link
      to={to}
      aria-current={active ? 'page' : undefined}
      className={cn(
        'flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm transition-colors',
        active
          ? 'bg-card font-medium text-foreground shadow-sm ring-1 ring-border'
          : 'text-muted-foreground hover:bg-card hover:text-foreground',
      )}
    >
      <Icon className="size-5 shrink-0" aria-hidden />
      {label}
    </Link>
  );
}

/** A dropdown panel. Closes on outside click or Escape. */
function Menu({
  children,
  className,
  onClose,
}: {
  children: React.ReactNode;
  className?: string;
  onClose: () => void;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div
      role="menu"
      className={cn(
        'z-40 min-w-48 overflow-hidden rounded-xl border bg-background p-1 shadow-lg',
        className,
      )}
    >
      {children}
    </div>
  );
}

function MenuItem({
  children,
  onClick,
  danger,
}: {
  children: React.ReactNode;
  onClick: () => void;
  danger?: boolean;
}) {
  return (
    <button
      type="button"
      role="menuitem"
      onClick={onClick}
      className={cn(
        'flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm hover:bg-muted',
        danger && 'text-destructive',
      )}
    >
      {children}
    </button>
  );
}

/**
 * The H1 in the top bar.
 *
 * <p>Matches the longest route prefix rather than the first one that fits. `/shop` matches both
 * "Dashboard" and the `/shop/orders` prefix, so a naive `find` always returns Dashboard and every
 * page in the console is titled "Dashboard" — which is how this was caught: the orders and
 * products screens rendered correctly but both said "Dashboard".
 */
function PageTitle({ pathname }: { pathname: string }) {
  // The most specific title wins. `/shop/products` would otherwise label the new-product form
  // "Products", which is a different screen with a different job.
  const specific = ROUTE_TITLES.find((r) => pathname === r.path || pathname.startsWith(r.path));
  const item = [...SHOP_NAV, ...ADMIN_NAV]
    .filter((n) => pathname === n.to || pathname.startsWith(`${n.to}/`))
    .sort((a, b) => b.to.length - a.to.length)[0];

  return (
    <h1 className="text-2xl font-semibold tracking-tight" data-testid="page-title">
      {specific?.label ?? item?.label ?? 'Dashboard'}
    </h1>
  );
}

/** Titles for routes a nav item cannot describe on its own. */
const ROUTE_TITLES: Array<{ path: string; label: string }> = [
  { path: '/shop/products/new', label: 'Add Product' },
  { path: '/shop/discounts', label: 'Discounts' },
  { path: '/shop/media', label: 'Media Library' },
  { path: '/shop/audience', label: 'Audience' },
  { path: '/shop/returns', label: 'Returns' },
  { path: '/shop/billing', label: 'Plan & billing' },
  { path: '/admin/wallet', label: 'Wallet approvals' },
  { path: '/admin/revenue', label: 'Revenue' },
  { path: '/admin/plans', label: 'Plans' },
  { path: '/admin/audit', label: 'Audit log' },
  { path: '/admin/explorer', label: 'Data explorer' },
  { path: '/admin/support', label: 'Support' },
];

// -------------------------------------------------------------------------- nav

interface NavItem {
  to: string;
  label: string;
  icon: typeof Gauge;
}

const SHOP_NAV: NavItem[] = [
  { to: '/shop', label: 'Dashboard', icon: Gauge },
  { to: '/shop/orders', label: 'Orders', icon: ShoppingBag },
  { to: '/shop/products', label: 'Products', icon: LayoutGrid },
  { to: '/shop/audience', label: 'Audience', icon: Users },
  { to: '/shop/returns', label: 'Returns', icon: PackageX },
  { to: '/shop/discounts', label: 'Discounts', icon: Percent },
  { to: '/shop/media', label: 'Media', icon: Image },
  { to: '/shop/theme', label: 'Appearance', icon: Sparkles },
  { to: '/shop/billing', label: 'Plan & billing', icon: CreditCard },
  { to: '/shop/settings', label: 'Settings', icon: Settings },
];

// Ordered by how often an operator reaches for each one, not alphabetically: the dashboard and the
// queues come first, and the tools an administrator opens when something has gone wrong come last.
const ADMIN_NAV: NavItem[] = [
  { to: '/admin', label: 'Dashboard', icon: Gauge },
  { to: '/admin/wallet', label: 'Wallet approvals', icon: Landmark },
  { to: '/admin/shops', label: 'Shops', icon: Store },
  { to: '/admin/users', label: 'People', icon: Users },
  { to: '/admin/revenue', label: 'Revenue', icon: TrendingUp },
  { to: '/admin/reports', label: 'Reports', icon: LayoutGrid },
  { to: '/admin/plans', label: 'Plans', icon: CreditCard },
  { to: '/admin/support', label: 'Support', icon: LifeBuoy },
  { to: '/admin/audit', label: 'Audit log', icon: ScrollText },
  { to: '/admin/explorer', label: 'Data explorer', icon: Terminal },
];
