import type { Metadata } from 'next';

import { API_URL } from '@store-app/api-client';
import { parseTheme, themeScopeStyle } from '@store-app/theme-engine';

import StorefrontLayout from './layout';

interface StorefrontData {
  shop: { name: string; description?: string; logoUrl?: string; slug: string };
  theme: unknown;
}

/**
 * Storefront home, rendered on the server (D1).
 *
 * Server rendering is what makes product pages indexable and share-previewable, which the
 * WhatsApp and social selling flow depends on. The theme config is fetched here and the section
 * list is resolved into markup before the HTML is streamed.
 */
async function getStorefront(shopSlug: string): Promise<StorefrontData | null> {
  try {
    const res = await fetch(`${API_URL}/api/v1/shops/${shopSlug}`, {
      headers: { Accept: 'application/json' },
      // Revalidate every five minutes; a published theme should appear quickly without
      // hammering the API on every request.
      next: { revalidate: 300, tags: [`storefront:${shopSlug}`] },
    });
    if (!res.ok) return null;
    return await res.json();
  } catch {
    // A storefront must not 500 because the API is briefly down; a 404 page is more useful.
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
    title: data.shop.name,
    description: data.shop.description ?? `Shop at ${data.shop.name}`,
    openGraph: {
      title: data.shop.name,
      description: data.shop.description ?? '',
      images: data.shop.logoUrl ? [data.shop.logoUrl] : undefined,
      type: 'website',
    },
  };
}

export default async function ShopHomePage({
  params,
}: {
  params: Promise<{ shopSlug: string }>;
}) {
  const { shopSlug } = await params;
  const data = await getStorefront(shopSlug);

  if (!data) {
    return (
      <StorefrontLayout>
        <main className="mx-auto max-w-3xl px-4 py-24 text-center">
          <h1 className="text-2xl font-semibold">This shop is not available</h1>
          <p className="mt-2 text-muted-foreground">
            The link may be wrong, or the shop may have been suspended.
          </p>
        </main>
      </StorefrontLayout>
    );
  }

  const { theme, issues } = parseTheme(data.theme);

  return (
    <StorefrontLayout>
      <div style={themeScopeStyle(theme.tokens)}>
        <header className="border-b">
          <div className="mx-auto flex max-w-6xl items-center gap-3 px-4 py-4">
            {data.shop.logoUrl && (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={data.shop.logoUrl} alt="" className="h-9 w-9 rounded" />
            )}
            <span className="font-semibold">{data.shop.name}</span>
          </div>
        </header>

        <main className="mx-auto max-w-6xl px-4 py-8">
          {issues.length > 0 && (
            <div className="mb-6 rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm">
              This theme could not be fully loaded ({issues.length} issue
              {issues.length === 1 ? '' : 's'}), so it is showing defaults.
            </div>
          )}

          {theme.sections.length === 0 ? (
            <div className="rounded-lg border border-dashed p-12 text-center text-muted-foreground">
              <p className="font-medium">This storefront has no sections yet.</p>
              <p className="mt-1 text-sm">
                The shop owner can add sections from the appearance editor.
              </p>
            </div>
          ) : (
            <div className="space-y-8">
              {theme.sections.map((section) => (
                <section
                  key={section.id}
                  data-section-type={section.type}
                  className="rounded-lg border border-dashed p-6 text-sm text-muted-foreground"
                >
                  <span className="font-mono text-xs">{section.type}</span>
                  <p className="mt-1">
                    Section rendering lands in Phase 3. The theme JSON is already flowing
                    end to end.
                  </p>
                </section>
              ))}
            </div>
          )}
        </main>
      </div>
    </StorefrontLayout>
  );
}
