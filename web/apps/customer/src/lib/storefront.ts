/**
 * Public storefront types, mirroring the API's DTOs.
 *
 * <p>Declared here rather than imported from `@store-app/api-client` because these endpoints are
 * read on the **server** during SSR, and the shared client attaches a Bearer token it cannot have
 * in a server component. The field names match the server exactly — that is the part that matters.
 * A rename on one side only produces `undefined` at runtime and, in the case of a price, `Rs. NaN`
 * with nothing logged.
 */

export interface ProductCard {
  id: string;
  slug: string;
  name: string;
  category: string;
  categoryLabel?: string | null;
  shortDescription?: string | null;
  /** Minor units (paisa). */
  price: number;
  compareAt?: number | null;
  hasDiscount?: boolean;
  featured?: boolean;
  imageUrl?: string | null;
}

export interface ProductListResponse {
  products: ProductCard[];
  page: number;
  size: number;
  shopName: string;
  category?: string | null;
}

export interface CategoryTile {
  category: string;
  label: string;
  count: number;
}

export interface Variant {
  id: string;
  name: string;
  sku?: string | null;
  price: number;
  compareAt?: number | null;
  imageUrl?: string | null;
  active: boolean;
  options?: Array<{ axis?: string; value?: string }>;
  optionsLabel?: string | null;
  hasDiscount?: boolean;
}

export interface ProductDetail extends ProductCard {
  shopSlug: string;
  shopName: string;
  description?: string | null;
  popular?: boolean;
  allowBackorder?: boolean;
  images: string[];
  variants: Variant[];
  related: ProductCard[];
}

export interface SearchResult {
  shops: Array<{
    slug: string;
    name: string;
    shopType?: string | null;
    logoUrl?: string | null;
    matchCount?: number;
  }>;
  products: Array<ProductCard & { shopSlug: string; shopName: string }>;
}

/** Minor units (paisa) formatted as rupees. Every price on the storefront goes through this. */
export function rupees(minor: number | null | undefined): string {
  const value = (minor ?? 0) / 100;
  return `Rs. ${value.toLocaleString('en-NP', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
}

/** Reads the API base URL on the server, where `import.meta.env` does not exist. */
function serverApiUrl(): string {
  return process.env.NEXT_PUBLIC_API_URL ?? process.env.API_URL ?? 'http://localhost:8080';
}

/**
 * Fetches JSON on the server, returning null instead of throwing.
 *
 * <p>A storefront must not 500 because one section of data is briefly unavailable. The caller
 * decides what a missing payload means — an empty list, an empty state, or a "not found" page —
 * because only it knows whether the absence is normal.
 */
async function serverGet<T>(path: string, revalidate: number, tags: string[]): Promise<T | null> {
  try {
    const res = await fetch(`${serverApiUrl()}${path}`, {
      headers: { Accept: 'application/json' },
      next: { revalidate, tags },
    });
    if (!res.ok) return null;
    return (await res.json()) as T;
  } catch {
    return null;
  }
}

export async function getCategories(shopSlug: string): Promise<CategoryTile[]> {
  return (await serverGet<CategoryTile[]>(
    `/api/v1/p/shops/${encodeURIComponent(shopSlug)}/categories`,
    300,
    [`shop:${shopSlug}`],
  )) ?? [];
}

export async function getProducts(
  shopSlug: string,
  category?: string | null,
  page = 0,
  size = 24,
): Promise<ProductListResponse | null> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (category) query.set('category', category);
  return serverGet<ProductListResponse>(
    `/api/v1/p/shops/${encodeURIComponent(shopSlug)}/products?${query.toString()}`,
    60,
    [`shop:${shopSlug}`, `products:${shopSlug}`],
  );
}

export async function getProduct(shopSlug: string, productSlug: string): Promise<ProductDetail | null> {
  return serverGet<ProductDetail>(
    `/api/v1/p/shops/${encodeURIComponent(shopSlug)}/products/${encodeURIComponent(productSlug)}`,
    60,
    [`shop:${shopSlug}`, `product:${shopSlug}`],
  );
}

export async function search(query: string): Promise<SearchResult | null> {
  return serverGet<SearchResult>(
    `/api/v1/p/search?q=${encodeURIComponent(query)}`,
    30,
    ['search'],
  );
}

export async function getShopSummary(shopSlug: string): Promise<{ shopName: string } | null> {
  return serverGet<{ shopName: string }>(
    `/api/v1/p/shops/${encodeURIComponent(shopSlug)}/products?size=1`,
    300,
    [`shop:${shopSlug}`],
  );
}