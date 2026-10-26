'use client';

import type { ReactNode } from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { Heart, LogOut, MapPin, Package, Search, Store, User } from 'lucide-react';

import { takeReturnTo } from '@store-app/auth';
import { Button } from '@store-app/ui';

import { auth, initAuth } from '../lib/auth';
import { cn } from '@store-app/ui';

/**
 * The customer app's chrome.
 *
 * <p><b>One place decides whether somebody is signed in.</b> Five pages each checking for a token
 * is how a customer ends up with a "Sign in" link on the gallery and a "Sign out" button on their
 * orders. The session is read once here and passed down.
 *
 * <p><b>The adapter itself is shared, not per-component</b> — see `lib/auth`. This file used to
 * build its own, and the login page built a second one; the two disagreed and the sign-in button
 * simply never enabled.
 *
 * <p><b>A client component, deliberately.</b> It reads a browser token, which does not exist during
 * server rendering. Making only this a client component keeps the pages themselves server-rendered,
 * which is what makes a storefront indexable and share-previewable (D1).
 *
 * <p><b>Shape: a floating bar over warm paper.</b> The reference for this app (shop.com) puts its
 * navigation in a rounded, elevated strip rather than a full-width rule with a hairline underneath.
 * That is the part worth taking — a marketplace's chrome should recede so the shops and products
 * are what the eye lands on. The bar is translucent with a blur so content scrolls *under* it
 * rather than being abruptly cut off by an opaque band.
 */

