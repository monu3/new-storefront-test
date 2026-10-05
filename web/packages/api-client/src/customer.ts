/**
 * Types for the customer portal (Phase 5).
 *
 * <p>Mirrors the backend records exactly, including the fields whose purpose is to stop the client
 * guessing: `paid` on a payment status, `complete` on a reorder preview, `canBeEnabled` on a
 * payment method. Each is a decision the server has already made.
 *
 * <p>Amounts are paisa integers everywhere. Where the server also sends a display string, that
 * string wins — one formatter, not two.
 */

/**
 * A page, in the offset-paged shape the API returns.
 *
 * <p>Named {@code CustomerPage}, not {@code Page}: {@code client.ts} already exports a
 * {@code Page<T>} that is a *cursor* page ({@code items} + {@code nextCursor}), and re-exporting
 * both under one name makes the import ambiguous for every caller.
 */
export interface CustomerPage<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

// ---------------------------------------------------------------- orders

export interface OrderSummary {
  orderNumber: string;
  status: string;
  paymentMethod: string;
  paymentStatus: string;
  totalMinor: number;
  totalDisplay: string;
  placedAt: string;
}

/**
 * One order, with its timeline.
 *
 * <p>No address. The backend deliberately does not put the delivery address on a customer-facing
 * order response: it is on the order, but it is not something a list or a link should carry.
 */
export interface OrderDetail {
  id: string;
  orderNumber: string;
  status: string;
  paymentMethod: string;
  paymentStatus: string;
  totalMinor: number;
  totalDisplay: string;
  placedAt: string;
  timeline: TrackingStep[];
  lines: OrderLine[];
}

/**
 * One step of the tracking timeline.
 *
 * <p>Read from `order_status_history`, which is written by the same transition that moved the
 * order — so the timeline cannot disagree with the status above it.
 */
export interface TrackingStep {
  status: string;
  note: string | null;
  at: string;
}

export interface OrderLine {
  productName: string;
  variantName: string | null;
  quantity: number;
  unitPriceMinor: number;
  unitPriceDisplay: string;
  lineTotalMinor: number;
  lineTotalDisplay: string;
  imageUrl: string | null;
}

/**
 * The answer to "has this been paid?".
 *
 * <p>From a server-to-server status query, never from the browser redirect — so `paid` is the only
 * field that may be trusted to mean money arrived, and `PENDING` genuinely means "we are still
 * asking" rather than "no".
 */
export interface PaymentStatus {
  status: 'PAID' | 'PENDING' | 'FAILED' | 'REFUNDED' | 'UNKNOWN';
  message: string;
  paid: boolean;
}

// ---------------------------------------------------------------- reorder

/**
 * A rebuild of a past order's basket.
 *
 * <p>A preview, not an order. `complete` is false when any line could not be bought again, and
 * `unavailable` says why — a reorder that quietly drops half the items is worse than one that
 * admits it.
 */
export interface ReorderPreview {
  orderNumber: string;
  available: ReorderLine[];
  unavailable: UnavailableLine[];
  lineCount: number;
  complete: boolean;
  message: string;
}

export interface ReorderLine {
  variantId: string;
  productName: string;
  variantName: string | null;
  quantity: number;
  priceMinor: number;
}

export interface UnavailableLine {
  productName: string;
  variantName: string | null;
  reason: string;
}

// ---------------------------------------------------------------- addresses

export interface Address {
  id: string;
  label: string;
  recipient: string;
  phone: string;
  line1: string;
  line2: string | null;
  city: string;
  state: string | null;
  postalCode: string | null;
  country: string;
  isDefault: boolean;
}

/** One line, for the checkout picker. */
export function addressOneLine(a: Address): string {
  return [a.line1, a.line2, a.city, a.state, a.postalCode, a.country]
    .filter((part): part is string => Boolean(part && part.trim()))
    .join(', ');
}

// ---------------------------------------------------------------- wishlist

export interface WishlistItem {
  productId: string;
  productName: string;
  productSlug: string;
  priceMinor: number;
  priceDisplay: string;
  shortDescription: string | null;
  /** Null renders a placeholder, never a broken image. */
  imageUrl: string | null;
  savedAt: string;
}

export interface WishlistResult {
  added: boolean;
  count: number;
  message: string;
}

// ---------------------------------------------------------------- reviews

export interface Review {
  id: string;
  productId: string;
  authorName: string;
  rating: number;
  body: string | null;
  /** From an order that actually contained this product — not merely a signed-in account. */
  verified: boolean;
  createdAt: string;
}

export interface RatingSummary {
  productId: string;
  average: number;
  count: number;
  /** "No reviews yet" rather than an empty number, because a card has to render something. */
  display: string;
}

// ---------------------------------------------------------------- search

/**
 * A search result: the shops *and* the products.
 *
 * <p>A customer searching "paracetamol" wants to know which pharmacies sell it, not to scroll
 * forty listings from one shop. The shops are the primary answer.
 */
export interface SearchResults {
  query: string;
  shopType: string | null;
  products: SearchProduct[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  shops: SearchShop[];
  /** Why the result is empty, when it is. Never a bare "no results". */
  hint: string | null;
}

export interface SearchShop {
  id: string;
  slug: string;
  name: string;
  shopType: string;
  matches: number;
}

export interface SearchProduct {
  id: string;
  name: string;
  slug: string;
  category: string;
  categoryLabel: string;
  priceMinor: number;
  priceDisplay: string;
  shortDescription: string | null;
  imageUrl: string | null;
  shopSlug: string | null;
  shopName: string | null;
}