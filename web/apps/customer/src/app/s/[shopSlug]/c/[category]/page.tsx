import type { Metadata } from 'next';
import Link from 'next/link';

import { getCategories, getProducts, rupees } from '@/lib/storefront';
import StorefrontLayout from '@/app/s/[shopSlug]/layout';

/**
 * A category listing (D1).
 *
 * <p>The handle is the enum value, slugged — `/c/rice` for `RICE` — because a category is an enum
 * constant rather than a database row (D17). An unknown or out-of-type category is refused by the
 * API, and this page turns that into a 404 rather than an empty grid that looks like an empty shop.
 */
export async function generateMetadata({
  params,
}: {
  params: Promise<{ shopSlug: string; category: string }>;
}): Promise<Metadata> {
  const { shopSlug, category } = await params;
  const tile = (await getCategories(shopSlug)).find(
    (c) => c.category.toLowerCase() === category.toLowerCase(),
  );
  return { title: tile ? `${tile.label} — shop` : 'Category' };
}

export default async function CategoryPage({
  params,
}: {
  params: Promise<{ shopSlug: string; category: string }>;
}) {
  const { shopSlug, category } = await params;
  const [categories, listing] = await Promise.all([
    getCategories(shopSlug),
    getProducts(shopSlug, category.toUpperCase()),
  ]);

  const tile = categories.find((c) => c.category.toLowerCase() === category.toLowerCase());

  // A category the shop does not stock, or one outside its shop type. A 404 rather than an empty
  // page: "nothing here" and "this does not exist" are different answers and only one is true.
  if (!tile || !listing) {
    return (
      <StorefrontLayout>
        <main className="mx-auto max-w-3xl px-4 py-24 text-center">
          <h1 className="text-2xl font-bold">Category not found</h1>
          <p className="mt-2 text-sm text-muted-foreground">
            This shop does not have a &ldquo;{category}&rdquo; section.
          </p>
          <Link href={`/s/${shopSlug}`} className="mt-4 inline-block text-sm underline">
            Back to the shop
          </Link>
        </main>
      </StorefrontLayout>
    );
  }

  return (
    <StorefrontLayout>
      <main className="mx-auto max-w-6xl px-4 py-8">
        <nav aria-label="Breadcrumb" className="mb-4 text-sm text-muted-foreground">
          <Link href={`/s/${shopSlug}`} className="hover:text-foreground">
            {listing.shopName}
          </Link>
          <span className="mx-2">/</span>
          <span>{tile.label}</span>
        </nav>

        <h1 className="text-2xl font-semibold">{tile.label}</h1>
        <p className="mt-1 text-sm text-muted-foreground">{tile.count} products</p>

        {listing.products.length === 0 ? (
          <p className="mt-10 rounded-lg border border-dashed p-10 text-center text-sm text-muted-foreground">
            Nothing in this category yet.
          </p>
        ) : (
          <ul className="mt-6 grid gap-4 sm:grid-cols-3 lg:grid-cols-4">
            {listing.products.map((p) => (
              <li key={p.id}>
                <Link
                  href={`/s/${shopSlug}/p/${p.slug}`}
                  className="group block overflow-hidden rounded-lg border transition-colors hover:bg-muted"
                >
                  <div className="aspect-square w-full overflow-hidden bg-muted">
                    {p.imageUrl ? (
                      // eslint-disable-next-line @next/next/no-img-element
                      <img
                        src={p.imageUrl}
                        alt={p.name}
                        loading="lazy"
                        className="h-full w-full object-cover transition-transform group-hover:scale-105"
                      />
                    ) : (
                      <div className="flex h-full items-center justify-center text-xs text-muted-foreground">
                        No image
                      </div>
                    )}
                  </div>
                  <div className="p-3">
                    <h2 className="line-clamp-2 text-sm font-medium leading-snug">{p.name}</h2>
                    <p className="mt-1 text-sm font-semibold">{rupees(p.price)}</p>
                  </div>
                </Link>
              </li>
            ))}
          </ul>
        )}

        <section className="mt-12">
          <h2 className="mb-3 text-sm font-medium text-muted-foreground">Other categories</h2>
          <ul className="flex flex-wrap gap-2">
            {categories
              .filter((c) => c.category !== tile.category)
              .map((c) => (
                <li key={c.category}>
                  <Link
                    href={`/s/${shopSlug}/c/${c.category.toLowerCase()}`}
                    className="inline-flex rounded-full border px-3 py-1.5 text-sm hover:bg-muted"
                  >
                    {c.label} ({c.count})
                  </Link>
                </li>
              ))}
          </ul>
        </section>
      </main>
    </StorefrontLayout>
  );
}