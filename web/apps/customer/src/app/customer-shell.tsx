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
    <div className="flex min-h-dvh flex-col">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded-md focus:bg-background focus:px-4 focus:py-2 focus:ring-1"
      >
        Skip to content
      </a>

      <header className="sticky top-0 z-40 border-b bg-background/95 backdrop-blur">
        <div className="mx-auto flex h-14 max-w-6xl items-center gap-3 px-4">
          <Link href="/" className="flex shrink-0 items-center gap-2 font-semibold">
            <Store className="size-5" aria-hidden />
            store.app
          </Link>

          <nav className="hidden items-center gap-1 sm:flex" aria-label="Main">
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
                <ShellLink href="/account/addresses" pathname={pathname}>
                  <MapPin className="size-4" aria-hidden />
                  Addresses
                </ShellLink>
              </>
            ) : null}
          </nav>

          <div className="ml-auto flex items-center gap-2">
            {ready && signedIn ? (
              <>
                <Button asChild variant="outline" size="sm">
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
            ) : ready ? (
              <Button asChild size="sm">
                <Link href="/login">Sign in</Link>
              </Button>
            ) : (
              // A placeholder the same width as the button it replaces, so the header does not
              // reflow when the session resolves.
              <span className="h-9 w-20 animate-pulse rounded-md bg-muted" aria-hidden />
            )}
          </div>
        </div>

        {/*
          Account links again on a narrow screen. Hidden rather than absent above sm, because a
          customer on a phone cannot reach their orders otherwise.
        */}
        {ready && signedIn ? (
          <nav
            className="flex items-center gap-1 overflow-x-auto border-t px-4 py-1.5 sm:hidden"
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

      <main id="main" className="mx-auto w-full max-w-6xl flex-1 px-4 py-6">
        {children}
      </main>

      <footer className="border-t py-6 text-center text-xs text-muted-foreground">
        store.app — a marketplace for Nepali shops
      </footer>
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
        'inline-flex shrink-0 items-center gap-1.5 rounded-md px-3 py-1.5 text-sm whitespace-nowrap transition-colors',
        active ? 'bg-muted font-medium' : 'text-muted-foreground hover:text-foreground',
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