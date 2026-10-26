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
 *
 * <p><b>Shape: the reference's airy display page.</b> The structural idea taken from shop.com is a
 * large, tight, high-contrast headline with a rounded panel beside it, generous vertical rhythm, and
 * pill controls throughout. The right-hand panel is the part that had to be earned: the previous
 * hero reserved half its width and left it empty, which reads as a failed image rather than as
 * whitespace. It now shows real shops, or an honest statement that there are none yet.
 */

export const metadata: Metadata = {
  title: 'store.app — your neighbourhood, online',
  description:
    'Buy from independent shops near you, or open your own and sell to your neighbourhood. Free to start.',
};

interface ShopPreview {
  slug: string;
  name: string;
  shopType: string;
  logoUrl?: string;
}

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

async function getShops(): Promise<{ shops: ShopPreview[]; total: number }> {
  try {
    const res = await fetch(`${API_URL}/api/v1/shops`, {
      headers: { Accept: 'application/json' },
      next: { revalidate: 300, tags: ['shops'] },
    });
    if (!res.ok) return { shops: [], total: 0 };

    const body = (await res.json()) as
      | ShopPreview[]
      | { shops?: ShopPreview[]; totalElements?: number };

    if (Array.isArray(body)) return { shops: body, total: body.length };
    return {
      shops: body.shops ?? [],
      total:
        typeof body.totalElements === 'number' ? body.totalElements : (body.shops ?? []).length,
    };
  } catch {
    return { shops: [], total: 0 };
  }
}

