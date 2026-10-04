import { useQuery, useQueryClient } from '@tanstack/react-query';
import type { QueryClient } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { ArrowLeft, Ban, Check, Clock, CreditCard, MapPin, Mail, Phone, Receipt, User } from 'lucide-react';

import { authorizedFetch, postJson } from '@store-app/auth';
import type { AuthAdapter } from '@store-app/auth';
import { Badge, Button, Card, CardContent, Skeleton, toast } from '@store-app/ui';
import { cn } from '@store-app/ui';

/**
 * One order, in full (Phase 4).
 *
 * <p>Replicated from the reference: a header with the order number and its state, order items and
 * customer information on the left, and a summary plus the state-machine actions on the right.
 *
 * <p><b>The action buttons come from the server's state machine, not from a hardcoded list.</b>
 * `GET /orders/{id}/transitions` returns exactly the statuses this order may move to. A button the
 * server will refuse is worse than no button: the owner clicks, gets a 409, and learns nothing
 * they did not already suspect.
 */

interface OrderLine {
  productName: string;
  variantName?: string | null;
  imageUrl?: string | null;
  categoryLabel: string;
  unitPrice: number;
  quantity: number;
  lineTotal: number;
}

interface TimelineEntry {
  status: string;
  note?: string | null;
  at: string;
}

interface OrderDetail {
  id: string;
  orderNumber: string;
  status: string;
  paymentStatus: string;
  paymentMethod: string;
  guestEmail?: string | null;
  guestPhone?: string | null;
  shippingAddress?: string | null;
  subtotal: number;
  discount: number;
  tax: number;
  shipping: number;
  total: number;
  totalDisplay: string;
  cancelReason?: string | null;
  placedAt: string;
  lines: OrderLine[];
  timeline: TimelineEntry[];
}

interface Transitions {
  current: string;
  next: string[];
  cancellable: boolean;
}

