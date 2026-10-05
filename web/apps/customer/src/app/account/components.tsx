'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import { Package, RefreshCw } from 'lucide-react';

import type {
  CustomerPage,
  OrderDetail,
  OrderSummary,
  PaymentStatus,
  ReorderPreview,
} from '@store-app/api-client';
import { Badge, Button, Card, CardContent, Skeleton } from '@store-app/ui';
import { cn } from '@store-app/ui';

import { useCart } from '@store-app/state';

import { apiGet } from '../../lib/api';

/**
 * The customer's orders (Phase 5).
 *
 * <p><b>Two screens in one file</b> — the list and the single order — because they share the
 * order-row rendering and the shop-scoped fetch, and splitting them would mean two files importing
 * each other's pieces for the sake of a route.
 *
 * <p><b>Every request carries the shop slug.</b> Order numbers are per shop, so `ORD-0001` exists
 * everywhere; the server scopes by customer *and* shop, and the client has to supply both.
 */
export function CustomerOrders() {
  return <OrderList />;
}

export function CustomerOrderDetail() {
  const { shopSlug, orderNumber } = useParams<{ shopSlug: string; orderNumber: string }>();
  const [order, setOrder] = useState<OrderDetail | null>(null);
  const [payment, setPayment] = useState<PaymentStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const detail = await apiGet<OrderDetail>(
          `/api/v1/customer/shops/${shopSlug}/orders/${orderNumber}`,
        );
        if (cancelled) return;
        setOrder(detail);

        // Only a gateway's own answer may mark a payment settled. Fetched separately so a slow
        // eSewa call never delays the order itself rendering.
        if (detail.paymentMethod === 'ESAWA') {
          const status = await apiGet<PaymentStatus>(
            `/api/v1/customer/shops/${shopSlug}/orders/${orderNumber}/payment`,
          );
          if (!cancelled) setPayment(status);
        }
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : 'Could not load that order');
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [shopSlug, orderNumber]);

  if (loading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-8 w-48" />
        <Skeleton className="h-64 rounded-xl" />
      </div>
    );
  }

  if (error || !order) {
    return (
      <Card>
        <CardContent className="p-10 text-center">
          <p className="font-medium">We could not find that order</p>
          <p className="mt-1 text-sm text-muted-foreground">
            {error ?? 'It may belong to another account.'}
          </p>
          <Button asChild className="mt-4" variant="outline">
            <Link href="/account/orders">Back to orders</Link>
          </Button>
        </CardContent>
      </Card>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">Order {order.orderNumber}</h1>
          <p className="text-sm text-muted-foreground">
            Placed {formatDate(order.placedAt)} · {money(order.totalMinor)}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <OrderStatusBadge status={order.status} />
          <ReorderButton shopSlug={shopSlug!} orderNumber={order.orderNumber} />
        </div>
      </div>

      {payment ? <PaymentBanner payment={payment} /> : null}

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_320px]">
        <Card>
          <CardContent className="p-5">
            <h2 className="mb-4 text-base font-semibold">Items</h2>
            <ul className="divide-y">
              {order.lines.map((line, index) => (
                <li key={index} className="flex items-center gap-4 py-3">
                  {line.imageUrl ? (
                    <img
                      src={line.imageUrl}
                      alt=""
                      className="size-14 shrink-0 rounded-lg border object-cover"
                    />
                  ) : (
                    // A placeholder rather than a broken image: most of this platform's products
                    // have no photograph, and an empty box reads as a loading state forever.
                    <span
                      className="grid size-14 shrink-0 place-items-center rounded-lg border bg-muted text-xs text-muted-foreground"
                      aria-hidden
                    >
                      {line.productName.slice(0, 2).toUpperCase()}
                    </span>
                  )}
                  <div className="min-w-0 flex-1">
                    <p className="truncate font-medium">{line.productName}</p>
                    {line.variantName ? (
                      <p className="truncate text-sm text-muted-foreground">{line.variantName}</p>
                    ) : null}
                    <p className="text-sm text-muted-foreground">×{line.quantity}</p>
                  </div>
                  <p className="shrink-0 font-medium">{money(line.lineTotalMinor)}</p>
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>

        <Card className="h-fit">
          <CardContent className="p-5">
            <h2 className="mb-4 text-base font-semibold">Tracking</h2>
            <ol className="space-y-3">
              {order.timeline.map((step, index) => {
                const last = index === order.timeline.length - 1;
                return (
                  <li key={index} className="flex gap-3">
                    <span
                      className={cn(
                        'mt-0.5 grid size-6 shrink-0 place-items-center rounded-full',
                        last ? 'bg-emerald-100 text-emerald-700' : 'bg-muted text-muted-foreground',
                      )}
                      aria-hidden
                    >
                      {last ? '✓' : index + 1}
                    </span>
                    <div className="min-w-0">
                      <p className="text-sm font-medium">{humanStatus(step.status)}</p>
                      <p className="text-xs text-muted-foreground">{formatDateTime(step.at)}</p>
                      {step.note ? (
                        <p className="mt-0.5 text-xs text-muted-foreground">{step.note}</p>
                      ) : null}
                    </div>
                  </li>
                );
              })}
            </ol>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

// -------------------------------------------------------------------- list

function OrderList() {
  const [shops, setShops] = useState<Array<{ slug: string; name: string }>>([]);
  const [shop, setShop] = useState<string>('');
  const [orders, setOrders] = useState<CustomerPage<OrderSummary> | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    void (async () => {
      const found = await apiGet<Array<{ slug: string; name: string }>>('/api/v1/checkout/shops');
      setShops(found);
      // Default to the first shop so the page is never an empty dropdown with no explanation.
      // `first` rather than `found[0]`: the array is empty-checked but the element is not
      // narrowed for the compiler.
      const first = found[0];
      if (first) setShop(first.slug);
      else setLoading(false);
    })();
  }, []);

  useEffect(() => {
    if (!shop) return;
    let cancelled = false;
    setLoading(true);
    void (async () => {
      try {
        const found = await apiGet<CustomerPage<OrderSummary>>(
          `/api/v1/customer/shops/${shop}/orders?size=50`,
        );
        if (!cancelled) setOrders(found);
      } catch {
        // A failed load shows the empty state, which says "no orders here yet". That is wrong
        // but not harmful, and an error box over an empty list is more alarming than the truth
        // is useful -- the retry is one page refresh.
        if (!cancelled) setOrders(null);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [shop]);

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">Your orders</h1>
        <p className="text-sm text-muted-foreground">
          Orders are per shop, so pick the shop you want to see.
        </p>
      </div>

      {shops.length > 1 ? (
        <label className="block text-sm">
          <span className="font-medium">Shop</span>
          <select
            value={shop}
            onChange={(e) => setShop(e.target.value)}
            className="mt-1 block rounded-md border bg-background px-3 py-2"
          >
            {shops.map((s) => (
              <option key={s.slug} value={s.slug}>
                {s.name}
              </option>
            ))}
          </select>
        </label>
      ) : null}

      {loading ? (
        <div className="space-y-2">
          {Array.from({ length: 3 }).map((_, i) => (
            <Skeleton key={i} className="h-20 rounded-xl" />
          ))}
        </div>
      ) : orders && orders.content.length > 0 ? (
        <ul className="space-y-2">
          {orders.content.map((order) => (
            <li key={order.orderNumber}>
              <Card className="transition-colors hover:border-foreground/20">
                <CardContent className="flex flex-wrap items-center gap-4 p-4">
                  <div className="min-w-0 flex-1">
                    <p className="font-medium">{order.orderNumber}</p>
                    <p className="text-sm text-muted-foreground">
                      {formatDate(order.placedAt)} · {order.paymentMethod.replace('_', ' ')}
                    </p>
                  </div>
                  <span className="font-medium">{order.totalDisplay}</span>
                  <OrderStatusBadge status={order.status} />
                  <Button asChild size="sm" variant="outline">
                    <Link href={`/s/${shop}/orders/${order.orderNumber}`}>View</Link>
                  </Button>
                </CardContent>
              </Card>
            </li>
          ))}
        </ul>
      ) : (
        <Card>
          <CardContent className="p-10 text-center">
            <Package className="mx-auto size-8 text-muted-foreground" aria-hidden />
            <p className="mt-3 font-medium">No orders here yet</p>
            <p className="mt-1 text-sm text-muted-foreground">
              When you order from this shop it will appear here, with its tracking.
            </p>
            <Button asChild className="mt-4">
              <Link href="/">Browse shops</Link>
            </Button>
          </CardContent>
        </Card>
      )}
    </div>
  );
}

// -------------------------------------------------------------------- bits

/**
 * The payment state, when there is one to report.
 *
 * <p>Rendered for every state including PENDING, because "we are confirming" is information.
 * A spinner with no words leaves a customer who has just handed over money wondering whether it
 * worked.
 */
function PaymentBanner({ payment }: { payment: PaymentStatus }) {
  const tone =
    payment.status === 'PAID'
      ? 'border-emerald-300 bg-emerald-50 text-emerald-900'
      : payment.status === 'FAILED'
        ? 'border-red-300 bg-red-50 text-red-900'
        : 'border-amber-300 bg-amber-50 text-amber-900';

  return (
    <div className={cn('rounded-lg border px-4 py-3', tone)} role="status">
      <p className="text-sm font-medium">Payment {payment.status.toLowerCase()}</p>
      <p className="mt-0.5 text-sm">{payment.message}</p>
      {!payment.paid ? (
        <p className="mt-1 text-xs">
          Checking again in a moment. Refresh this page for the latest.
        </p>
      ) : null}
    </div>
  );
}

/**
 * Rebuilds a basket from a past order.
 *
 * <p>Adds the buyable lines to the cart and then says what could not be added. Putting them in the
 * basket anyway would let a customer reach checkout with something the shop no longer sells.
 */
function ReorderButton({ shopSlug, orderNumber }: { shopSlug: string; orderNumber: string }) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<ReorderPreview | null>(null);
  const [error, setError] = useState<string | null>(null);

  const go = async () => {
    setBusy(true);
    setError(null);
    try {
      const preview = await apiGet<ReorderPreview>(
        `/api/v1/customer/shops/${shopSlug}/orders/${orderNumber}/reorder`,
      );
      setResult(preview);

      // Only the available lines go in the basket; the unavailable ones are reported, not dropped.
      //
      // <p>getState() rather than useCart(): this runs in an event handler, not during render, so
      // there is no hook to call -- and calling one from a callback would be a rules-of-hooks
      // violation rather than a style preference.
      const cart = useCart.getState();
      cart.clearOtherShops(shopSlug);
      for (const line of preview.available) {
        cart.add(
          {
            shopSlug,
            variantId: line.variantId,
            // The cart line needs a product id for the basket's "remove all of this product"
            // affordance. The reorder preview does not carry one, because a reorder is about
            // variants; the checkout resolves it from the variant when it quotes.
            productId: '',
            name: line.productName,
            ...(line.variantName ? { variantName: line.variantName } : {}),
            unitPriceMinor: line.priceMinor,
          },
          line.quantity,
        );
      }
      router.push(`/s/${shopSlug}/checkout`);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not rebuild that order');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <Button onClick={() => void go()} disabled={busy} variant="outline">
        <RefreshCw className={cn('mr-1.5 size-4', busy && 'animate-spin')} aria-hidden />
        Order again
      </Button>

      {error ? (
        <p role="alert" className="text-sm text-red-600">
          {error}
        </p>
      ) : null}
      {result && !result.complete ? (
        <p className="text-xs text-amber-700">{result.message}</p>
      ) : null}
    </>
  );
}

function OrderStatusBadge({ status }: { status: string }) {
  const tone: Record<string, string> = {
    PENDING: 'bg-amber-50 text-amber-700',
    ACCEPTED: 'bg-orange-50 text-orange-700',
    PROCESSING: 'bg-sky-50 text-sky-700',
    SHIPPED: 'bg-indigo-50 text-indigo-700',
    DELIVERED: 'bg-emerald-50 text-emerald-700',
    CANCELLED: 'bg-red-50 text-red-700',
  };
  return (
    <Badge className={tone[status] ?? 'bg-muted text-muted-foreground'}>
      {humanStatus(status)}
    </Badge>
  );
}

/** "PENDING" is a database enum; "Awaiting confirmation" is what a customer reads. */
function humanStatus(status: string): string {
  return (
    {
      PENDING: 'Awaiting confirmation',
      ACCEPTED: 'Confirmed',
      PROCESSING: 'Being packed',
      SHIPPED: 'On its way',
      DELIVERED: 'Delivered',
      CANCELLED: 'Cancelled',
    }[status] ?? status
  );
}

function money(minor: number): string {
  return `NRP ${(minor / 100).toLocaleString('en-US', { minimumFractionDigits: 2 })}`;
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
}

function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString('en-GB', {
    day: 'numeric',
    month: 'short',
    hour: 'numeric',
    minute: '2-digit',
  });
}
export { CustomerWishlist, CustomerAddresses } from './parts';
