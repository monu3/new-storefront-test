'use client';

import Link from 'next/link';
import { useState } from 'react';

import { useCart } from '@store-app/state';

/**
 * The cart, and the first step of checkout.
 *
 * <p>Totals are computed from the cached prices so the page renders instantly, but they are
 * explicitly labelled as an estimate and re-quoted on the checkout screen. The server is the only
 * authority on what something costs (D49: the parts must sum exactly to the total, tax is charged
 * on the post-discount amount, and an inclusive tax rule must not be added twice). Showing a
 * client-side total as though it were final is how a customer reaches checkout and finds a
 * different number, and stops trusting the shop.
 *
 * <p>A cart belongs to one shop. Two shops cannot be one order, so adding from a second shop
 * replaces the basket rather than silently mixing vendors — the cart store enforces that, and this
 * page says so plainly rather than appearing to lose the customer's items.
 */
export function CartView({ shopSlug }: { shopSlug: string }) {
  const { lines, activeShopSlug, setQuantity, remove, clear, subtotalMinor } = useCart();
  const [cleared, setCleared] = useState(false);

  // The cart is persisted, so it can hold another shop's lines. A customer following a link to
  // this shop's cart should see their basket, not an empty page — and should be told plainly if it
  // belongs somewhere else.
  if (lines.length === 0) {
    return (
      <main className="mx-auto max-w-3xl px-4 py-24 text-center">
        <h1 className="text-2xl font-bold">Your cart is empty</h1>
        <p className="mt-2 text-sm text-muted-foreground">
          Nothing here yet. Find something you like and it will show up on this page.
        </p>
        <Link href={`/s/${shopSlug}`} className="mt-6 inline-block text-sm underline underline-offset-4">
          Continue shopping
        </Link>
      </main>
    );
  }

  const shop = activeShopSlug ?? lines[0]?.shopSlug ?? shopSlug;

  return (
    <main className="mx-auto max-w-4xl px-4 py-8">
      <h1 className="text-2xl font-semibold">Your cart</h1>

      {shop !== shopSlug ? (
        <p role="status" className="mt-3 rounded-md border bg-muted/50 px-3 py-2 text-sm">
          You have items from another shop.{' '}
          <Link href={`/s/${shop}/cart`} className="underline underline-offset-4">
            Go to that cart
          </Link>{' '}
          to check out — an order can only be placed with one shop at a time.
        </p>
      ) : null}

      <ul className="mt-6 divide-y rounded-lg border">
        {lines.map((line) => (
          <li key={line.variantId} className="flex items-center gap-4 p-4">
            <div className="size-16 shrink-0 overflow-hidden rounded-md bg-muted">
              {line.imageUrl ? (
                // eslint-disable-next-line @next/next/no-img-element
                <img src={line.imageUrl} alt="" className="h-full w-full object-cover" />
              ) : null}
            </div>

            <div className="min-w-0 flex-1">
              <p className="truncate font-medium">{line.name}</p>
              {line.variantName ? (
                <p className="text-xs text-muted-foreground">{line.variantName}</p>
              ) : null}
              <p className="mt-1 text-sm">{rupees(line.unitPriceMinor)} each</p>
            </div>

            <div className="flex items-center rounded-md border">
              <button
                type="button"
                aria-label={`Decrease quantity of ${line.name}`}
                className="px-3 py-1.5"
                onClick={() => setQuantity(line.variantId, Math.max(1, line.quantity - 1))}
              >
                −
              </button>
              <span className="min-w-8 text-center text-sm">{line.quantity}</span>
              <button
                type="button"
                aria-label={`Increase quantity of ${line.name}`}
                className="px-3 py-1.5"
                onClick={() => setQuantity(line.variantId, line.quantity + 1)}
              >
                +
              </button>
            </div>

            <span className="w-24 text-right text-sm font-semibold">
              {rupees(line.unitPriceMinor * line.quantity)}
            </span>

            <button
              type="button"
              aria-label={`Remove ${line.name}`}
              className="p-2 text-muted-foreground hover:text-foreground"
              onClick={() => remove(line.variantId)}
            >
              ✕
            </button>
          </li>
        ))}
      </ul>

      <div className="mt-6 flex flex-wrap items-center justify-between gap-4">
        <button
          type="button"
          className="text-sm text-muted-foreground underline underline-offset-4"
          onClick={() => {
            clear();
            setCleared(true);
          }}
        >
          Empty the cart
        </button>

        <div className="text-right">
          <p className="text-sm text-muted-foreground">Estimated total</p>
          <p className="text-xl font-bold">{rupees(subtotalMinor())}</p>
          <p className="text-xs text-muted-foreground">
            Delivery and tax are worked out at checkout.
          </p>
        </div>
      </div>

      {cleared ? (
        <p role="status" className="mt-3 text-sm text-muted-foreground">
          Cart emptied.
        </p>
      ) : null}

      <div className="mt-6">
        <Link
          href={`/s/${shop}/checkout`}
          className="inline-flex h-11 items-center rounded-md bg-primary px-6 text-sm font-medium text-primary-foreground"
        >
          Go to checkout
        </Link>
      </div>
    </main>
  );
}

/** Minor units to a rupee string. */
function rupees(minor: number): string {
  return `Rs. ${(minor / 100).toLocaleString('en-NP', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
}