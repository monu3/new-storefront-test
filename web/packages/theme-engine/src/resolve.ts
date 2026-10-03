/**
 * Section data resolution — the part of the renderer with no JSX in it.
 *
 * A section's props say *what* to show ("source: featured, limit: 12") and the API supplies the
 * data. This module is the join between them, kept free of React so it can be tested directly
 * and reasoned about on its own.
 *
 * The whole point is that the storefront asks the API for one payload and resolves every
 * commerce section from it. The alternative is a storefront issuing six calls before the first
 * product appears.
 */

import type { ThemeSection } from './schema';

/** What the storefront endpoint returns. Shapes mirror `PublicThemeController.StorefrontData`. */
export interface StorefrontData {
  shopSlug: string;
  shopName: string;
  description?: string | null;
  logoUrl?: string | null;
  coverImageUrl?: string | null;
  followerCount?: number;
  shopType?: string;
  /** Already validated by the server; parsed here for safety. */
  themeConfig?: string | unknown;
  categories?: CategoryTile[];
  featuredProducts?: ProductSummary[];
  latestProducts?: ProductSummary[];
  assets?: ThemeAssetData[];
}

export interface CategoryTile {
  code: string;
  label: string;
  productCount: number;
}

export interface ProductSummary {
  id?: string;
  name: string;
  slug?: string;
  price: number;
  compareAt?: number | null;
  categoryLabel?: string;
  shortDescription?: string | null;
  hasDiscount?: boolean;
  imageUrl?: string | null;
}

export interface ThemeAssetData {
  id: string;
  kind: string;
  handle: string;
  name: string;
  config: string;
}

/** Props a section component receives. `data` is already resolved for its `source`. */
export interface ResolvedSection {
  section: ThemeSection;
  /** Products for a commerce section; empty otherwise. */
  products: ProductSummary[];
  /** Categories for a category grid; empty otherwise. */
  categories: CategoryTile[];
  /** The asset this section's `asset:` reference points at, if it exists. */
  asset: ThemeAssetData | null;
  /** The shop, for headings and links. */
  shop: { slug: string; name: string; logoUrl?: string | null; description?: string | null };
}

/** Where a section's content comes from. */
export type SectionSource = 'featured' | 'latest' | 'shop_categories' | 'reviews' | 'manual';

export function sectionSource(section: ThemeSection): SectionSource {
  const raw = section.props?.source;
  if (
    raw === 'featured' ||
    raw === 'latest' ||
    raw === 'shop_categories' ||
    raw === 'reviews' ||
    raw === 'manual'
  ) {
    return raw;
  }
  return 'manual';
}

export function sectionLimit(section: ThemeSection, fallback: number): number {
  const raw = section.props?.limit;
  const value = typeof raw === 'number' ? raw : Number(raw);
  if (!Number.isFinite(value) || value <= 0) {
    return fallback;
  }
  return Math.min(Math.floor(value), 60);
}

/**
 * Resolves one section against the storefront payload.
 *
 * An unknown `source` resolves to nothing rather than throwing. A storefront that 500s because a
 * shop typed `"source": "best_sellers"` takes down a shop's entire homepage; an empty section
 * takes down one block.
 */
export function resolveSection(section: ThemeSection, data: StorefrontData): ResolvedSection {
  const source = sectionSource(section);
  const limit = sectionLimit(section, 12);

  let products: ProductSummary[] = [];
  let categories: CategoryTile[] = [];

  switch (source) {
    case 'featured':
      products = (data.featuredProducts ?? []).slice(0, limit);
      break;
    case 'latest':
      products = (data.latestProducts ?? []).slice(0, limit);
      break;
    case 'shop_categories':
      // D17: resolved from the shop type's enum upstream, so this is a zero-query join.
      categories = (data.categories ?? []).slice(0, limit);
      break;
    case 'reviews':
    case 'manual':
    default:
      // Reviews do not exist until Phase 5, and `manual` means the owner listed the items
      // inline in props. Both render from props.
      break;
  }

  return {
    section,
    products,
    categories,
    asset: resolveAsset(section, data),
    shop: {
      slug: data.shopSlug,
      name: data.shopName,
      logoUrl: data.logoUrl ?? null,
      description: data.description ?? null,
    },
  };
}

/** Resolves every visible section, in order. */
export function resolveSections(
  sections: ThemeSection[],
  data: StorefrontData,
): ResolvedSection[] {
  return sections.filter((s) => s.hidden !== true).map((s) => resolveSection(s, data));
}

/**
 * Looks up the asset a section references.
 *
 * A section may say `imageUrl: "asset:HERO/main"`. A reference to something the shop has not
 * uploaded yet resolves to null and the component renders a placeholder — which is the point of
 * handles: a shop can lay out a page before it has a photograph for it.
 */
export function resolveAsset(section: ThemeSection, data: StorefrontData): ThemeAssetData | null {
  const url = section.props?.imageUrl;
  if (typeof url !== 'string' || !url.startsWith('asset:')) {
    return null;
  }
  const reference = url.slice('asset:'.length);
  const slash = reference.indexOf('/');
  if (slash === -1) {
    return null;
  }
  const kind = reference.slice(0, slash);
  const handle = reference.slice(slash + 1);
  return (data.assets ?? []).find((a) => a.kind === kind && a.handle === handle) ?? null;
}

/** The image URL a section should render, preferring the resolved asset over a literal URL. */
export function sectionImageUrl(resolved: ResolvedSection): string | null {
  const { section, asset } = resolved;
  if (asset) {
    try {
      const config = JSON.parse(asset.config) as { imageUrl?: string };
      if (config.imageUrl) {
        return config.imageUrl;
      }
    } catch {
      // A malformed asset config renders without an image, which is the degraded state.
    }
  }
  const url = section.props?.imageUrl;
  return typeof url === 'string' && url && !url.startsWith('asset:') ? url : null;
}

/** Formats a price in paisa as rupees, for display. */
export function formatPrice(minor: number): string {
  return `Rs. ${(minor / 100).toLocaleString('en-NP', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
}
