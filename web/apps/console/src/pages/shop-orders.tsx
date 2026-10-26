import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import {
  CheckCircle2,
  Clock,
  Download,
  Eye,
  Package,
  Search,
  SlidersHorizontal,
  Truck,
  XCircle,
} from 'lucide-react';

import { authorizedFetch, postJson } from '@store-app/auth';
import type { AuthAdapter } from '@store-app/auth';
import {
  Badge,
  Button,
  Checkbox,
  DataTable,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
  Input,
  toast,
} from '@store-app/ui';
import type { BulkTransitionResult } from '@store-app/api-client';
import { cn } from '@store-app/ui';

/**
 * The orders board (Phase 4).
 *
 * <p>Replicated from the reference: status tabs carrying counts, a search box, Export / Sort by /
 * Lifetime, and the order table. <b>Pagination is added</b> — the reference lists every order,
 * which stops working at a few hundred rows, and this board is the screen an owner works from.
 */

interface OrderSummary {
  id: string;
  orderNumber: string;
  status: string;
  paymentStatus: string;
  paymentMethod: string;
  customerEmail?: string | null;
  customerName?: string | null;
  city?: string | null;
  lineCount: number;
  subtotal: number;
  discount: number;
  tax: number;
  shipping: number;
  total: number;
  totalDisplay: string;
  commission?: number;
  placedAt: string;
}

interface OrderBoard {
  page: {
    content: OrderSummary[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
    last: boolean;
  };
  status: string | null;
  countsByStatus: Record<string, number>;
  totalActive: number;
}

/** The tabs, in the reference's order. `null` is All. */
const TABS = [
  { key: null, label: 'All' },
  { key: 'PENDING', label: 'Pending' },
  { key: 'ACCEPTED', label: 'Accepted' },
  { key: 'PROCESSING', label: 'Processing' },
  { key: 'SHIPPED', label: 'Shipped' },
  { key: 'DELIVERED', label: 'Delivered' },
  { key: 'CANCELLED', label: 'Cancelled' },
] as const;

/**
 * The statuses a bulk action can move to.
 *
 * <p>These mirror the named single-order endpoints. The server refuses anything illegal per order
 * anyway, so this list is a convenience rather than a guard — but offering "Mark pending" would be
 * offering a button that can only ever fail.
 */
const BULK_ACTIONS = [
  { target: 'ACCEPTED', label: 'Accept', icon: CheckCircle2 },
  { target: 'PROCESSING', label: 'Start packing', icon: Package },
  { target: 'SHIPPED', label: 'Mark shipped', icon: Truck },
  { target: 'DELIVERED', label: 'Mark delivered', icon: CheckCircle2 },
  { target: 'CANCELLED', label: 'Cancel', icon: XCircle },
] as const;

export function ShopOrders({ auth }: { auth: AuthAdapter }) {
  const queryClient = useQueryClient();
  const [params, setParams] = useSearchParams();
  const [search, setSearch] = useState(params.get('q') ?? '');
  /** Ids ticked on this page. Deliberately not in the URL: a bulk action is transient. */
  const [selected, setSelected] = useState<string[]>([]);

  const status = params.get('status');
  const page = Number(params.get('page') ?? 0);
  const size = Number(params.get('size') ?? 20);
  const sort = params.get('sort') ?? 'newest';
  const q = params.get('q') ?? '';

  const setParam = (patch: Record<string, string | number | null>) => {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(patch)) {
      if (value === null || value === '') next.delete(key);
      else next.set(key, String(value));
    }
    setParams(next, { replace: true });
  };

  const { data, isLoading, isFetching } = useQuery({
    queryKey: ['orders', status, page, size, sort, q],
    queryFn: async () => {
      const query = new URLSearchParams({ page: String(page), size: String(size), sort });
      if (status) query.set('status', status);
      if (q) query.set('q', q);
      const response = await authorizedFetch(auth, `/api/v1/shop/orders?${query}`);
      if (!response.ok) throw new Error(`Orders request failed: ${response.status}`);
      return (await response.json()) as OrderBoard;
    },
    placeholderData: (previous) => previous,
  });

  const counts = data?.countsByStatus ?? {};
  const rows = data?.page.content ?? [];
  const pageOrders = rows;

  /**
   * Moves every ticked order to one status.
   *
   * <p>The response is a per-id result, not a status code: the server runs each order in its own
   * transaction so one refused order cannot undo the ones that worked. The owner is told which
   * order was left behind and why, because "3 of 4 succeeded" does not tell them which one to look
   * at — and the one left behind is the one that needs them.
   */
  const bulk = useMutation({
    mutationFn: async (target: string) => {
      const response = await postJson(auth, '/api/v1/shop/orders/bulk-transition', {
        orderIds: selected,
        target,
      });
      if (!response.ok) throw new Error(await readProblem(response));
      return (await response.json()) as BulkTransitionResult;
    },
    onSuccess: (result) => {
      const refused = Object.entries(result.refused);
      if (refused.length === 0) {
        toast({ title: `${result.moved.length} orders updated`, description: `All moved to the new status.` });
      } else {
        toast({
          title: `${result.moved.length} of ${result.requested} orders updated`,
          description: refused.map(([id, why]) => `${id.slice(0, 8)}: ${why}`).join(' · '),
          variant: 'destructive',
        });
      }
      setSelected([]);
      void queryClient.invalidateQueries({ queryKey: ['orders'] });
      void queryClient.invalidateQueries({ queryKey: ['shop-dashboard'] });
    },
    onError: (e: Error) =>
      toast({ title: 'The bulk action failed', description: e.message, variant: 'destructive' }),
  });

