'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';

import { useCart } from '@store-app/state';

export interface QuoteLine {
  productName: string;
  variantName?: string | null;
  quantity: number;
  lineTotalMinor: number;
  purchasable?: boolean;
  availableStock?: number;
}

export interface Quote {
  lines: QuoteLine[];
  subtotal: number;
  discount: number;
  tax: number;
  shipping: number;
  total: number;
  totalDisplay: string;
  discountMessage?: string | null;
  success?: boolean;
}

export interface PaymentMethod {
  code: 'ESAWA' | 'COD' | 'BANK_TRANSFER';
  name: string;
  description?: string | null;
}

/** Minor units to a rupee string. Every amount on this screen goes through here. */
export function rupees(minor: number): string {
  return `Rs. ${(minor / 100).toLocaleString('en-NP', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
}

/**
 * Checkout: collect details, price the basket on the server, then place the order.
 *
 * <p><b>The server prices everything.</b> The cart's numbers are a cached convenience; this screen
 * asks {@code /checkout/quote} for the real figure as soon as it loads, and again whenever the
 * basket or the discount code changes. What the customer confirms here is therefore the number the
 * server will charge — not an estimate that changes at the last step, which is the commonest reason
 * people abandon a checkout.
 *
 * <p>An invalid discount code never blocks the purchase: the quote still succeeds and carries a
 * message, so a typo costs the discount and nothing else (D49).
 */
export function CheckoutView({ shopSlug }: { shopSlug: string }) {
  const router = useRouter();
  const cart = useCart();
  const [quote, setQuote] = useState<Quote | null>(null);
  const [methods, setMethods] = useState<PaymentMethod[]>([]);
  const [method, setMethod] = useState<'COD' | 'ESAWA' | 'BANK_TRANSFER'>('COD');
  const [discountCode, setDiscountCode] = useState('');
  const [email, setEmail] = useState('');
  const [phone, setPhone] = useState('');
  const [form, setForm] = useState({ line1: '', city: '', state: '', deliveryInstructions: '' });
  const [placing, setPlacing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const shop = cart.activeShopSlug ?? shopSlug;
  const lines = cart.lines.filter((l) => l.shopSlug === shop);

  useEffect(() => {
    void fetch(`/api/v1/checkout/payment-methods?shopSlug=${encodeURIComponent(shopSlug)}`)
      .then((r) => (r.ok ? r.json() : []))
      .then((list: PaymentMethod[]) => {
        setMethods(list);
        // Prefer cash on delivery: no third-party account needed, which matters when much of the
        // traffic is a phone on a connection that may drop mid-payment.
        if (list.some((m) => m.code === 'COD')) setMethod('COD');
        else if (list[0]) setMethod(list[0].code);
      })
      .catch(() => setMethods([]));
  }, [shopSlug]);

  // Re-quote whenever the basket or the discount code changes.
  useEffect(() => {
    if (lines.length === 0) return;
    void fetch('/api/v1/checkout/quote', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        shopSlug: shop,
        lines: lines.map((l) => ({ variantId: l.variantId, quantity: l.quantity })),
        ...(discountCode.trim() ? { discountCode: discountCode.trim() } : {}),
      }),
    })
      .then(async (r) => {
        if (!r.ok) {
          const body = await r.json().catch(() => ({}));
          throw new Error(body.detail ?? 'Could not price this order');
        }
        return r.json() as Promise<Quote>;
      })
      .then(setQuote)
      .catch((e: Error) => setError(e.message));
  }, [shop, lines, discountCode]);

  if (lines.length === 0) {
    return (
      <main className="mx-auto max-w-3xl px-4 py-24 text-center">
        <h1 className="text-2xl font-bold">Nothing to check out</h1>
        <p className="mt-2 text-sm text-muted-foreground">Your cart is empty.</p>
        <Link href={`/s/${shopSlug}`} className="mt-6 inline-block text-sm underline">
          Continue shopping
        </Link>
      </main>
    );
  }

  async function placeOrder() {
    setPlacing(true);
    setError(null);
    try {
      const response = await fetch('/api/v1/checkout/orders', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          shopSlug: shop,
          lines: lines.map((l) => ({ variantId: l.variantId, quantity: l.quantity })),
          email: email.trim() || undefined,
          phone: phone.trim() || undefined,
          shippingAddress: {
            line1: form.line1,
            city: form.city,
            state: form.state,
            deliveryInstructions: form.deliveryInstructions || undefined,
            complete: Boolean(form.line1 && form.city),
          },
          city: form.city || undefined,
          discountCode: discountCode.trim() || undefined,
          paymentMethod: method,
        }),
      });

      const body = (await response.json().catch(() => ({}))) as {
        orderNumber?: string;
        order?: { orderNumber?: string };
        detail?: string;
      };
      if (!response.ok) {
        throw new Error(body.detail ?? `The server rejected this (HTTP ${response.status})`);
      }

      // Only now is the basket finished. Clearing earlier would lose it if the order failed, and
      // the customer would rebuild it by hand to retry.
      cart.clear();

      // Handed to the confirmation page rather than fetched again: the response already carries the
      // whole order, and a guest has no authenticated endpoint to re-read it from.
      const placed = body as {
        orderNumber?: string;
        totalDisplay?: string;
        paymentInstructions?: string | null;
        lines?: Array<{
          productName: string;
          variantName?: string | null;
          quantity: number;
          lineTotalMinor: number;
        }>;
      };
      try {
        sessionStorage.setItem(
          `order:${shop}:${body.orderNumber}`,
          JSON.stringify({ ...placed, status: 'PENDING', paymentMethod: method }),
        );
      } catch {
        // Storage unavailable; the confirmation page still receives it through the URL.
      }
      router.push(`/s/${shop}/orders/${body.orderNumber}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not place the order');
      setPlacing(false);
    }
  }

  const canPlace = Boolean(quote && form.line1 && form.city) && !placing;

  return (
    <main className="mx-auto max-w-5xl px-4 py-8">
      <h1 className="text-2xl font-semibold">Checkout</h1>

      {error ? (
        <p role="alert" className="mt-4 rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm">
          {error}
        </p>
      ) : null}

      <div className="mt-6 grid gap-8 md:grid-cols-[1fr_320px]">
        <div className="space-y-6">
          <fieldset className="rounded-lg border p-4">
            <legend className="px-1 text-sm font-medium">Where should we deliver?</legend>
            <div className="grid gap-3">
              <Field id="line1" label="Street / tole" value={form.line1} onChange={(v) => setForm({ ...form, line1: v })} />
              <div className="grid gap-3 sm:grid-cols-2">
                <Field id="city" label="City" value={form.city} onChange={(v) => setForm({ ...form, city: v })} />
                <Field id="state" label="District" value={form.state} onChange={(v) => setForm({ ...form, state: v })} />
              </div>
              <Field
                id="instructions"
                label="Delivery notes (optional)"
                value={form.deliveryInstructions}
                onChange={(v) => setForm({ ...form, deliveryInstructions: v })}
              />
            </div>
          </fieldset>

          <fieldset className="rounded-lg border p-4">
            <legend className="px-1 text-sm font-medium">How will you pay?</legend>
            <div className="space-y-2">
              {methods.map((m) => (
                <label key={m.code} className="flex cursor-pointer items-start gap-3 rounded-md border p-3">
                  <input
                    type="radio"
                    name="payment"
                    value={m.code}
                    checked={method === m.code}
                    onChange={() => setMethod(m.code)}
                    className="mt-1"
                  />
                  <span>
                    <span className="block text-sm font-medium">{m.name}</span>
                    {m.description ? (
                      <span className="block text-xs text-muted-foreground">{m.description}</span>
                    ) : null}
                  </span>
                </label>
              ))}
              {methods.length === 0 ? (
                <p className="text-sm text-muted-foreground">This shop has not set up payment yet.</p>
              ) : null}
            </div>
          </fieldset>

          <fieldset className="rounded-lg border p-4">
            <legend className="px-1 text-sm font-medium">Contact (optional)</legend>
            <p className="mb-2 text-xs text-muted-foreground">
              Only needed for eSewa or bank transfer confirmations.
            </p>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field id="email" label="Email" type="email" value={email} onChange={setEmail} />
              <Field id="phone" label="Phone" value={phone} onChange={setPhone} />
            </div>
          </fieldset>
        </div>
<aside className="h-fit rounded-lg border p-4">
          <h2 className="text-sm font-medium">Order summary</h2>

          <ul className="mt-3 space-y-2 text-sm">
            {lines.map((l) => (
              <li key={l.variantId} className="flex justify-between gap-2">
                <span className="min-w-0 truncate">
                  {l.name}
                  {l.variantName ? ` (${l.variantName})` : ''} × {l.quantity}
                </span>
                <span className="shrink-0">{rupees(l.unitPriceMinor * l.quantity)}</span>
              </li>
            ))}
          </ul>

          <input
            aria-label="Discount code"
            placeholder="Discount code"
            value={discountCode}
            onChange={(e) => setDiscountCode(e.target.value)}
            className="mt-3 w-full rounded-md border px-2 py-1.5 text-sm"
          />
          {quote?.discountMessage ? (
            <p className="mt-1 text-xs text-muted-foreground">{quote.discountMessage}</p>
          ) : null}

          {quote ? (
            <dl className="mt-4 space-y-1 border-t pt-3 text-sm">
              <Row label="Subtotal" value={rupees(quote.subtotal)} />
              {quote.discount > 0 ? <Row label="Discount" value={`− ${rupees(quote.discount)}`} /> : null}
              {quote.tax > 0 ? <Row label="Tax" value={rupees(quote.tax)} /> : null}
              {quote.shipping > 0 ? <Row label="Delivery" value={rupees(quote.shipping)} /> : null}
              <div className="flex justify-between border-t pt-2 text-base font-bold">
                <dt>Total</dt>
                <dd>{quote.totalDisplay}</dd>
              </div>
            </dl>
          ) : (
            <p className="mt-4 text-sm text-muted-foreground">Pricing your order…</p>
          )}

          <button
            type="button"
            onClick={placeOrder}
            disabled={!canPlace}
            className="mt-4 inline-flex h-11 w-full items-center justify-center rounded-md bg-primary text-sm font-medium text-primary-foreground disabled:opacity-50"
          >
            {placing ? 'Placing order…' : 'Place order'}
          </button>

          {!form.line1 || !form.city ? (
            <p className="mt-2 text-xs text-muted-foreground">
              Enter a street and city to place your order.
            </p>
          ) : null}
        </aside>
      </div>
    </main>
  );
}



function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between">
      <dt className="text-muted-foreground">{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

function Field({
  id,
  label,
  value,
  onChange,
  type = 'text',
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: string;
}) {
  return (
    <div>
      {/* A real <label for> against an input that is genuinely labelable. */}
      <label htmlFor={id} className="block text-sm">
        {label}
      </label>
      <input
        id={id}
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="mt-1 w-full rounded-md border px-3 py-2 text-sm"
      />
    </div>
  );
}
