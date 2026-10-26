import type { Metadata } from 'next';

import { API_URL } from '@store-app/api-client';
import { SHOP_TYPES_STALE_TIME, type ShopTypeResponse } from '@store-app/enums';

export const metadata: Metadata = {
  title: 'store.app — search every shop',
  description: 'Find a product and the shops near you that sell it.',
};

/**
 * Public search.
 *
 * <p><b>This page did not exist.</b> The header has linked to `/search` since the shell was
 * written, and the route returned a 404 — while `PublicSearchController` had been serving
 * `/api/v1/p/search` since Phase 5. A nav item that goes nowhere is worse than no nav item: it
 * teaches people the site is unfinished. The endpoint is good, so the page is the missing half.
 *
 * <p><b>Server-rendered, and legible with no JavaScript.</b> The search box is a plain
 * `<form method="get">`. That is not laziness — it is the difference between a search that works on
 * a mid-range phone over a slow connection and one that waits for a hydration bundle. The URL is
 * the state, so a result set can be pasted into a message.
 *
 * <p><b>Shops first, then products.</b> The API answers both in one round trip because a customer
 * searching "paracetamol" wants to know which pharmacies have it, not to scroll forty listings from
 * one. The page follows that order rather than rearranging it.
 *
 * <p><b>An empty result always says why.</b> The API returns a `hint` for queries too short to be
 * useful — one character cannot use the trigram index — and this page shows it instead of a bare
 * "no results".
 */

interface ShopHit {
  id: string;
  slug: string;
  name: string;
  shopType: string;
  matches: number;
}

interface ProductHit {
  id: string;
  name: string;
  slug: string;
  category: string;
  categoryLabel: string;
  priceMinor: number;
  priceDisplay: string;
  shortDescription: string | null;
  imageUrl: string | null;
  shopSlug: string | null;
  shopName: string | null;
}

interface SearchResponse {
  query: string;
  shopType: string | null;
  products: ProductHit[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  shops: ShopHit[];
  hint: string | null;
}

const EMPTY: SearchResponse = {
  query: '',
  shopType: null,
  products: [],
  page: 0,
  size: 24,
  totalElements: 0,
  totalPages: 0,
  shops: [],
  hint: null,
};

async function runSearch(q: string, type: string | null, page: number): Promise<SearchResponse> {
  const params = new URLSearchParams({ q, page: String(page), size: '24' });
  if (type) params.set('type', type);

  try {
    const res = await fetch(`${API_URL}/api/v1/p/search?${params}`, {
      headers: { Accept: 'application/json' },
      // Results are per-query and change as shops edit stock, so there is nothing useful to cache
      // across requests. `no-store` also keeps a signed-out search from being served to someone
      // else later with a different type filter.
      cache: 'no-store',
    });
    if (!res.ok) return { ...EMPTY, query: q, hint: 'Search is unavailable right now.' };
    return (await res.json()) as SearchResponse;
  } catch {
    // A search page must not 500 because the API blinked. The box stays usable and says so.
    return { ...EMPTY, query: q, hint: 'Search is unavailable right now. Try again in a moment.' };
  }
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
    return [];
  }
}

