import { useQuery, type UseQueryResult } from '@tanstack/react-query';

import type { ShopTypeResponse, ShopTypeCode } from './types';

// NOTE: this package is shared by the Vite console and the Next.js storefront, and only
// Next.js defines `process` in the browser. A bare `process.env` here is a ReferenceError at
// module scope, which blanks the whole app before React mounts. Vite's `define` does not cover
// workspace packages served from outside the app root, so the guard below is what actually makes
// this work. See apps/console/vite.config.ts and plan Appendix P.
function readEnv(key: string): string | undefined {
  if (typeof process !== 'undefined' && process.env) {
    return process.env[key];
  }
  return (import.meta as unknown as { env?: Record<string, string | undefined> }).env?.[key];
}

const API_URL =
  readEnv('NEXT_PUBLIC_API_URL') ?? readEnv('VITE_API_URL') ?? 'http://localhost:8080';

/**
 * The category tree is small, identical for every caller, and never changes without a backend
 * deploy. A long stale time keeps the signup form instant and removes a request from every page
 * load; the trade-off is a newly added category not appearing until the cache expires, which is
 * an acceptable delay for a code change that already required a deploy.
 */
export const SHOP_TYPES_STALE_TIME = 60 * 60 * 1000; // 1 hour
export const shopTypesQueryKey = ['meta', 'shop-types'] as const;

async function fetchShopTypes(): Promise<ShopTypeResponse[]> {
  const res = await fetch(`${API_URL}/api/v1/meta/shop-types`, {
    headers: { Accept: 'application/json' },
  });
  if (!res.ok) {
    throw new Error(`Failed to load shop types: ${res.status}`);
  }
  return res.json();
}

/**
 * All shop types with their permitted product categories.
 *
 * Public endpoint — no token required — because it is needed on the signup screen before a
 * user has any relationship with the platform.
 */
export function useShopTypes(): UseQueryResult<ShopTypeResponse[]> {
  return useQuery({
    queryKey: shopTypesQueryKey,
    queryFn: fetchShopTypes,
    staleTime: SHOP_TYPES_STALE_TIME,
    gcTime: SHOP_TYPES_STALE_TIME * 2,
    retry: 1,
  });
}

/** Convenience: find one type by code, for filtering a product form. */
export function useShopType(code: ShopTypeCode | undefined): ShopTypeResponse | undefined {
  const { data } = useShopTypes();
  return data?.find((t) => t.code === code);
}