export default async function LandingPage() {
  const [shopTypes, shopData] = await Promise.all([getShopTypes(), getShops()]);
  const { shops, total: shopCount } = shopData;
  const typeLabel = new Map<string, string>(shopTypes.map((t) => [t.code as string, t.label]));

  return (
    <main className="space-y-20 pb-4 sm:space-y-28">
      {/* ---------------------------------------------------------------- hero */}
      <section className="relative overflow-hidden rounded-[2rem] border border-border bg-card px-6 py-12 sm:px-10 sm:py-16 lg:px-16 lg:py-20">
        {/* A second wash of the palette behind everything, so the panel is warm rather than flat
            white without turning into a gradient gimmick. */}
        <div
          aria-hidden
          className="pointer-events-none absolute -right-24 -top-32 size-[28rem] rounded-full bg-secondary/70 blur-3xl"
        />

        <div className="relative grid items-center gap-12 lg:grid-cols-[1.12fr_0.88fr] lg:gap-16">
          <div>
            <p className="inline-flex items-center gap-2 rounded-full border border-border bg-background/70 px-3 py-1 text-xs font-medium text-muted-foreground">
              <span className="size-1.5 rounded-full bg-success" aria-hidden />
              {shopCount > 0
                ? `${shopCount} ${shopCount === 1 ? 'shop' : 'shops'} and counting`
                : 'Opening soon'}
            </p>

            {/*
              The display line. Tight leading and negative tracking are what make this read as a
              statement rather than as a paragraph in a larger font — the reference's headline sits
              at roughly 0.95 line height, and at that size anything looser looks accidental.
            */}
            <h1 className="mt-6 text-balance text-4xl font-semibold leading-[0.98] tracking-[-0.04em] sm:text-5xl lg:text-6xl">
              Your neighbourhood,
              <br />
              <span className="text-primary">online.</span>
            </h1>

            <p className="mt-6 max-w-xl text-lg leading-relaxed text-muted-foreground">
              The pharmacy two streets away, the bookshop your kid likes, the grocer who knows your
              name. Buy directly from independent shops — no marketplace middleman deciding what you
              see.
            </p>

            <div className="mt-9 flex flex-wrap items-center gap-3">
              {/*
                The primary action uses the palette's own pair rather than a hard-coded colour.
                `bg-neutral` looked right and rendered as nothing at all: this project defines no
                `neutral` token, so the primary call to action on the front page was white on white.
              */}
              <a
                href="/shops"
                className="inline-flex h-12 items-center rounded-full bg-primary px-7 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
              >
                Browse shops
              </a>
              <RegisterButton />
            </div>

            <p className="mt-5 text-xs text-muted-foreground">
              No account needed to order. Guest checkout works everywhere.
            </p>
          </div>

          {/* ------------------------------------------------------ live shops */}
          <div className="relative">
            <div className="absolute -inset-3 -z-10 rounded-[2rem] bg-secondary/60" aria-hidden />

            {shops.length > 0 ? (
              <div className="space-y-3">
                {shops.slice(0, 3).map((shop, index) => (
                  <a
                    key={shop.slug}
                    href={`/s/${shop.slug}`}
                    className="flex items-center gap-4 rounded-2xl border border-border bg-background p-4 transition-shadow hover:shadow-md"
                    // A slight staircase: three identical cards in a column read as a list, while
                    // an offset stack reads as a preview. Only on the middle card, so the first and
                    // last stay flush with the panel.
                    style={index === 1 ? { marginLeft: '1.25rem', marginRight: '-1.25rem' } : undefined}
                  >
                    <span className="grid size-11 shrink-0 place-items-center overflow-hidden rounded-xl bg-secondary text-sm font-semibold text-secondary-foreground">
                      {shop.logoUrl ? (
                        // eslint-disable-next-line @next/next/no-img-element
                        <img src={shop.logoUrl} alt="" className="size-full object-cover" />
                      ) : (
                        shop.name.slice(0, 2).toUpperCase()
                      )}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate font-medium">{shop.name}</span>
                      <span className="block truncate text-sm text-muted-foreground">
                        {typeLabel.get(shop.shopType) ?? shop.shopType}
                      </span>
                    </span>
                    <span className="shrink-0 text-muted-foreground" aria-hidden>
                      →
                    </span>
                  </a>
                ))}

                <div className="flex items-center justify-between gap-4 rounded-2xl border border-border bg-background p-4">
                  <div>
                    <p className="text-xs uppercase tracking-[0.08em] text-muted-foreground">
                      Kinds of shop
                    </p>
                    <p className="mt-1 text-2xl font-semibold tracking-tight">
                      {shopTypes.length > 0 ? shopTypes.length : '—'}
                    </p>
                  </div>
                  <a
                    href="/shops"
                    className="rounded-full border border-border px-3.5 py-1.5 text-xs font-medium transition-colors hover:bg-secondary"
                  >
                    See all
                  </a>
                </div>
              </div>
            ) : (
              <div className="rounded-2xl border border-dashed border-border bg-background/80 p-8 text-center">
                <p className="font-medium">No shops are published yet.</p>
                <p className="mx-auto mt-2 max-w-xs text-sm text-muted-foreground">
                  Shops appear here once an admin verifies them. Yours could be the first.
                </p>
                <a
                  href="/login"
                  className="mt-5 inline-flex h-10 items-center rounded-full bg-primary px-5 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
                >
                  Open your shop
                </a>
              </div>
            )}
          </div>
        </div>
      </section>

      {/* ---------------------------------------------------------------- categories */}
      {shopTypes.length > 0 ? (
        <section>
          <div className="flex items-baseline justify-between gap-4">
            <h2 className="text-2xl font-semibold tracking-tight sm:text-3xl">What people sell</h2>
            <a
              href="/shops"
              className="shrink-0 text-sm text-muted-foreground transition-colors hover:text-primary"
            >
              See all shops →
            </a>
          </div>

          <ul className="mt-6 flex flex-wrap gap-2.5">
            {shopTypes.slice(0, 12).map((type) => {
              const Icon = shopTypeIcon(type.icon);
              return (
                <li key={type.code}>
                  <a
                    href={`/shops?type=${type.code}`}
                    className="inline-flex items-center gap-2 rounded-full border border-border bg-card px-4 py-2.5 text-sm transition-colors hover:border-primary/40 hover:text-primary"
                  >
                    <Icon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                    {type.label}
                  </a>
                </li>
              );
            })}
          </ul>
        </section>
      ) : null}

      {/* ---------------------------------------------------------------- the two paths */}
      <section className="grid gap-5 md:grid-cols-2">
        <article className="flex flex-col rounded-[1.75rem] border border-border bg-card p-8 transition-shadow hover:shadow-md sm:p-10">
          <h2 className="text-2xl font-semibold tracking-tight">Buying something?</h2>
          <p className="mt-3 text-muted-foreground">
            Browse shops by what they sell. No account needed to order — guest checkout works
            everywhere. An account just gives you order history and a wishlist.
          </p>
          <div className="mt-auto pt-8">
            <a
              href="/shops"
              className="inline-flex h-11 items-center rounded-full border border-border px-6 text-sm font-medium transition-colors hover:bg-secondary"
            >
              See what&apos;s open
            </a>
          </div>
        </article>

        <article className="flex flex-col rounded-[1.75rem] bg-primary p-8 text-primary-foreground sm:p-10">
          <h2 className="text-2xl font-semibold tracking-tight">Running a shop?</h2>
          <p className="mt-3 text-primary-foreground/80">
            Open a shop in a couple of minutes. List products, take orders, and get paid into your
            wallet. We take 5% when an order is accepted and nothing at all to set up.
          </p>
          <div className="mt-auto pt-8">
            <a
              href="/login"
              className="inline-flex h-11 items-center rounded-full bg-primary-foreground px-6 text-sm font-medium text-primary transition-colors hover:bg-primary-foreground/90"
            >
              Open your shop
            </a>
          </div>
        </article>
      </section>

      {/* ---------------------------------------------------------------- how it works */}
      <section>
        <h2 className="text-2xl font-semibold tracking-tight sm:text-3xl">How it works</h2>
        <p className="mt-2 max-w-xl text-muted-foreground">
          The same three steps whether you are buying or selling.
        </p>

        <ol className="mt-8 grid gap-4 md:grid-cols-3">
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
            <li key={step.title} className="rounded-[1.5rem] border border-border bg-card p-7">
              <span className="grid size-9 place-items-center rounded-full bg-secondary text-sm font-semibold text-secondary-foreground">
                {index + 1}
              </span>
              <h3 className="mt-5 font-semibold">{step.title}</h3>
              <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{step.body}</p>
            </li>
          ))}
        </ol>
      </section>

      {/* ---------------------------------------------------------------- closing call */}
      <section className="overflow-hidden rounded-[2rem] bg-foreground px-6 py-16 text-center text-background sm:px-10 sm:py-20">
        <h2 className="text-3xl font-semibold tracking-tight sm:text-5xl sm:leading-[1.02]">
          Your corner shop, online
        </h2>
        <p className="mx-auto mt-4 max-w-lg text-background/70">
          Setting one up takes a couple of minutes and costs nothing to start.
        </p>
        <a
          href="/login"
          className="mt-8 inline-flex h-12 items-center rounded-full bg-background px-7 text-sm font-medium text-foreground transition-colors hover:bg-background/90"
        >
          Open your shop
        </a>
      </section>
    </main>
  );
}