export default async function SearchPage({
  searchParams,
}: {
  searchParams: Promise<{ q?: string; type?: string; page?: string }>;
}) {
  const params = await searchParams;
  const q = (params.q ?? '').trim();
  const type = params.type?.trim() || null;
  const page = Math.max(0, Number(params.page ?? 0) || 0);

  const [results, shopTypes] = await Promise.all([runSearch(q, type, page), getShopTypes()]);

  const hasQuery = q.length >= 2;
  const typeLabel = new Map<string, string>(shopTypes.map((t) => [t.code as string, t.label]));

  /** Keep the current query when changing one parameter. */
  const withParams = (next: { q?: string; type?: string | null; page?: number }) => {
    const p = new URLSearchParams();
    const nextQuery = next.q ?? q;
    const nextType = next.type === undefined ? type : next.type;
    const nextPage = next.page ?? 0;
    if (nextQuery) p.set('q', nextQuery);
    if (nextType) p.set('type', nextType);
    if (nextPage > 0) p.set('page', String(nextPage));
    const qs = p.toString();
    return qs ? `/search?${qs}` : '/search';
  };

  return (
    <main>
      {/* ------------------------------------------------------------- heading */}
      <section className="pb-8 pt-2">
        <h1 className="text-4xl font-semibold tracking-[-0.03em] sm:text-5xl">Search</h1>
        <p className="mt-3 max-w-xl text-muted-foreground">
          Type what you need. You will see the shops that stock it, then the products themselves.
        </p>

        <form action="/search" method="get" className="mt-7 flex max-w-2xl gap-2" role="search">
          <label htmlFor="q" className="sr-only">
            Search products and shops
          </label>
          <div className="relative flex-1">
            <svg
              viewBox="0 0 24 24"
              className="pointer-events-none absolute left-4 top-1/2 size-5 -translate-y-1/2 text-muted-foreground"
              fill="none"
              stroke="currentColor"
              strokeWidth={2}
              aria-hidden
            >
              <circle cx="11" cy="11" r="7" />
              <path d="m20 20-3.5-3.5" strokeLinecap="round" />
            </svg>
            <input
              id="q"
              name="q"
              type="search"
              defaultValue={q}
              autoComplete="off"
              placeholder="Coffee, paracetamol, a book…"
              className="h-14 w-full rounded-full border border-border bg-card pl-12 pr-4 text-base shadow-sm outline-none transition-shadow placeholder:text-muted-foreground focus-visible:ring-2 focus-visible:ring-ring"
            />
          </div>
          {type ? <input type="hidden" name="type" value={type} /> : null}
          <button
            type="submit"
            className="h-14 shrink-0 rounded-full bg-primary px-7 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
          >
            Search
          </button>
        </form>

        {shopTypes.length > 0 ? (
          <nav aria-label="Filter by shop type" className="mt-5 flex flex-wrap gap-2">
            <FilterPill href={withParams({ type: null })} active={type === null}>
              All shops
            </FilterPill>
            {shopTypes.map((t) => (
              <FilterPill
                key={t.code}
                href={withParams({ type: t.code as string })}
                active={type === t.code}
              >
                {t.label}
              </FilterPill>
            ))}
          </nav>
        ) : null}
      </section>

      {/* ------------------------------------------------------------- results */}
      {!hasQuery ? (
        <EmptyState
          title="Start with a word"
          body="Search needs at least two characters. Try a product, a category, or a shop's speciality."
        />
      ) : results.hint && results.products.length === 0 && results.shops.length === 0 ? (
        <EmptyState
          title={q ? `Nothing matched “${q}”` : 'Nothing here yet'}
          body={results.hint}
        />
      ) : (
        <div className="space-y-10">
          {results.shops.length > 0 ? (
            <section aria-labelledby="shops-heading">
              <div className="flex items-baseline justify-between gap-4">
                <h2 id="shops-heading" className="text-xl font-semibold tracking-tight">
                  Shops that have it
                </h2>
                <p className="text-sm text-muted-foreground">
                  {type ? (typeLabel.get(type) ?? type) : 'All kinds'}
                </p>
              </div>

              <ul className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                {results.shops.map((shop) => (
                  <li key={shop.id}>
                    <a
                      href={`/s/${shop.slug}`}
                      className="flex items-center justify-between gap-4 rounded-2xl border border-border bg-card p-4 transition-shadow hover:shadow-md"
                    >
                      <div className="min-w-0">
                        <p className="truncate font-medium">{shop.name}</p>
                        <p className="truncate text-sm text-muted-foreground">
                          {typeLabel.get(shop.shopType) ?? shop.shopType}
                        </p>
                      </div>
                      <span className="shrink-0 rounded-full bg-secondary px-2.5 py-1 text-xs font-medium text-secondary-foreground">
                        {shop.matches} {shop.matches === 1 ? 'match' : 'matches'}
                      </span>
                    </a>
                  </li>
                ))}
              </ul>
            </section>
          ) : null}

          <section aria-labelledby="products-heading">
            <div className="flex items-baseline justify-between gap-4">
              <h2 id="products-heading" className="text-xl font-semibold tracking-tight">
                Products
              </h2>
              <p className="text-sm text-muted-foreground">
                {results.totalElements === 0
                  ? 'Nothing to show'
                  : `${results.totalElements.toLocaleString()} ${
                      results.totalElements === 1 ? 'result' : 'results'
                    }`}
              </p>
            </div>

            {results.products.length === 0 ? (
              <EmptyState
                title={`No products matched “${q}”`}
                body="Try a shorter word, or clear the shop-type filter. Only verified shops with active listings appear here."
              />
            ) : (
              <>
                <ul className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
                  {results.products.map((product) => (
                    <li key={product.id}>
                      <a
                        href={
                          product.shopSlug
                            ? `/s/${product.shopSlug}/p/${product.slug}`
                            : '/shops'
                        }
                        className="group flex h-full flex-col overflow-hidden rounded-2xl border border-border bg-card transition-shadow hover:shadow-md"
                      >
                        <div className="aspect-[4/3] w-full overflow-hidden bg-secondary">
                          {product.imageUrl ? (
                            // eslint-disable-next-line @next/next/no-img-element
                            <img
                              src={product.imageUrl}
                              alt=""
                              className="size-full object-cover transition-transform duration-300 group-hover:scale-[1.03]"
                              loading="lazy"
                            />
                          ) : (
                            <div className="grid size-full place-items-center text-xs text-muted-foreground">
                              No image
                            </div>
                          )}
                        </div>
                        <div className="flex flex-1 flex-col p-4">
                          <p className="text-xs text-muted-foreground">{product.categoryLabel}</p>
                          <p className="mt-1 line-clamp-2 font-medium leading-snug">{product.name}</p>
                          {product.shopName ? (
                            <p className="mt-1 truncate text-xs text-muted-foreground">
                              {product.shopName}
                            </p>
                          ) : null}
                          <p className="mt-auto pt-3 font-semibold">{product.priceDisplay}</p>
                        </div>
                      </a>
                    </li>
                  ))}
                </ul>

                {results.totalPages > 1 ? (
                  <nav
                    aria-label="Search results pages"
                    className="mt-8 flex items-center justify-between gap-4 rounded-full border border-border bg-card px-4 py-2"
                  >
                    {page > 0 ? (
                      <a
                        href={withParams({ page: page - 1 })}
                        className="rounded-full px-3 py-1.5 text-sm hover:bg-secondary"
                      >
                        ← Previous
                      </a>
                    ) : (
                      <span className="px-3 py-1.5 text-sm text-muted-foreground">← Previous</span>
                    )}

                    <span className="text-sm text-muted-foreground">
                      Page {page + 1} of {results.totalPages}
                    </span>

                    {page + 1 < results.totalPages ? (
                      <a
                        href={withParams({ page: page + 1 })}
                        className="rounded-full px-3 py-1.5 text-sm hover:bg-secondary"
                      >
                        Next →
                      </a>
                    ) : (
                      <span className="px-3 py-1.5 text-sm text-muted-foreground">Next →</span>
                    )}
                  </nav>
                ) : null}
              </>
            )}
          </section>
        </div>
      )}
    </main>
  );
}

function FilterPill({
  href,
  active,
  children,
}: {
  href: string;
  active: boolean;
  children: React.ReactNode;
}) {
  return (
    <a
      href={href}
      aria-current={active ? 'true' : undefined}
      className={
        active
          ? 'rounded-full bg-foreground px-3.5 py-1.5 text-sm font-medium text-background'
          : 'rounded-full border border-border bg-card px-3.5 py-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground'
      }
    >
      {children}
    </a>
  );
}

function EmptyState({ title, body }: { title: string; body: string }) {
  return (
    <div className="rounded-[1.75rem] border border-dashed border-border bg-card/50 px-6 py-16 text-center">
      <p className="font-medium">{title}</p>
      <p className="mx-auto mt-2 max-w-md text-sm text-muted-foreground">{body}</p>
    </div>
  );
}
