import { shopTypeIcon } from '@store-app/icons';
import { SHOP_TYPES_STALE_TIME, type ShopTypeResponse } from '@store-app/enums';
import { API_URL } from '@store-app/api-client';

import type { Metadata } from 'next';

import { RegisterButton } from '../components/register-button';

/**
 * The landing page (Phase 8.5).
 *
 * <p><b>Server-rendered, and statically.</b> This is the first thing anybody sees and it has to work
 * with no JavaScript at all — on a slow connection, on a phone, to somebody checking a link in a
 * message. The shop types are static reference data with a long revalidate, so a slow meta endpoint
 * cannot take the page down.
 *
 * <p><b>Two doors, not one.</b> Most visitors here are customers who already know what they want and
 * a marketing page that makes them scroll to find the shop list is a page that loses them. So the
 * gallery is one click away and prominent; the pitch is for the two people who arrived to decide
 * whether this is for them at all.
 */
export const metadata: Metadata = {
  title: 'store.app — your neighbourhood, online',
  description:
    'Buy from independent shops near you, or open your own and sell to your neighbourhood. Free to start.',
};

async function getShopTypes(): Promise<ShopTypeResponse[]> {
  try {
    const res = await fetch(`${API_URL}/api/v1/meta/shop-types`, {
      headers: { Accept: 'application/json' },
      next: { revalidate: SHOP_TYPES_STALE_TIME },
    });
    if (!res.ok) return [];
    return await res.json();
  } catch {
    // Reference data, not the page. A failure here costs the category grid and nothing else.
    return [];
  }
}

async function getShopCount(): Promise<number> {
  try {
    const res = await fetch(`${API_URL}/api/v1/shops`, {
      headers: { Accept: 'application/json' },
      next: { revalidate: 300, tags: ['shops'] },
    });
    if (!res.ok) return 0;

    const body = (await res.json()) as
      | unknown[]
      | { shops?: unknown[]; totalElements?: number };

    if (Array.isArray(body)) return body.length;
    if ('totalElements' in body && typeof body.totalElements === 'number') {
      return body.totalElements;
    }
    return body.shops?.length ?? 0;
  } catch {
    return 0;
  }
}

