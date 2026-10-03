import { useEffect, useState } from 'react';

import { useQuery } from '@tanstack/react-query';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import {
  Bell,
  ChevronRight,
  CircleHelp,
  FileText,
  Gauge,
  LayoutGrid,
  LogOut,
  Settings,
  ShoppingBag,
  Sparkles,
  Store,
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
    enabled: user.session.authenticated,
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

  const isAdmin = user.session.roles.includes(APP_ROLE.ADMIN);
  const nav = isAdmin ? ADMIN_NAV : SHOP_NAV;
  const path = location.pathname;

  return (
    <div className="flex min-h-dvh bg-muted/40">
      {/* ------------------------------------------------------------ sidebar */}
      <aside className="flex w-[336px] shrink-0 flex-col bg-[#0d0d0d] text-neutral-300">
        <div className="p-4">
          <div className="flex items-center gap-3 rounded-xl bg-[#1c1c1c] p-3 ring-1 ring-white/10">
            <div className="relative">
              <Avatar className="size-11 bg-white text-neutral-900">
                <Store className="size-5" aria-hidden />
              </Avatar>
              {/* Verified marker, mirroring the reference's green status dot. */}
              <span
                className={cn(
                  'absolute -bottom-0.5 -right-0.5 size-3.5 rounded-full ring-2 ring-[#1c1c1c]',
                  stats?.verified ? 'bg-emerald-500' : 'bg-amber-500',
                )}
                title={stats?.verified ? 'Verified shop' : 'Not verified yet'}
              />
            </div>
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-semibold text-white">
                {stats?.shopName ?? user.session.fullName ?? 'Your shop'}
              </p>
              <Link
                to={`/preview/${stats?.shopSlug ?? ''}`}
                className="mt-0.5 inline-flex items-center gap-1 text-xs text-neutral-400 hover:text-neutral-200"
              >
                Visit your shop
                <ChevronRight className="size-3" aria-hidden />
              </Link>
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
          <div className="flex items-center gap-3 rounded-lg px-3 py-3 hover:bg-white/5">
            <Sparkles className="size-5 shrink-0 text-neutral-400" aria-hidden />
            <div className="min-w-0">
              <p className="text-sm font-medium text-white">Upgrade plan</p>
              <p className="text-xs text-neutral-500">Get extra benefits</p>
            </div>
          </div>
        </div>
      </aside>

      {/* ------------------------------------------------------------ main column */}
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-[72px] shrink-0 items-center justify-between gap-4 border-b bg-background px-6">
          <PageTitle pathname={path} />
          <div className="flex items-center gap-2">
            <Link
              to="/shop/audience"
              className="mr-2 inline-flex items-center gap-2 rounded-lg bg-neutral-900 px-3 py-2 text-sm text-white hover:bg-neutral-800"
            >
              <Users className="size-4" aria-hidden />
              Followers: {stats?.followers ?? 0}
            </Link>

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
                <Avatar className="size-9 bg-neutral-900 text-white">
                  <Store className="size-4" aria-hidden />
                </Avatar>
              </button>
              {profileOpen ? (
                <Menu onClose={() => setProfileOpen(false)} className="absolute right-0 top-full z-30 mt-1 w-72">
                  <div className="flex items-center gap-3 px-3 py-3">
                    <Avatar className="size-10 bg-blue-100 text-blue-900">
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
                      <span className="grid size-7 place-items-center rounded-md bg-red-50 text-red-600">
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
          ? 'bg-[#2a2a2a] font-medium text-white'
          : 'text-neutral-400 hover:bg-white/5 hover:text-neutral-200',
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
        danger && 'text-red-600',
      )}
    >
      {children}
    </button>
  );
}

/** The H1 in the top bar, which follows the section rather than the route. */
function PageTitle({ pathname }: { pathname: string }) {
  const item = [...SHOP_NAV, ...ADMIN_NAV].find(
    (n) => pathname === n.to || pathname.startsWith(`${n.to}/`),
  );
  return <h1 className="text-2xl font-semibold tracking-tight">{item?.label ?? 'Dashboard'}</h1>;
}

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
  { to: '/shop/theme', label: 'Appearance', icon: Sparkles },
  { to: '/shop/settings', label: 'Settings', icon: Settings },
];

const ADMIN_NAV: NavItem[] = [
  { to: '/admin', label: 'Dashboard', icon: Gauge },
  { to: '/admin/shops', label: 'Shops', icon: Store },
  { to: '/admin/reports', label: 'Reports', icon: LayoutGrid },
  { to: '/admin/settings', label: 'Settings', icon: Settings },
];