  const columns = useMemo(
    () => [
      {
        key: 'select',
        header: (
          <Checkbox
            aria-label="Select every order on this page"
            checked={pageOrders.length > 0 && selected.length === pageOrders.length}
            onCheckedChange={(on) =>
              setSelected(on ? pageOrders.map((o) => o.id) : [])
            }
          />
        ),
        className: 'w-10',
        render: (order: OrderSummary) => (
          <Checkbox
            aria-label={`Select order ${order.orderNumber}`}
            checked={selected.includes(order.id)}
            onCheckedChange={(on) =>
              setSelected((current) =>
                on ? [...current, order.id] : current.filter((id) => id !== order.id),
              )
            }
          />
        ),
      },
      {
        key: 'orderNumber',
        header: 'Order Number',
        className: 'font-medium',
        render: (order: OrderSummary) => (
          <a href={`/shop/orders/${order.id}`} className="text-primary hover:underline">
            {order.orderNumber}
          </a>
        ),
      },
      {
        key: 'date',
        header: 'Date',
        render: (order: OrderSummary) => <span className="whitespace-nowrap">{formatDate(order.placedAt)}</span>,
      },
      {
        key: 'customer',
        header: 'Customer',
        render: (order: OrderSummary) => (
          <span className="block max-w-[220px] truncate">
            {order.customerName || order.customerEmail || 'Guest'}
          </span>
        ),
      },
      {
        key: 'payment',
        header: 'Payment',
        render: (order: OrderSummary) => (
          <PaymentBadge method={order.paymentMethod} />
        ),
      },
      {
        key: 'status',
        header: 'Status',
        render: (order: OrderSummary) => <StatusBadge status={order.status} />,
      },
      {
        key: 'amount',
        header: 'Amount',
        className: 'whitespace-nowrap font-medium',
        render: (order: OrderSummary) => order.totalDisplay,
      },
      {
        key: 'view',
        header: 'View',
        className: 'text-right',
        render: (order: OrderSummary) => (
          <a
            href={`/shop/orders/${order.id}`}
            aria-label={`View order ${order.orderNumber}`}
            className="inline-block rounded p-1.5 text-muted-foreground hover:bg-muted hover:text-foreground"
          >
            <Eye className="size-4" aria-hidden />
          </a>
        ),
      },
    ],
    [],
  );

