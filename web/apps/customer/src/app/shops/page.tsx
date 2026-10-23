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
 * Server-rendered so the list is crawlable and the first paint needs no JavaScript. Shop types
 * are fetched separately because they are static reference data: a slow or failing meta
 * endpoint must not take the gallery down with it.
 */
async function getShops(): Promise<
  Array<{ slug: string; name: string; shopType: string; logoUrl?: string }>
> {
  try {
    const res = await fetch(`${API_URL}/api/v1/shops`, {
      headers: { Accept: 'application/json' },
      next: { revalidate: 300, tags: ['shops'] },
    });
    if (!res.ok) return [];

    // The endpoint returns a paged envelope ({shops: [...], size, ...}), not a bare array.
    // Assuming a bare array produced `shops.map is not a function` and a 500 on the home page —
    // the one URL every visitor lands on. Unwrapped defensively so a future envelope change
    // degrades to an empty gallery rather than a stack trace.
    const body = (await res.json()) as
      | Array<{ slug: string; name: string; shopType: string; logoUrl?: string }>
      | { shops?: Array<{ slug: string; name: string; shopType: string; logoUrl?: string }> };

    return Array.isArray(body) ? body : (body.shops ?? []);
  } catch {
    // A gallery must not 500 because the API is briefly down; an empty state is more useful than
    // an error page a visitor cannot act on.
    return [];
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

export default async function GalleryPage() {
  const [shops, shopTypes] = await Promise.all([getShops(), getShopTypes()]);
  // Keyed by string, not ShopTypeCode: shop.shopType comes from the API and is untrusted
  // until validated. A shop row with a type the enum does not know must not crash the
  // gallery; the fallback below shows the raw code instead.
  const typeLabel = new Map<string, string>(shopTypes.map((t) => [t.code as string, t.label]));

  return (
    <main className="mx-auto max-w-6xl px-4 py-10">
      <header className="mb-8">
        <h1 className="text-3xl font-semibold">Shops</h1>
        <p className="mt-1 text-muted-foreground">
          {shops.length} {shops.length === 1 ? 'shop' : 'shops'} trading on store.app
        </p>
      </header>

      {shopTypes.length > 0 && (
        <nav aria-label="Filter by shop type" className="mb-8 flex flex-wrap gap-2">
          {shopTypes.map((type) => {
            const Icon = shopTypeIcon(type.icon);
            return (
              <a
                key={type.code}
                href={`/?type=${type.code}`}
                className="inline-flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-sm hover:bg-accent"
              >
                <Icon className="h-4 w-4" aria-hidden />
                {type.label}
              </a>
            );
          })}
        </nav>
      )}

      {shops.length === 0 ? (
        <div className="rounded-lg border border-dashed p-16 text-center text-muted-foreground">
          <p className="font-medium">No shops published yet.</p>
          <p className="mt-1 text-sm">
            Shops appear here once an admin verifies them. The API currently returns an empty
            list, which is expected before Phase 1.
          </p>
        </div>
      ) : (
        <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {shops.map((shop) => (
            <li key={shop.slug}>
              <a
                href={`/s/${shop.slug}`}
                className="block rounded-lg border p-4 transition-colors hover:bg-accent"
              >
                <div className="flex items-center gap-3">
                  {shop.logoUrl && (
                    // eslint-disable-next-line @next/next/no-img-element
                    <img src={shop.logoUrl} alt="" className="h-10 w-10 rounded" />
                  )}
                  <div className="min-w-0">
                    <p className="truncate font-medium">{shop.name}</p>
                    <p className="truncate text-sm text-muted-foreground">
                      {typeLabel.get(shop.shopType) ?? shop.shopType}
                    </p>
                  </div>
                </div>
              </a>
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
