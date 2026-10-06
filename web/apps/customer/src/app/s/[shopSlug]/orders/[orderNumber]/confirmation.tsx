'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';

/** Mirrors the shape of the order the checkout endpoint returns. */
interface PlacedOrder {
  orderNumber: string;
  status: string;
  paymentMethod: string;
  totalDisplay: string;
  paymentInstructions?: string | null;
  deliveryCity?: string | null;
  lines: Array<{
    productName: string;
    variantName?: string | null;
    quantity: number;
    lineTotalMinor: number;
  }>;
}

/**
 * Order confirmation.
 *
 * <p><b>Rendered from what the order response already returned</b>, not from a follow-up fetch. The
 * checkout endpoint hands back the whole placed order, so re-requesting it would be a second round
 * trip to learn something already in hand — and, worse, it would need an endpoint that works for a
 * guest.
 *
 * <p>That last point is why this exists rather than reusing the account's order detail. Guest
 * checkout is permitted (D50) and is the common case on a storefront, so the customer who just paid
 * has no session and no {@code /api/v1/customer/...} access. Routing them to an authenticated page
 * showed an empty shell after a successful purchase, which reads as a failed order.
 *
 * <p>The order is kept in sessionStorage so a reload still renders the summary. A customer
 * reloading their confirmation is ordinary, and losing it to a blank page would be absurd.
 */
export function OrderConfirmation({
  shopSlug,
  orderNumber,
  fallback,
}: {
  shopSlug: string;
  orderNumber: string;
  /** Passed straight from the checkout response, so the common path needs no storage at all. */
  fallback?: PlacedOrder | null;
}) {
  const storageKey = `order:${shopSlug}:${orderNumber}`;
  const [order, setOrder] = useState<PlacedOrder | null>(fallback ?? null);

  useEffect(() => {
    if (fallback) {
      try {
        sessionStorage.setItem(storageKey, JSON.stringify(fallback));
      } catch {
        // Private browsing or a full quota. The confirmation still renders from `fallback`, so
        // only a reload would be lost — not worth failing the page over.
      }
      return;
    }
    try {
      const stored = sessionStorage.getItem(storageKey);
      if (stored) setOrder(JSON.parse(stored) as PlacedOrder);
    } catch {
      // Unreadable storage: fall through to the state below.
    }
  }, [fallback, storageKey]);

  if (!order) {
    return (
      <main className="mx-auto max-w-3xl px-4 py-24 text-center">
        <h1 className="text-2xl font-bold">Order {orderNumber}</h1>
        <p className="mt-2 text-sm text-muted-foreground">
          We have your order. Sign in to see its full history and track it over time.
        </p>
        <div className="mt-6 flex justify-center gap-4 text-sm">
          <Link href={`/s/${shopSlug}`} className="underline underline-offset-4">
            Back to the shop
          </Link>
          <Link href="/account/orders" className="underline underline-offset-4">
            My orders
          </Link>
        </div>
      </main>
    );
  }

  return (
    <main className="mx-auto max-w-2xl px-4 py-12">
      <div className="rounded-lg border border-primary/30 bg-primary/5 p-6">
        <h1 className="text-2xl font-semibold">Thank you — your order is placed</h1>
        <p className="mt-2 text-sm">
          Order number <strong>{order.orderNumber}</strong>. Keep it for any question about this
          delivery.
        </p>
        {order.paymentInstructions ? (
          <p className="mt-3 text-sm">{order.paymentInstructions}</p>
        ) : null}
      </div>

      <h2 className="mt-8 text-sm font-medium text-muted-foreground">What you ordered</h2>
      <ul className="mt-2 divide-y rounded-lg border">
        {order.lines.map((line) => (
          <li
            key={`${line.variantName}-${line.productName}`}
            className="flex justify-between gap-4 p-4 text-sm"
          >
            <span>
              {line.productName}
              {line.variantName ? ` (${line.variantName})` : ''} × {line.quantity}
            </span>
            <span className="shrink-0">
              Rs. {(line.lineTotalMinor / 100).toLocaleString('en-NP', {
                minimumFractionDigits: 2,
                maximumFractionDigits: 2,
              })}
            </span>
          </li>
        ))}
        <li className="flex justify-between p-4 text-base font-bold">
          <span>Total</span>
          <span>{order.totalDisplay}</span>
        </li>
      </ul>

      <div className="mt-6 flex gap-4 text-sm">
        <Link href={`/s/${shopSlug}`} className="underline underline-offset-4">
          Continue shopping
        </Link>
        <Link href="/account/orders" className="underline underline-offset-4">
          View my orders
        </Link>
      </div>
    </main>
  );
}