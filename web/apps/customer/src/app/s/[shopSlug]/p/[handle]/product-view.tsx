'use client';

import Link from 'next/link';
import { useState } from 'react';

import { useCart } from '@store-app/state';

import { rupees } from '@/lib/storefront';
import type { ProductDetail } from '@/lib/storefront';

/**
 * A single product, and the page where a customer actually decides to buy.
 *
 * <p>The add-to-cart button is a client island inside a server-rendered page: the product, price and
 * variants arrive as HTML (D1, so the page is indexable and a WhatsApp link previews it), and only
 * the quantity picker and the button need interactivity.
 *
 * <p>Cart state lives in Zustand, persisted to `localStorage`. Nothing is trusted: the price shown
 * here is a cached convenience and checkout re-quotes server-side, because a price held in a
 * browser is a price anybody can edit.
 */
export function ProductView({ product }: { product: ProductDetail }) {
  const shopSlug = product.shopSlug;

  // D38: a product always has at least one variant, and the variant is what carries price and
  // stock. Defaulting to the first *active* one means the page never renders unbuyable.
  const buyable = product.variants.filter((v) => v.active);
  const [variantId, setVariantId] = useState<string>(buyable[0]?.id ?? '');
  const [quantity, setQuantity] = useState(1);
  const [added, setAdded] = useState(false);

  const selected = product.variants.find((v) => v.id === variantId);
  const canBuy = Boolean(selected);

  function addToCart() {
    if (!selected) return;
    useCart.getState().add(
      {
        variantId: selected.id,
        productId: product.id,
        shopSlug,
        name: product.name,
        variantName: selected.name,
        imageUrl: selected.imageUrl ?? product.imageUrl ?? undefined,
        unitPriceMinor: selected.price,
      },
      quantity,
    );
    setAdded(true);
    // Cleared rather than left up: a button that permanently reads "Added" stops answering the
    // question it exists to answer, which is "did my click land?".
    setTimeout(() => setAdded(false), 2500);
  }

  const hero = product.imageUrl ?? product.images[0];

  return (
    <main className="mx-auto max-w-6xl px-4 py-8">
      <nav aria-label="Breadcrumb" className="mb-6 text-sm text-muted-foreground">
        <Link href={`/s/${shopSlug}`} className="hover:text-foreground">
          {product.shopName}
        </Link>
        {product.categoryLabel ? (
          <>
            <span className="mx-2">/</span>
            <Link
              href={`/s/${shopSlug}/c/${product.category.toLowerCase()}`}
              className="hover:text-foreground"
            >
              {product.categoryLabel}
            </Link>
          </>
        ) : null}
      </nav>

      <div className="grid gap-8 md:grid-cols-2">
        <div className="overflow-hidden rounded-lg border bg-muted">
          {hero ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={hero} alt={product.name} className="aspect-square w-full object-cover" />
          ) : (
            <div className="flex aspect-square items-center justify-center text-sm text-muted-foreground">
              No photo yet
            </div>
          )}
        </div>

        <div>
          <h1 className="text-2xl font-semibold">{product.name}</h1>
          {product.shortDescription ? (
            <p className="mt-2 text-sm text-muted-foreground">{product.shortDescription}</p>
          ) : null}

          <div className="mt-4 flex items-baseline gap-3">
            <span className="text-2xl font-bold">{rupees(selected?.price ?? product.price)}</span>
            {selected?.compareAt ? (
              <span className="text-sm text-muted-foreground line-through">
                {rupees(selected.compareAt)}
              </span>
            ) : null}
          </div>

          {product.description ? (
            <p className="mt-4 whitespace-pre-line text-sm leading-relaxed text-muted-foreground">
              {product.description}
            </p>
          ) : null}

          {buyable.length > 1 ? (
            <fieldset className="mt-6">
              <legend className="text-sm font-medium">Choose an option</legend>
              <div className="mt-2 flex flex-wrap gap-2">
                {buyable.map((v) => (
                  <label
                    key={v.id}
                    className={`cursor-pointer rounded-md border px-3 py-2 text-sm ${
                      v.id === variantId ? 'border-primary bg-primary/5' : ''
                    }`}
                  >
                    <input
                      type="radio"
                      name="variant"
                      value={v.id}
                      checked={v.id === variantId}
                      onChange={() => setVariantId(v.id)}
                      className="sr-only"
                    />
                    {v.name}
                  </label>
                ))}
              </div>
            </fieldset>
          ) : null}

          <div className="mt-6 flex flex-wrap items-center gap-3">
            <div className="flex items-center rounded-md border">
              <button
                type="button"
                aria-label="Decrease quantity"
                className="px-3 py-2"
                onClick={() => setQuantity((q) => Math.max(1, q - 1))}
              >
                −
              </button>
              <span className="min-w-8 text-center text-sm">{quantity}</span>
              <button
                type="button"
                aria-label="Increase quantity"
                className="px-3 py-2"
                onClick={() => setQuantity((q) => q + 1)}
              >
                +
              </button>
            </div>

            <button
              type="button"
              onClick={addToCart}
              disabled={!canBuy}
              className="inline-flex h-11 items-center rounded-md bg-primary px-6 text-sm font-medium text-primary-foreground disabled:opacity-50"
            >
              {added ? 'Added to cart' : 'Add to cart'}
            </button>

            <Link href={`/s/${shopSlug}/cart`} className="text-sm underline underline-offset-4">
              Go to cart
            </Link>
          </div>

          {added ? (
            <p role="status" className="mt-3 text-sm text-muted-foreground">
              Added.{' '}
              <Link
                href={`/s/${shopSlug}/cart`}
                className="underline underline-offset-4"
              >
                View cart and check out
              </Link>
              .
            </p>
          ) : null}
        </div>
      </div>

      {product.related.length > 0 ? (
        <section className="mt-12">
          <h2 className="mb-4 text-lg font-semibold">You might also like</h2>
          <ul className="grid gap-4 sm:grid-cols-3 lg:grid-cols-4">
            {product.related.slice(0, 4).map((p) => (
              <li key={p.id}>
                <Link
                  href={`/s/${shopSlug}/p/${p.slug}`}
                  className="block rounded-lg border p-3 transition-colors hover:bg-muted"
                >
                  <p className="truncate text-sm font-medium">{p.name}</p>
                  <p className="mt-1 text-sm">{rupees(p.price)}</p>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </main>
  );
}