export function CustomerShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const [signedIn, setSignedIn] = useState(false);
  const [fullName, setFullName] = useState<string | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let cancelled = false;

    void (async () => {
      // Init first, then ask. `isAuthenticated()` is `init'd && authenticated`, so asking before
      // the initialisation resolves always answers false -- which is what left the header showing
      // a placeholder to a customer who was signed in.
      //
      // <p>And the finally matters: a failed init leaves the promise rejected (deliberately, so a
      // retry cannot call `kc.init()` twice), and without this the header would render its
      // skeleton forever with no explanation and no way forward.
      try {
        await initAuth();
        if (cancelled) return;

        const authenticated = auth.isAuthenticated();
        if (authenticated) {
          const session = auth.getSession();
          setFullName(session.fullName ?? session.email ?? null);

          // Where they were heading before the Keycloak round trip. Taken (and cleared) rather
          // than read, so a later sign-in does not send somebody to a page they visited hours ago.
          const destination = takeReturnTo();
          if (destination) {
            router.replace(destination);
            return;
          }
        }
        setSignedIn(authenticated);
      } catch {
        // Sign-in is unavailable. Browsing still works -- guest checkout is the point -- so the
        // header simply offers "Sign in" and the failure surfaces when they press it.
        if (!cancelled) setSignedIn(false);
      } finally {
        if (!cancelled) setReady(true);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [pathname, router]);

  return (
    <div className="flex min-h-dvh flex-col bg-background">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded-full focus:bg-card focus:px-4 focus:py-2 focus:ring-2 focus:ring-ring"
      >
        Skip to content
      </a>

      {/* ------------------------------------------------------------- top bar */}
      <header className="sticky top-0 z-40 px-3 pt-3 sm:px-5 sm:pt-4">
        <div className="mx-auto flex h-14 max-w-7xl items-center gap-2 rounded-full border border-border/80 bg-card/85 px-2 shadow-sm backdrop-blur-xl sm:gap-3 sm:px-3">
          <Link
            href="/"
            className="flex shrink-0 items-center gap-2 rounded-full py-1 pl-2 pr-3 transition-colors hover:bg-secondary/60"
          >
            {/* The mark is the accent rather than another icon in the text colour: with one warm
                accent in the palette, this is the single place the brand is allowed to appear. */}
            <span className="grid size-8 place-items-center rounded-full bg-primary text-primary-foreground">
              <Store className="size-4" aria-hidden />
            </span>
            <span className="text-[15px] font-semibold tracking-tight">store.app</span>
          </Link>

          <nav className="hidden items-center gap-0.5 md:flex" aria-label="Main">
            <ShellLink href="/shops" pathname={pathname}>
              Browse
            </ShellLink>
            <ShellLink href="/search" pathname={pathname}>
              <Search className="size-4" aria-hidden />
              Search
            </ShellLink>
            {/*
              Rendered only once `ready`, so a visitor does not see the account links appear a
              moment after the page settles. The flicker is small but it makes a page feel unstable.
            */}
            {ready && signedIn ? (
              <>
                <ShellLink href="/account/orders" pathname={pathname}>
                  <Package className="size-4" aria-hidden />
                  Orders
                </ShellLink>
                <ShellLink href="/account/wishlist" pathname={pathname}>
                  <Heart className="size-4" aria-hidden />
                  Wishlist
                </ShellLink>
              </>
            ) : null}
          </nav>

          <div className="ml-auto flex items-center gap-1.5">
            {ready && signedIn ? (
              <>
                <Button asChild variant="ghost" size="sm" className="rounded-full">
                  <Link href="/account/addresses" aria-label="Addresses" title="Addresses">
                    <MapPin className="size-4" aria-hidden />
                    <span className="hidden lg:inline">Addresses</span>
                  </Link>
                </Button>
                <Button asChild variant="outline" size="sm" className="rounded-full">
                  <Link href="/account">
                    <User className="size-4" aria-hidden />
                    <span className="hidden max-w-32 truncate sm:inline">
                      {fullName ?? 'Account'}
                    </span>
                    <span className="sm:hidden">Account</span>
                  </Link>
                </Button>
                <SignOutButton />
              </>
            ) : (
              /*
                "Sign in" immediately, not a skeleton. Initialisation performs a silent SSO check
                against Keycloak, and on a cold load that round trip measured 3.5–6 seconds here —
                during which this header showed an empty grey pill. That is indistinguishable from a
                broken control, and it sat in the most prominent corner of the page.

                "Sign in" is the honest default: the storefront is public and most visitors are
                signed out. A customer who *is* signed in sees it swap to their account a moment
                later, which is a smaller lie than an empty button. Both states are the same height,
                so the bar does not reflow either way.
              */
              <Button asChild size="sm" className="rounded-full px-4">
                <Link href="/login">Sign in</Link>
              </Button>
            )}
          </div>
        </div>

        {/*
          Account links again on a narrow screen. Hidden rather than absent above md, because a
          customer on a phone cannot reach their orders otherwise.
        */}
        {ready && signedIn ? (
          <nav
            className="mx-auto flex max-w-7xl items-center gap-1 overflow-x-auto px-2 pt-1.5 md:hidden"
            aria-label="Account"
          >
            <ShellLink href="/account/orders" pathname={pathname}>
              Orders
            </ShellLink>
            <ShellLink href="/account/wishlist" pathname={pathname}>
              Wishlist
            </ShellLink>
            <ShellLink href="/account/addresses" pathname={pathname}>
              Addresses
            </ShellLink>
          </nav>
        ) : null}
      </header>

      <main id="main" className="mx-auto w-full max-w-7xl flex-1 px-4 py-8 sm:px-6">
        {children}
      </main>

      {/* ------------------------------------------------------------- footer */}
      <footer className="mt-16 border-t border-border/70 bg-secondary/40">
        <div className="mx-auto max-w-7xl px-4 py-12 sm:px-6">
          <div className="grid gap-10 sm:grid-cols-2 lg:grid-cols-4">
            <div className="lg:col-span-2">
              <div className="flex items-center gap-2">
                <span className="grid size-8 place-items-center rounded-full bg-primary text-primary-foreground">
                  <Store className="size-4" aria-hidden />
                </span>
                <span className="text-[15px] font-semibold tracking-tight">store.app</span>
              </div>
              <p className="mt-4 max-w-sm text-sm text-muted-foreground">
                A marketplace for Nepali shops. Buy directly from the shop that sells it — no
                middleman deciding what you see.
              </p>
            </div>

            <FooterColumn
              title="Shopping"
              links={[
                { href: '/shops', label: 'Browse shops' },
                { href: '/search', label: 'Search products' },
              ]}
            />
            <FooterColumn
              title="Your account"
              links={[
                { href: '/login', label: 'Sign in' },
                { href: '/account/orders', label: 'Orders' },
                { href: '/account/wishlist', label: 'Wishlist' },
              ]}
            />
          </div>

          <div className="mt-10 flex flex-col gap-2 border-t border-border/70 pt-6 text-xs text-muted-foreground sm:flex-row sm:items-center sm:justify-between">
            <p>© {new Date().getFullYear()} store.app</p>
            <p>Made for the shops on your street.</p>
          </div>
        </div>
      </footer>
    </div>
  );
}

function FooterColumn({
  title,
  links,
}: {
  title: string;
  links: Array<{ href: string; label: string }>;
}) {
  return (
    <div>
      <p className="text-xs font-medium uppercase tracking-[0.08em] text-muted-foreground">
        {title}
      </p>
      <ul className="mt-4 space-y-2.5 text-sm">
        {links.map((link) => (
          <li key={link.href}>
            <Link href={link.href} className="transition-colors hover:text-primary">
              {link.label}
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}

function ShellLink({
  href,
  pathname,
  children,
}: {
  href: string;
  pathname: string | null;
  children: ReactNode;
}) {
  const active = href === '/' ? pathname === '/' : pathname?.startsWith(href);
  return (
    <Link
      href={href}
      aria-current={active ? 'page' : undefined}
      className={cn(
        // Pill, because the bar itself is a pill: a square item inside a fully rounded container
        // reads as a mistake rather than a style.
        'inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full px-3.5 py-1.5 text-sm transition-colors',
        active
          ? 'bg-foreground font-medium text-background'
          : 'text-muted-foreground hover:bg-secondary/60 hover:text-foreground',
      )}
    >
      {children}
    </Link>
  );
}

function SignOutButton() {
  const [busy, setBusy] = useState(false);
  return (
    <Button
      size="sm"
      variant="ghost"
      className="rounded-full"
      disabled={busy}
      aria-label="Sign out"
      title="Sign out"
      onClick={() => {
        setBusy(true);
        // A full reload rather than a router refresh: the shell and the pages both read the
        // session on mount, and navigating would leave the previous page's data on screen.
        void auth.logout().finally(() => {
          window.location.href = '/';
        });
      }}
    >
      <LogOut className="size-4" aria-hidden />
    </Button>
  );
}
