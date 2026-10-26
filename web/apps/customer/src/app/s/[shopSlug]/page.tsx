import type { Metadata } from 'next';

import { API_URL } from '@store-app/api-client';
import { ThemeRenderer } from '@store-app/theme-engine';
import type { StorefrontData } from '@store-app/theme-engine';

import StorefrontLayout from './layout';

interface StorefrontResponse {
  shopSlug: string;
  shopName: string;
  description?: string | null;
  logoUrl?: string | null;
  coverImageUrl?: string | null;
  followerCount?: number;
  shopType?: string;
  themeConfig?: string;
  sectionCount?: number;
  fromPublishedTheme?: boolean;
  categories?: StorefrontData['categories'];
  featuredProducts?: StorefrontData['featuredProducts'];
  latestProducts?: StorefrontData['latestProducts'];
  assets?: StorefrontData['assets'];
}

/**
 * Storefront home, rendered on the server (D1, D8).
 *
 * <p>One fetch for everything the page needs: the shop, its published theme, and the data the
 * commerce sections render from. The alternative is a storefront issuing six calls before the
 * first product appears, and a page whose content depends on how many of them were fast.
 *
 * <p>The theme document is rendered by `ThemeRenderer` — the same function the console's editor
 * preview calls. That is what "the preview matches the storefront" means concretely: there is
 * only one implementation, so the two cannot drift.
 */
async function getStorefront(shopSlug: string): Promise<StorefrontResponse | null> {
  try {
    const res = await fetch(`${API_URL}/api/v1/p/shops/${shopSlug}/storefront`, {
      headers: { Accept: 'application/json' },
      // A published theme should appear quickly, without hammering the API on every request.
      next: { revalidate: 60, tags: [`storefront:${shopSlug}`, `theme:${shopSlug}`] },
    });
    if (!res.ok) return null;
    return (await res.json()) as StorefrontResponse;
  } catch {
    // A storefront must not 500 because the API is briefly down; a 404 is more useful than a
    // stack trace a customer would never read.
    return null;
  }
}

export async function generateMetadata({
  params,
}: {
  params: Promise<{ shopSlug: string }>;
}): Promise<Metadata> {
  const { shopSlug } = await params;
  const data = await getStorefront(shopSlug);

  if (!data) {
    return { title: 'Shop not found' };
  }

  return {
    title: data.shopName,
    description: data.description ?? `Shop at ${data.shopName}`,
    openGraph: {
      title: data.shopName,
      description: data.description ?? `Shop at ${data.shopName}`,
      // The cover image is what a WhatsApp or Facebook preview shows. Without it a shared link
      // renders as a bare title, which is the difference between a link that gets clicked and one
      // that does not — and social selling is a Phase 8 feature this has to be ready for.
      images: data.coverImageUrl ? [{ url: data.coverImageUrl }] : undefined,
    },
  };
}

export default async function ShopHome({
  params,
}: {
  params: Promise<{ shopSlug: string }>;
}) {
  const { shopSlug } = await params;
  const data = await getStorefront(shopSlug);

  if (!data) {
    return (
      <StorefrontLayout>
        <div className="mx-auto max-w-3xl px-4 py-24 text-center">
          <h1 className="text-2xl font-bold">Shop not found</h1>
          <p className="mt-2 text-sm text-muted-foreground">
            This shop may not exist, or it may not be published yet.
          </p>
        </div>
      </StorefrontLayout>
    );
  }

  return (
    <StorefrontLayout>
      {/* The theme owns the column width (layout.width) and the page surface, so this wrapper
          must impose neither. The shell already provides the `main` landmark. */}
      <div>
        <ThemeRenderer
          config={data.themeConfig ?? {}}
          data={{
            shopSlug: data.shopSlug,
            shopName: data.shopName,
            description: data.description ?? null,
            logoUrl: data.logoUrl ?? null,
            coverImageUrl: data.coverImageUrl ?? null,
            followerCount: data.followerCount,
            shopType: data.shopType,
            categories: data.categories ?? [],
            featuredProducts: data.featuredProducts ?? [],
            latestProducts: data.latestProducts ?? [],
            assets: data.assets ?? [],
          }}
        />
      </div>
    </StorefrontLayout>
  );
}