export default async function LandingPage() {
  const [shopTypes, shopCount] = await Promise.all([getShopTypes(), getShopCount()]);

  return (
    <main>
      {/* ---------------------------------------------------------------- hero */}
      <section className="relative overflow-hidden border-b bg-gradient-to-b from-muted/60 to-background">
        <div className="mx-auto max-w-6xl px-4 py-20 sm:py-28">
          <div className="max-w-3xl">
            <p className="mb-4 inline-flex items-center gap-2 rounded-full border bg-background px-3 py-1 text-xs font-medium text-muted-foreground">
              <span className="size-1.5 rounded-full bg-emerald-500" aria-hidden />
              {shopCount > 0
                ? `${shopCount} ${shopCount === 1 ? 'shop' : 'shops'} and counting`
                : 'Opening soon'}
            </p>

            <h1 className="text-4xl font-semibold tracking-tight sm:text-6xl">
              Your neighbourhood,
              <br />
              <span className="text-muted-foreground">online.</span>
            </h1>

            <p className="mt-6 max-w-xl text-lg text-muted-foreground">
              The pharmacy two streets away, the bookshop your kid likes, the grocer who knows your
              name. Buy directly from independent shops — no marketplace middleman deciding what you
              see.
            </p>

            <div className="mt-9 flex flex-wrap gap-3">
              {/*
                The primary action uses the theme's own foreground/background pair rather than a
                hard-coded colour. `bg-neutral` looked right and rendered as nothing at all: this
                project defines no `neutral` token, so the primary call to action on the front page
                was white on white.
              */}
              <a
                href="/shops"
                className="inline-flex h-11 items-center rounded-lg bg-foreground px-6 text-sm font-medium text-background transition-colors hover:bg-foreground/90"
              >
                Browse shops
              </a>
              <RegisterButton />
            </div>
          </div>
        </div>
      </section>

      {/* ---------------------------------------------------------------- the two paths */}
      <section className="mx-auto max-w-6xl px-4 py-16">
        <div className="grid gap-6 md:grid-cols-2">
          <article className="rounded-2xl border p-8 transition-shadow hover:shadow-sm">
            <h2 className="text-xl font-semibold">Buying something?</h2>
            <p className="mt-2 text-sm text-muted-foreground">
              Browse shops by what they sell. No account needed to order — guest checkout works
              everywhere. An account just gives you order history and a wishlist.
            </p>
            <a
              href="/shops"
              className="mt-6 inline-flex h-11 items-center rounded-lg border bg-background px-5 text-sm font-medium transition-colors hover:bg-muted"
            >
              See what&apos;s open
            </a>
          </article>

          <article className="rounded-2xl border p-8 transition-shadow hover:shadow-sm">
            <h2 className="text-xl font-semibold">Running a shop?</h2>
            <p className="mt-2 text-sm text-muted-foreground">
              Open a shop in a couple of minutes. List products, take orders, and get paid into your
              wallet. We take 5% when an order is accepted and nothing at all to set up.
            </p>
            <a
              href="/login"
              className="mt-6 inline-flex h-11 items-center rounded-lg bg-foreground px-5 text-sm font-medium text-background transition-colors hover:bg-foreground/90"
            >
              Open your shop
            </a>
          </article>
        </div>
      </section>

      {/* ---------------------------------------------------------------- how it works */}
      <section className="border-y bg-muted/30">
        <div className="mx-auto max-w-6xl px-4 py-16">
          <h2 className="text-2xl font-semibold tracking-tight">How it works</h2>
          <p className="mt-2 max-w-xl text-sm text-muted-foreground">
            The same three steps whether you are buying or selling.
          </p>

          <ol className="mt-10 grid gap-8 md:grid-cols-3">
            {[
              {
                title: 'Find your people',
                body: 'Nineteen kinds of shop, from pharmacies to florists. Customers search by what they need, not by brand.',
              },
              {
                title: 'Trade directly',
                body: 'No reseller in the middle. The shop sets its own prices, and the customer talks to the shop.',
              },
              {
                title: 'Get paid',
                body: 'Money goes into the shop’s wallet when an order is accepted. Withdraw when you want to.',
              },
            ].map((step, index) => (
              <li key={step.title}>
                <span className="flex size-8 items-center justify-center rounded-full border bg-background text-sm font-semibold">
                  {index + 1}
                </span>
                <h3 className="mt-4 font-semibold">{step.title}</h3>
                <p className="mt-1 text-sm text-muted-foreground">{step.body}</p>
              </li>
            ))}
          </ol>
        </div>
      </section>

      {/* ---------------------------------------------------------------- categories */}
      {shopTypes.length > 0 ? (
        <section className="mx-auto max-w-6xl px-4 py-16">
          <div className="flex items-baseline justify-between gap-4">
            <h2 className="text-2xl font-semibold tracking-tight">What people sell</h2>
            <a href="/shops" className="text-sm text-muted-foreground hover:text-foreground">
              See all shops →
            </a>
          </div>

          <ul className="mt-8 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
            {shopTypes.slice(0, 12).map((type) => {
              const Icon = shopTypeIcon(type.icon);
              return (
                <li key={type.code}>
                  <a
                    href={`/shops?type=${type.code}`}
                    className="flex items-center gap-3 rounded-xl border p-4 transition-colors hover:bg-muted/50"
                  >
                    <Icon className="size-5 shrink-0 text-muted-foreground" aria-hidden />
                    <span className="min-w-0 truncate text-sm font-medium">{type.label}</span>
                  </a>
                </li>
              );
            })}
          </ul>
        </section>
      ) : null}

      {/* ---------------------------------------------------------------- closing call */}
      <section className="border-t">
        <div className="mx-auto max-w-6xl px-4 py-20 text-center">
          <h2 className="text-3xl font-semibold tracking-tight">
            Your corner shop, online
          </h2>
          <p className="mx-auto mt-3 max-w-lg text-muted-foreground">
            Setting one up takes a couple of minutes and costs nothing to start.
          </p>
          <a
            href="/login"
            className="mt-8 inline-flex h-11 items-center rounded-lg bg-foreground px-6 text-sm font-medium text-background transition-colors hover:bg-foreground/90"
          >
            Open your shop
          </a>
        </div>
      </section>
    </main>
  );
}