import type { Metadata } from 'next';

import { getProduct } from '@/lib/storefront';
import StorefrontLayout from '@/app/s/[shopSlug]/layout';
import { ProductView } from './product-view';

/**
 * The product detail page (D1).
 *
 * <p>Server-rendered, so a product link is indexable and shares with a preview on WhatsApp — the
 * multi-channel requirement the plan calls out. `generateMetadata` is what makes the shared card
 * show a name and description instead of a bare URL.
 */
export async function generateMetadata({
  params,
}: {
  params: Promise<{ shopSlug: string; handle: string }>;
}): Promise<Metadata> {
  const { shopSlug, handle } = await params;
  const product = await getProduct(shopSlug, handle);

  if (!product) return { title: 'Product not found' };

  const description = product.shortDescription ?? `${product.name} at ${product.shopName}`;
  return {
    title: `${product.name} — ${product.shopName}`,
    description,
    openGraph: {
      title: product.name,
      description,
      images: product.imageUrl ? [{ url: product.imageUrl }] : undefined,
    },
  };
}

export default async function ProductPage({
  params,
}: {
  params: Promise<{ shopSlug: string; handle: string }>;
}) {
  const { shopSlug, handle } = await params;
  const product = await getProduct(shopSlug, handle);

  if (!product) {
    return (
      <StorefrontLayout>
        <main className="mx-auto max-w-3xl px-4 py-24 text-center">
          <h1 className="text-2xl font-bold">Product not found</h1>
          <p className="mt-2 text-sm text-muted-foreground">
            This product may have sold out or been removed by the shop.
          </p>
        </main>
      </StorefrontLayout>
    );
  }

  return (
    <StorefrontLayout>
      <ProductView product={product} />
    </StorefrontLayout>
  );
}