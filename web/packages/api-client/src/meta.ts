import { api } from './client';
import type { ShopTypeResponse, ProductCategoryCode } from '@store-app/enums';

/**
 * Meta endpoints. Public, so no Authorization header is attached — the shop-type picker is
 * needed on the signup screen before the user has any relationship with the platform.
 */
export const meta = {
  shopTypes: () => api.get<ShopTypeResponse[]>('/api/v1/meta/shop-types'),

  shopType: (code: string) => api.get<ShopTypeResponse>(`/api/v1/meta/shop-types/${code}`),
};

/**
 * Product payloads.
 *
 * Money is always sent and received as an integer number of paisa. A float crossing this
 * boundary is how rounding bugs enter a ledger, so the type forbids it at compile time and the
 * formatter below is the only place a decimal string is produced (for display).
 */
export interface ProductDraft {
  productCategory: ProductCategoryCode;
  name: string;
  slug: string;
  shortDescription?: string;
  description?: string;
  basePriceMinor: number;
  compareAtMinor?: number;
  weightGrams?: number;
  trackInventory: boolean;
  variants?: ProductVariantDraft[];
}

export interface ProductVariantDraft {
  sku?: string;
  name: string;
  optionValues: Record<string, string>;
  priceMinor: number;
  compareAtMinor?: number;
  barcode?: string;
}

export interface CreateProductRequest {
  productCategory: ProductCategoryCode;
  name: string;
  shortDescription?: string;
  description?: string;
  basePriceMinor: number;
  compareAtMinor?: number;
  weightGrams?: number;
  trackInventory: boolean;
  variants?: ProductVariantDraft[];
}

export interface OrderQuoteRequest {
  shopSlug: string;
  items: Array<{ variantId: string; quantity: number }>;
  discountCode?: string;
  shippingZoneId?: string;
}

export interface OrderQuoteResponse {
  subtotalMinor: number;
  discountMinor: number;
  taxMinor: number;
  shippingMinor: number;
  totalMinor: number;
  /** What the platform would earn on this order if the shop were on the free plan. */
  commissionPreviewMinor?: number;
  commissionWaived: boolean;
}

export interface PlaceOrderRequest extends OrderQuoteRequest {
  paymentMethod: 'ESAWA' | 'COD' | 'BANK_TRANSFER';
  guestEmail?: string;
  guestPhone?: string;
  shippingAddress: Record<string, string>;
  customerNote?: string;
}

export const orders = {
  /** Server-side repricing. The client never sends prices, only ids and quantities. */
  quote: (body: OrderQuoteRequest) =>
    api.post<OrderQuoteResponse>('/api/v1/orders/quote', body, {
      // A quote is not a money movement, but it is a POST that may be retried; the key keeps
      // repeated clicks from creating parallel evaluation work.
      idempotencyKey: `quote_${body.shopSlug}_${body.items.map((i) => i.variantId).join('-')}`,
    }),

  place: (body: PlaceOrderRequest) =>
    api.post<{ id: string; orderNumber: string }>('/api/v1/orders', body, {
      idempotencyKey: `order_${body.shopSlug}_${Date.now()}`,
    }),
};

/** Minor units to a rupee display string, e.g. 125000 -> "Rs. 1,250.00". */
export function formatMinor(amountMinor: number, currency = 'NPR'): string {
  const value = amountMinor / 100;
  const formatted = new Intl.NumberFormat('en-NP', {
    style: 'currency',
    currency,
    minimumFractionDigits: 2,
  }).format(value);
  return formatted.replace(/^NPR\s?/, 'Rs. ');
}