export function ShopOrderDetail({ auth }: { auth: AuthAdapter }) {
  const { orderId } = useParams<{ orderId: string }>();
  const queryClient = useQueryClient();

  const { data: order, isLoading } = useQuery({
    queryKey: ['order', orderId],
    queryFn: async () => {
      const response = await authorizedFetch(auth, `/api/v1/shop/orders/${orderId}`);
      if (!response.ok) throw new Error(`Order request failed: ${response.status}`);
      return (await response.json()) as OrderDetail;
    },
    enabled: Boolean(orderId),
  });

  const { data: transitions } = useQuery({
    queryKey: ['order-transitions', orderId],
    queryFn: async () => {
      const response = await authorizedFetch(auth, `/api/v1/shop/orders/${orderId}/transitions`);
      if (!response.ok) return null;
      return (await response.json()) as Transitions;
    },
    enabled: Boolean(orderId),
  });

  if (isLoading || !order) return <OrderSkeleton />;

  const address = parseAddress(order.shippingAddress);
  const lineCount = order.lines.length;

  return (
    <div className="space-y-4">
      {/* ------------------------------------------------------------ header */}
      <Card>
        <CardContent className="flex flex-wrap items-center gap-3 p-5">
          <a
            href="/shop/orders"
            aria-label="Back to orders"
            className="rounded-md p-2 text-muted-foreground hover:bg-muted hover:text-foreground"
          >
            <ArrowLeft className="size-5" aria-hidden />
          </a>
          <span className="h-6 w-px bg-border" aria-hidden />
          <h2 className="text-xl font-semibold">
            Order Details{' '}
            <span className="text-primary">{order.orderNumber}</span>
          </h2>

          <div className="ml-auto flex flex-wrap items-center gap-2">
            <Badge className={cn('px-3 py-1', statusStyles[order.status] ?? 'bg-muted')}>
              <Clock className="mr-1 size-3.5" aria-hidden />
              {order.status}
            </Badge>
            <Badge
              variant="outline"
              className={cn(
                'gap-1.5 px-3 py-1',
                order.paymentStatus === 'PAID' ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : '',
              )}
            >
              <CreditCard className="size-3.5" aria-hidden />
              Payment: {order.paymentStatus}
            </Badge>
            <Button variant="outline" onClick={() => void downloadInvoice(auth, order.id)}>
              <Receipt className="size-4" aria-hidden />
              Receipt
            </Button>
          </div>
        </CardContent>
      </Card>

      <div className="grid gap-4 xl:grid-cols-3">
        {/* ------------------------------------------------------------ left */}
        <div className="space-y-4 xl:col-span-2">
          <Card>
            <CardContent className="p-5">
              <h3 className="mb-4 text-base font-semibold">
                Order Items ({lineCount})
              </h3>
              <div className="space-y-4">
                {order.lines.map((line, index) => (
                  <div key={index} className="flex gap-4">
                    <div className="grid size-20 shrink-0 place-items-center overflow-hidden rounded-lg bg-muted text-xs text-muted-foreground">
                      {line.productName.slice(0, 2).toUpperCase()}
                    </div>
                    <div className="min-w-0 flex-1">
                      <p className="font-medium">{line.productName}</p>
                      <p className="text-xs text-muted-foreground">
                        {line.variantName || line.categoryLabel}
                      </p>
                      <div className="mt-3 flex flex-wrap gap-6 border-t pt-3 text-sm">
                        <span>
                          <span className="text-muted-foreground">Quantity:</span>{' '}
                          <span className="font-medium">{line.quantity}</span>
                        </span>
                        <span>
                          <span className="text-muted-foreground">Unit Price:</span>{' '}
                          <span className="font-medium">{formatPrice(line.unitPrice)}</span>
                        </span>
                        <span className="ml-auto font-semibold">{formatPrice(line.lineTotal)}</span>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
              <div className="mt-4 flex justify-end border-t pt-3 text-base font-semibold">
                Total: {order.totalDisplay}
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardContent className="p-5">
              <h3 className="mb-4 text-base font-semibold">Customer Information</h3>
              <div className="grid gap-4 sm:grid-cols-2">
                <Info icon={<User className="size-4" />} label="Customer" value={address.name || 'Guest'} />
                <Info icon={<Phone className="size-4" />} label="Phone" value={order.guestPhone || '—'} />
                <Info icon={<Mail className="size-4" />} label="Email" value={order.guestEmail || '—'} />
                <Info
                  icon={<MapPin className="size-4" />}
                  label="Address"
                  value={[address.city, address.state, address.country].filter(Boolean).join(', ') || '—'}
                />
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardContent className="p-5">
              <h3 className="mb-4 text-base font-semibold">Timeline</h3>
              <ol className="space-y-3">
                {order.timeline.map((entry, index) => (
                  <li key={index} className="flex items-start gap-3">
                    <span className="mt-0.5 grid size-6 shrink-0 place-items-center rounded-full bg-emerald-100 text-emerald-700">
                      <Check className="size-3.5" aria-hidden />
                    </span>
                    <div>
                      <p className="text-sm font-medium">{entry.status}</p>
                      <p className="text-xs text-muted-foreground">{formatDateTime(entry.at)}</p>
                      {entry.note ? (
                        <p className="mt-0.5 text-xs text-muted-foreground">{entry.note}</p>
                      ) : null}
                    </div>
                  </li>
                ))}
              </ol>
            </CardContent>
          </Card>
        </div>

        {/* ------------------------------------------------------------ right */}
        <div className="space-y-4">
          <Card>
            <CardContent className="p-5">
              <h3 className="mb-4 text-base font-semibold">Order Summary</h3>
              <dl className="space-y-3 text-sm">
                <Row label="Order Date" value={formatDate(order.placedAt)} />
                <Row label="Payment Method" value={order.paymentMethod.replace('_', ' ')} />
                <Row label="Status" value={order.paymentStatus} />
              </dl>
              <dl className="mt-5 space-y-3 border-t pt-4 text-sm">
                <Row label="Sub Total" value={formatPrice(order.subtotal)} />
                {order.discount > 0 ? (
                  <Row label="Discount" value={`− ${formatPrice(order.discount)}`} />
                ) : null}
                {order.tax > 0 ? <Row label="Tax" value={formatPrice(order.tax)} /> : null}
                <Row label="Delivery Fee" value={formatPrice(order.shipping)} />
              </dl>
              <div className="mt-4 flex justify-between border-t pt-4 font-semibold">
                <span>Total</span>
                <span>{order.totalDisplay}</span>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardContent className="space-y-3 p-5">
              <h3 className="text-base font-semibold">Actions</h3>
              {transitions?.next?.length ? (
                transitions.next.map((status) => (
                  <Button
                    key={status}
                    className={cn('w-full', status === 'CANCELLED' && 'border-0')}
                    variant={status === 'CANCELLED' ? 'destructive' : 'default'}
                    style={status === 'CANCELLED' ? { background: '#fee2e2', color: '#dc2626' } : undefined}
                    onClick={() => void transition(auth, order.id, status, queryClient)}
                  >
                    {status === 'CANCELLED' ? (
                      <Ban className="mr-2 size-4" aria-hidden />
                    ) : (
                      <Check className="mr-2 size-4" aria-hidden />
                    )}
                    {transitionLabel(status)}
                  </Button>
                ))
              ) : (
                <p className="text-sm text-muted-foreground">
                  This order has reached a final state. No further actions are available.
                </p>
              )}
              {order.cancelReason ? (
                <p className="rounded-md bg-muted px-3 py-2 text-xs text-muted-foreground">
                  Cancelled: {order.cancelReason}
                </p>
              ) : null}
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------- pieces

function Info({
  icon,
  label,
  value,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
}) {
  return (
    <div className="flex items-center gap-3">
      <span className="grid size-9 shrink-0 place-items-center rounded-full bg-muted text-muted-foreground">
        {icon}
      </span>
      <div className="min-w-0">
        <p className="truncate text-sm font-medium">{value}</p>
        <p className="text-xs text-muted-foreground">{label}</p>
      </div>
    </div>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="text-right font-medium">{value}</dd>
    </div>
  );
}

function OrderSkeleton() {
  return (
    <div className="space-y-4">
      <Skeleton className="h-20 rounded-xl" />
      <div className="grid gap-4 xl:grid-cols-3">
        <Skeleton className="h-80 rounded-xl xl:col-span-2" />
        <Skeleton className="h-80 rounded-xl" />
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------- helpers

const statusStyles: Record<string, string> = {
  PENDING: 'bg-amber-50 text-amber-700',
  ACCEPTED: 'bg-orange-50 text-orange-700',
  PROCESSING: 'bg-sky-50 text-sky-700',
  SHIPPED: 'bg-indigo-50 text-indigo-700',
  DELIVERED: 'bg-emerald-50 text-emerald-700',
  CANCELLED: 'bg-red-50 text-red-700',
};

function transitionLabel(status: string): string {
  return {
    ACCEPTED: 'Accept Order',
    PROCESSING: 'Start Packing',
    SHIPPED: 'Mark Shipped',
    DELIVERED: 'Mark Delivered',
    CANCELLED: 'Cancel Order',
  }[status] ?? status;
}

/** Posts the transition and refreshes both this order and the board. */
async function transition(
  auth: AuthAdapter,
  orderId: string,
  status: string,
  queryClient: QueryClient,
) {
  const action = status.toLowerCase();
  const response = await postJson(auth, `/api/v1/shop/orders/${orderId}/${action}`, {});
  if (response.ok) {
    await queryClient.invalidateQueries({ queryKey: ['order', orderId] });
    await queryClient.invalidateQueries({ queryKey: ['order-transitions', orderId] });
    await queryClient.invalidateQueries({ queryKey: ['orders'] });
    await queryClient.invalidateQueries({ queryKey: ['shop-dashboard'] });
  }
}

/**
 * The address snapshot.
 *
 * <p>Parsed rather than shown raw, and never throws: the snapshot is frozen jsonb written at order
 * time, and a shape this UI does not recognise should cost the owner the address block, not the
 * whole order page.
 */
function parseAddress(raw?: string | null): Record<string, string> {
  if (!raw) return {};
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    return Object.fromEntries(
      Object.entries(parsed).map(([k, v]) => [k, v == null ? '' : String(v)]),
    );
  } catch {
    return {};
  }
}

function formatPrice(minor: number): string {
  return `NRP ${(minor / 100).toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('en-US', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  });
}

function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString('en-US', {
    day: 'numeric',
    month: 'short',
    hour: 'numeric',
    minute: '2-digit',
  });
}

/**
 * Downloads the order's invoice PDF.
 *
 * <p>Fetched with the Bearer token and saved through an object URL, not navigated to. A browser
 * cannot attach an `Authorization` header to a navigation, so `window.open('/invoices/{id}.pdf')`
 * would land on a 401 and the owner would see a JSON error where their invoice should be.
 */
async function downloadInvoice(auth: AuthAdapter, orderId: string): Promise<void> {
  const response = await authorizedFetch(auth, `/api/v1/shop/invoices/${orderId}.pdf`);
  if (!response.ok) {
    // A toast rather than alert(): a native dialog blocks the whole page and cannot be styled or
    // tested for. "The invoice could not be generated" is also the honest message — an alert with
    // "HTTP 500" would tell an owner nothing they can act on.
    toast({
      title: 'The invoice could not be generated',
      description: `The server returned HTTP ${response.status}. Try again, or download it from the order on a different browser.`,
      variant: 'destructive',
    });
    return;
  }

  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `invoice-${orderId}.pdf`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