  return (
    <div className="space-y-4">
      {/* ------------------------------------------------------------- tabs */}
      <div className="flex flex-wrap gap-2">
        {TABS.map((tab) => {
          const selected = tab.key === null ? !status : status === tab.key;
          const count = tab.key === null ? data?.totalActive ?? 0 : counts[tab.key] ?? 0;
          return (
            <button
              key={tab.label}
              type="button"
              onClick={() => setParam({ status: tab.key, page: 0 })}
              aria-pressed={selected}
              className={cn(
                'inline-flex items-center gap-2 rounded-lg border px-4 py-2 text-sm transition-colors',
                selected
                  ? 'border-primary bg-primary text-primary-foreground'
                  : 'bg-card text-foreground hover:bg-muted',
              )}
            >
              {tab.label}
              <span
                className={cn(
                  'rounded px-1.5 py-0.5 text-xs',
                  selected ? 'bg-white/20' : 'bg-muted text-muted-foreground',
                )}
              >
                {count}
              </span>
            </button>
          );
        })}
      </div>

      {/* ------------------------------------------------------------- toolbar */}
      <DataTable
        data-testid="orders-table"
        columns={columns}
        rows={rows}
        rowKey={(order) => order.id}
        meta={data ? {
          page: data.page.page,
          size: data.page.size,
          totalElements: data.page.totalElements,
          totalPages: data.page.totalPages,
        } : undefined}
        onPageChange={(next) => setParam({ page: next })}
        onPageSizeChange={(next) => setParam({ size: next, page: 0 })}
        empty={
          q
            ? `No orders match "${q}".`
            : 'No orders in this state yet. They appear here as customers place them.'
        }
        toolbar={
          <div className="flex flex-wrap items-center gap-3 p-4">
            {selected.length > 0 ? (
              <div className="flex flex-wrap items-center gap-2 rounded-lg border border-emerald-300 bg-success/10 px-3 py-2">
                <span className="text-sm font-medium text-emerald-900">
                  {selected.length} selected
                </span>
                {BULK_ACTIONS.map((action) => {
                  const Icon = action.icon;
                  return (
                    <Button
                      key={action.target}
                      size="sm"
                      disabled={bulk.isPending}
                      onClick={() => bulk.mutate(action.target)}
                      style={
                        action.target === 'CANCELLED'
                          ? { background: '#fee2e2', color: '#dc2626' }
                          : undefined
                      }
                      className={action.target === 'CANCELLED' ? 'border-0' : undefined}
                    >
                      <Icon className="mr-1.5 size-3.5" aria-hidden />
                      {action.label}
                    </Button>
                  );
                })}
                <Button size="sm" variant="ghost" onClick={() => setSelected([])}>
                  Clear
                </Button>
              </div>
            ) : null}

            <form
              className="relative min-w-[280px] flex-1"
              onSubmit={(e) => {
                e.preventDefault();
                setParam({ q: search.trim() || null, page: 0 });
              }}
            >
              <Search
                className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
                aria-hidden
              />
              <Input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Order ID, phone or a name"
                aria-label="Search orders"
                className="pl-9"
              />
            </form>

            <Button variant="outline" asChild>
              <a
                href={`/api/v1/shop/orders/export.csv${q ? `?q=${encodeURIComponent(q)}` : ''}${
                  status ? `${q ? '&' : '?'}status=${status}` : ''
                }`}
                onClick={(e) => {
                  // The API needs the Bearer token and a plain <a href> cannot send one, so the
                  // export is fetched and handed to the browser as a blob instead of navigating.
                  e.preventDefault();
                  void downloadOrders(auth, status, q);
                }}
              >
                <Download className="size-4" aria-hidden />
                Export
              </a>
            </Button>

            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline">
                  <SlidersHorizontal className="size-4" aria-hidden />
                  Sort by
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                {(
                  [
                    ['newest', 'Date'],
                    ['oldest', 'Oldest first'],
                    ['amount', 'Amount'],
                    ['customer', 'Customer'],
                    ['status', 'Status'],
                  ] as Array<[string, string]>
                ).map(([value, label]) => (
                  <DropdownMenuItem key={value} onSelect={() => setParam({ sort: value, page: 0 })}>
                    {label}
                    {sort === value ? <span className="ml-auto text-xs text-muted-foreground">current</span> : null}
                  </DropdownMenuItem>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>

            <Button variant="outline" disabled title="Lifetime totals arrive with Phase 4 reports">
              <Clock className="size-4" aria-hidden />
              Lifetime
            </Button>
          </div>
        }
      />
      {isFetching && !isLoading ? (
        <p className="text-xs text-muted-foreground">Refreshing…</p>
      ) : null}
    </div>
  );
}

// ---------------------------------------------------------------------- pieces

function PaymentBadge({ method }: { method: string }) {
  const styles: Record<string, string> = {
    ESAWA: 'bg-success/10 text-success',
    COD: 'bg-orange-50 text-orange-700',
    BANK_TRANSFER: 'bg-sky-50 text-sky-700',
  };
  return (
    <Badge className={cn('font-medium', styles[method] ?? 'bg-muted text-muted-foreground')}>
      {method.replace('_', ' ')}
    </Badge>
  );
}

function StatusBadge({ status }: { status: string }) {
  // The colour is the status itself, so a shop owner reads the board by colour before reading
  // the word. Amber is waiting on you, green is done, red is gone wrong.
  const styles: Record<string, string> = {
    PENDING: 'bg-warning/10 text-warning',
    ACCEPTED: 'bg-orange-50 text-orange-700',
    PROCESSING: 'bg-sky-50 text-sky-700',
    SHIPPED: 'bg-indigo-50 text-indigo-700',
    DELIVERED: 'bg-success/10 text-success',
    CANCELLED: 'bg-destructive/10 text-destructive',
  };
  return (
    <Badge className={cn('font-medium', styles[status] ?? 'bg-muted text-muted-foreground')}>
      {status}
    </Badge>
  );
}

function formatDate(iso: string): string {
  const date = new Date(iso);
  return date.toLocaleString('en-US', {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  });
}

/** Fetches the CSV with the Bearer token and saves it, since a plain link cannot send one. */
async function downloadOrders(auth: AuthAdapter, status: string | null, q: string) {
  const query = new URLSearchParams();
  if (status) query.set('status', String(status));
  if (q) query.set('q', q);
  const response = await authorizedFetch(auth, `/api/v1/shop/orders/export.csv?${query}`);
  if (!response.ok) return;
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = 'orders.csv';
  link.click();
  URL.revokeObjectURL(url);
}

/** Turns a problem response into one readable line, so a bulk failure is never "HTTP 400". */
async function readProblem(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { detail?: string; title?: string };
    if (body.detail) return body.detail;
    if (body.title) return body.title;
  } catch {
    // Not a problem+ body.
  }
  return `The server rejected this (HTTP ${response.status})`;
}
