import { shopTypeIcon } from '@store-app/icons';
import { SHOP_TYPES_STALE_TIME, type ShopTypeResponse } from '@store-app/enums';
import { API_URL } from '@store-app/api-client';

import type { Metadata } from 'next';

export const metadata: Metadata = {
  title: 'store.app — shops near you',
  description: 'Browse independent shops and buy directly from them.',
};

/**
 * Shop gallery (the home page).
 *
 * <p>Server-rendered so the list is crawlable and the first paint needs no JavaScript. Shop types
 * are fetched separately because they are static reference data: a slow or failing meta
 * endpoint must not take the gallery down with it.
 *
 * <p><b>The filters did not filter.</b> Each category chip linked to `/?type=…` — the landing page,
 * with a query parameter nothing read — and this page never passed `type` to the API at all. The
 * endpoint has supported filtering since it was written. A control that looks like a filter and
 * silently shows everything is worse than no control, because the customer concludes there is
 * nothing of that kind rather than that the chip is broken. The chips now point here and the value
 * reaches the query.
 */

interface GalleryShop {
  slug: string;
  name: string;
  description?: string | null;
  shopType: string;
  logoUrl?: string | null;
  coverUrl?: string | null;
  followerCount?: number;
}

interface GalleryResponse {
  shops: GalleryShop[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

const EMPTY: GalleryResponse = { shops: [], page: 0, size: 24, totalElements: 0, totalPages: 0 };

async function getShops(type: string | null, page: number): Promise<GalleryResponse> {
  try {
    const params = new URLSearchParams({ page: String(page), limit: '24' });
    if (type) params.set('type', type);

    const res = await fetch(`${API_URL}/api/v1/shops?${params}`, {
      headers: { Accept: 'application/json' },
      next: { revalidate: 300, tags: ['shops'] },
    });
    if (!res.ok) return EMPTY;

    // The endpoint returns a paged envelope ({shops: [...], size, ...}), not a bare array.
    // Assuming a bare array produced `shops.map is not a function` and a 500 on the home page —
    // the one URL every visitor lands on. Unwrapped defensively so a future envelope change
    // degrades to an empty gallery rather than a stack trace.
    const body = (await res.json()) as GalleryResponse | GalleryShop[];

    if (Array.isArray(body)) {
      return { ...EMPTY, shops: body, totalElements: body.length };
    }
    return {
      shops: body.shops ?? [],
      page: body.page ?? 0,
      size: body.size ?? 24,
      totalElements: body.totalElements ?? (body.shops ?? []).length,
      totalPages: body.totalPages ?? 0,
    };
  } catch {
    // A gallery must not 500 because the API is briefly down; an empty state is more useful than
    // an error page a visitor cannot act on.
    return EMPTY;
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

export default async function GalleryPage({
  searchParams,
}: {
  searchParams: Promise<{ type?: string; page?: string }>;
}) {
  const params = await searchParams;
  const shopTypes = await getShopTypes();

  // Validated against the reference list before it reaches the API. An unknown code in the URL is
  // a 400 from `ShopType` conversion, which this page would then report as "no shops" — telling a
  // customer the marketplace is empty because they followed a stale link.
  const knownTypes = new Set(shopTypes.map((t) => t.code as string));
  const requested = params.type?.toUpperCase() ?? null;
  const type = requested && knownTypes.has(requested) ? requested : null;
  const page = Math.max(0, Number(params.page ?? 0) || 0);

  const gallery = await getShops(type, page);

  // Keyed by string, not ShopTypeCode: shop.shopType comes from the API and is untrusted
  // until validated. A shop row with a type the enum does not know must not crash the
  // gallery; the fallback below shows the raw code instead.
  const typeLabel = new Map<string, string>(shopTypes.map((t) => [t.code as string, t.label]));

  const withType = (code: string | null, nextPage = 0) => {
    const p = new URLSearchParams();
    if (code) p.set('type', code);
    if (nextPage > 0) p.set('page', String(nextPage));
    const qs = p.toString();
    return qs ? `/shops?${qs}` : '/shops';
  };

  return (
    <main className="mx-auto max-w-7xl px-0 py-2">
      <header className="mb-8">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div>
            <h1 className="text-4xl font-semibold tracking-[-0.035em] sm:text-5xl">Shops</h1>
            <p className="mt-3 text-muted-foreground">
              {gallery.totalElements > 0 ? (
                <>
                  {gallery.totalElements.toLocaleString()}{' '}
                  {gallery.totalElements === 1 ? 'shop' : 'shops'} trading on store.app
                  {type ? ` in ${typeLabel.get(type) ?? type}` : ''}
                </>
              ) : (
                'No shops are trading yet'
              )}
            </p>
          </div>

          <a
            href="/search"
            className="inline-flex h-10 items-center gap-2 rounded-full border border-border bg-card px-4 text-sm transition-colors hover:text-primary"
          >
            <span aria-hidden>⌕</span>
            Search products
          </a>
        </div>
      </header>

      {shopTypes.length > 0 && (
        <nav aria-label="Filter by shop type" className="mb-8 flex flex-wrap gap-2">
          <FilterPill href={withType(null)} active={type === null}>
            All shops
          </FilterPill>
          {shopTypes.map((t) => {
            const Icon = shopTypeIcon(t.icon);
            return (
              <FilterPill
                key={t.code}
                href={withType(t.code as string)}
                active={type === t.code}
              >
                <Icon className="size-4 shrink-0" aria-hidden />
                {t.label}
              </FilterPill>
            );
          })}
        </nav>
      )}

      {gallery.shops.length === 0 ? (
        <div className="rounded-[1.75rem] border border-dashed border-border bg-card/50 px-6 py-16 text-center">
          <p className="font-medium">
            {type ? `No ${typeLabel.get(type) ?? type} shops are open yet.` : 'No shops published yet.'}
          </p>
          <p className="mx-auto mt-2 max-w-md text-sm text-muted-foreground">
            {type
              ? 'Shops appear here once an admin verifies them. Try another kind of shop, or see everything that is open.'
              : 'Shops appear here once an admin verifies them. If you run one, opening it takes a couple of minutes.'}
          </p>
          <a
            href={type ? '/shops' : '/login'}
            className="mt-6 inline-flex h-10 items-center rounded-full bg-primary px-5 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
          >
            {type ? 'See all shops' : 'Open your shop'}
          </a>
        </div>
      ) : (
        <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {gallery.shops.map((shop) => (
            <li key={shop.slug}>
              <a
                href={`/s/${shop.slug}`}
                className="group flex h-full flex-col overflow-hidden rounded-[1.5rem] border border-border bg-card transition-shadow hover:shadow-md"
              >
                <div className="relative aspect-[16/9] w-full overflow-hidden bg-secondary">
                  {shop.coverUrl ? (
                    // eslint-disable-next-line @next/next/no-img-element
                    <img
                      src={shop.coverUrl}
                      alt=""
                      className="size-full object-cover transition-transform duration-300 group-hover:scale-[1.03]"
                      loading="lazy"
                    />
                  ) : (
                    <div className="grid size-full place-items-center">
                      <span className="text-2xl font-semibold text-muted-foreground/60">
                        {shop.name.slice(0, 2).toUpperCase()}
                      </span>
                    </div>
                  )}
                </div>

                <div className="flex flex-1 items-start gap-3 p-5">
                  {shop.logoUrl ? (
                    // eslint-disable-next-line @next/next/no-img-element
                    <img
                      src={shop.logoUrl}
                      alt=""
                      className="size-10 shrink-0 rounded-xl object-cover"
                      loading="lazy"
                    />
                  ) : null}
                  <div className="min-w-0">
                    <p className="truncate font-semibold">{shop.name}</p>
                    <p className="truncate text-sm text-muted-foreground">
                      {typeLabel.get(shop.shopType) ?? shop.shopType}
                    </p>
                    {shop.description ? (
                      <p className="mt-2 line-clamp-2 text-sm text-muted-foreground">
                        {shop.description}
                      </p>
                    ) : null}
                  </div>
                </div>
              </a>
            </li>
          ))}
        </ul>
      )}

      {gallery.totalPages > 1 ? (
        <nav
          aria-label="Gallery pages"
          className="mt-8 flex items-center justify-between gap-4 rounded-full border border-border bg-card px-4 py-2"
        >
          {page > 0 ? (
            <a
              href={withType(type, page - 1)}
              className="rounded-full px-3 py-1.5 text-sm transition-colors hover:bg-secondary"
            >
              ← Previous
            </a>
          ) : (
            <span className="px-3 py-1.5 text-sm text-muted-foreground">← Previous</span>
          )}

          <span className="text-sm text-muted-foreground">
            Page {page + 1} of {gallery.totalPages}
          </span>

          {page + 1 < gallery.totalPages ? (
            <a
              href={withType(type, page + 1)}
              className="rounded-full px-3 py-1.5 text-sm transition-colors hover:bg-secondary"
            >
              Next →
            </a>
          ) : (
            <span className="px-3 py-1.5 text-sm text-muted-foreground">Next →</span>
          )}
        </nav>
      ) : null}
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
          ? 'inline-flex items-center gap-1.5 rounded-full bg-foreground px-3.5 py-1.5 text-sm font-medium text-background'
          : 'inline-flex items-center gap-1.5 rounded-full border border-border bg-card px-3.5 py-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground'
      }
    >
      {children}
    </a>
  );
}
