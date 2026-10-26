import { useQuery } from '@tanstack/react-query';
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';

import { authorizedFetch } from '@store-app/auth';
import type { AuthAdapter } from '@store-app/auth';
import type { Dashboard } from '@store-app/api-client';
import { Badge, Card, CardContent, Skeleton } from '@store-app/ui';
import { AlertTriangle } from 'lucide-react';

/**
 * The shop dashboard (Phase 4).
 *
 * <p>Charts replicated from the reference application: a grouped revenue bar chart and a monthly
 * sales line, with the same palette and axis treatment.
 *
 * <p><b>Every figure comes from the shop's own orders.</b> Nothing is seeded. A shop with no sales
 * sees zeros and flat charts, which is the truth — and an owner who mistakes invented numbers for
 * real ones will price against them.
 */

/**
 * Chart series, taken from the palette rather than from the reference project's terracotta and
 * teal. Those two read as a different product sitting inside this one: the bar was the loudest
 * thing on the dashboard and it was the only element not drawn from the coffee family.
 */
const SERIES = ['#7A5240', '#4E7C59'];

export function ShopDashboard({ auth }: { auth: AuthAdapter }) {
  const { data, isLoading, error } = useQuery({
    queryKey: ['shop-dashboard'],
    queryFn: async () => {
      const response = await authorizedFetch(auth, '/api/v1/shop/dashboard');
      if (!response.ok) throw new Error(`Dashboard request failed: ${response.status}`);
      return (await response.json()) as Dashboard;
    },
    staleTime: 60_000,
  });

  if (isLoading) return <DashboardSkeleton />;

  if (error) {
    return (
      <Card>
        <CardContent className="p-8 text-center">
          <p className="font-medium">Could not load your dashboard</p>
          <p className="mt-1 text-sm text-muted-foreground">{error.message}</p>
        </CardContent>
      </Card>
    );
  }
  if (!data) return null;

  return (
    <div className="space-y-6">
      {/* ------------------------------------------------------------ KPI row */}
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="Total Revenue"
          value={data.totalRevenueDisplay}
          changePct={data.revenueChangePct}
          icon={<span className="text-lg">$</span>}
          testId="stat-revenue"
        />
        <StatCard
          label="Sales"
          value={data.sales.toLocaleString('en-US')}
          changePct={data.salesChangePct}
          icon={<CartIcon />}
          testId="stat-sales"
        />
        <StatCard
          label="New Customers"
          value={data.newCustomers.toLocaleString('en-US')}
          changePct={data.newCustomersChangePct}
          icon={<UsersIcon />}
          testId="stat-customers"
        />
        <StatCard
          label="Products Sold"
          value={data.unitsSold.toLocaleString('en-US')}
          changePct={data.unitsSoldChangePct}
          icon={<BoxIcon />}
          testId="stat-units"
        />
      </div>

      {/* ------------------------------------------------------------ charts */}
      <div className="grid gap-4 xl:grid-cols-3">
        <Card className="xl:col-span-2">
          <CardContent className="p-6">
            <h2 className="text-base font-semibold">Revenue Overview</h2>
            <p className="mt-0.5 text-sm text-muted-foreground">
              Revenue for the last 6 months
            </p>
            <div className="mt-6 h-[320px]">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={data.revenueByMonth} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="4 4" stroke="#E7DCCF" vertical={false} />
                  <XAxis dataKey="month" stroke="#9ca3af" fontSize={13} tickLine={false} axisLine={false} />
                  <YAxis
                    stroke="#9ca3af"
                    fontSize={13}
                    tickLine={false}
                    axisLine={false}
                    width={48}
                    // Minor units are rupees-times-100, which would label the axis in paisa.
                    tickFormatter={(v: number) => (v / 100).toLocaleString('en-US')}
                  />
                  <Tooltip
                    cursor={{ fill: 'rgba(0,0,0,0.04)' }}
                    formatter={(v: number) => [`Rs. ${(v / 100).toLocaleString('en-US', { minimumFractionDigits: 2 })}`, 'Revenue']}
                  />
                  <Bar dataKey="amountMinor" name="Revenue" fill={SERIES[0]} radius={[3, 3, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </CardContent>
        </Card>

        <TrafficSourcesCard />
        <LowStockCard auth={auth} />
      </div>

      {/* ------------------------------------------------------------ sales trend */}
      <Card>
        <CardContent className="p-6">
          <h2 className="text-base font-semibold">Sales Trend</h2>
          <p className="mt-0.5 text-sm text-muted-foreground">Monthly sales performance</p>
          <div className="mt-6 h-[300px]">
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={data.salesByMonth} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
                <defs>
                  <linearGradient id="salesFill" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor={SERIES[0]} stopOpacity={0.35} />
                    <stop offset="100%" stopColor={SERIES[0]} stopOpacity={0.02} />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="4 4" stroke="#E7DCCF" vertical={false} />
                <XAxis dataKey="month" stroke="#9ca3af" fontSize={13} tickLine={false} axisLine={false} />
                <YAxis stroke="#9ca3af" fontSize={13} tickLine={false} axisLine={false} width={40} />
                <Tooltip formatter={(v: number) => [`${v} orders`, 'Sales']} />
                <Area
                  type="monotone"
                  dataKey="orders"
                  name="Sales"
                  stroke={SERIES[0]}
                  strokeWidth={2}
                  fill="url(#salesFill)"
                  dot={{ r: 4, fill: SERIES[0] }}
                />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        </CardContent>
      </Card>

      {/* ------------------------------------------------------------ action list */}
      <div className="grid gap-4 md:grid-cols-3">
        <StatCard label="Open orders" value={String(data.openOrders)} icon={<CartIcon />} testId="stat-open-orders" />
        <StatCard label="Low stock variants" value={String(data.lowStockVariants)} icon={<BoxIcon />} testId="stat-low-stock" />
        <StatCard
          label="Catalogue"
          value={`${data.publishedProducts} live · ${data.draftProducts} draft`}
          icon={<span className="text-lg">#</span>}
          testId="stat-catalogue"
        />
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------- pieces

function StatCard({
  label,
  value,
  changePct,
  icon,
  testId,
}: {
  label: string;
  value: string;
  changePct?: number | null;
  icon: React.ReactNode;
  testId?: string;
}) {
  return (
    <Card data-testid={testId}>
      <CardContent className="p-6">
        <div className="flex items-start justify-between">
          <p className="text-sm text-muted-foreground">{label}</p>
          <span className="text-muted-foreground">{icon}</span>
        </div>
        <p className="mt-3 text-3xl font-semibold tracking-tight">{value}</p>
        {changePct !== undefined ? <Trend changePct={changePct} /> : null}
      </CardContent>
    </Card>
  );
}

/**
 * The change chip.
 *
 * <p>Renders nothing rather than "0%" when there is no previous period. "0%" would assert no
 * change, and there is no data to have changed from.
 */
function Trend({ changePct }: { changePct: number | null }) {
  if (changePct === null || changePct === undefined) {
    return <p className="mt-3 text-xs text-muted-foreground">No previous period to compare</p>;
  }
  const up = changePct >= 0;
  return (
    <p className="mt-3 flex items-center gap-2 text-xs text-muted-foreground">
      <span
        className={
          up
            ? 'rounded-md bg-success/10 px-2 py-1 font-medium text-success'
            : 'rounded-md bg-destructive/10 px-2 py-1 font-medium text-destructive'
        }
      >
        {up ? '↗' : '↘'} {up ? '+' : ''}
        {changePct.toFixed(1)}%
      </span>
      from last month
    </p>
  );
}

/**
 * Traffic sources, shown honestly as unavailable.
 *
 * <p>The reference renders a donut here. Nothing in this platform records where a visitor came
 * from, so those percentages would have to be invented — and a chart of plausible numbers is
 * more persuasive than a missing one, which is exactly why an owner would make a marketing
 * decision on it. The card keeps its place so the layout matches, and says what is missing.
 */
function TrafficSourcesCard() {
  return (
    <Card>
      <CardContent className="p-6">
        <h2 className="text-base font-semibold">Traffic Sources</h2>
        <p className="mt-0.5 text-sm text-muted-foreground">Visitor distribution by source</p>
        <div className="mt-6 flex h-[260px] flex-col items-center justify-center rounded-lg border border-dashed p-6 text-center">
          <p className="text-sm font-medium">Not tracked yet</p>
          <p className="mt-1 max-w-xs text-xs text-muted-foreground">
            This platform does not record where a visitor came from. Rather than show invented
            percentages, the card stays empty until traffic tracking exists.
          </p>
        </div>
      </CardContent>
    </Card>
  );
}

function DashboardSkeleton() {
  return (
    <div className="space-y-6">
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        {Array.from({ length: 4 }, (_, i) => (
          <Skeleton key={i} className="h-32 rounded-xl" />
        ))}
      </div>
      <Skeleton className="h-[420px] rounded-xl" />
    </div>
  );
}

// ---------------------------------------------------------------------- low stock

interface LowStockRow {
  productId: string;
  variantId: string;
  variantName: string | null;
  sku: string | null;
  onHand: number;
  reserved: number;
  available: number;
  lowStockThreshold: number;
  lowStock: boolean;
}

/**
 * What is running out, on the dashboard rather than buried in a stock screen.
 *
 * <p>The figure is the server's `available` — on hand minus reserved. Reserved stock is committed
 * to orders that have been placed but not yet accepted, so counting it would tell an owner they
 * have more than they can actually sell.
 *
 * <p><b>A warning, never a rule.</b> Nothing here decides a shop has "too little" of something it
 * intends to restock tomorrow, and no row offers a one-click reorder for a product the shop may not
 * intend to restock at all.
 */
function LowStockCard({ auth }: { auth: AuthAdapter }) {
  const { data, isLoading } = useQuery({
    queryKey: ['low-stock'],
    queryFn: async () => {
      const response = await authorizedFetch(auth, '/api/v1/shop/stock/low');
      if (!response.ok) return [];
      return (await response.json()) as LowStockRow[];
    },
  });

  // Ordered worst-first so an out-of-stock line is the first thing read.
  const rows = [...(data ?? [])].sort((a, b) => a.available - b.available).slice(0, 6);
  const outOfStock = (data ?? []).filter((r) => r.available <= 0).length;

  return (
    <Card>
      <CardContent className="p-6">
        <div className="flex items-start justify-between gap-3">
          <div>
            <h2 className="text-base font-semibold">Low Stock</h2>
            <p className="mt-0.5 text-sm text-muted-foreground">
              {isLoading
                ? 'Checking…'
                : (data ?? []).length === 0
                  ? 'Everything is above its warning level.'
                  : `${(data ?? []).length} item${(data ?? []).length === 1 ? '' : 's'} at or below its threshold${outOfStock ? `, ${outOfStock} out of stock` : ''}.`}
            </p>
          </div>
          {(data ?? []).length > 0 ? (
            <a
              href="/shop/stock"
              className="shrink-0 text-sm text-primary hover:underline"
            >
              Manage stock
            </a>
          ) : null}
        </div>

        {isLoading ? (
          <Skeleton className="mt-5 h-32 rounded-lg" />
        ) : rows.length === 0 ? (
          <p className="mt-5 rounded-lg border border-dashed p-4 text-center text-sm text-muted-foreground">
            Nothing is running low right now.
          </p>
        ) : (
          <ul className="mt-5 divide-y">
            {rows.map((row) => (
              <li key={row.variantId} className="flex items-center gap-3 py-2.5">
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm font-medium">
                    {row.variantName || 'Unnamed variant'}
                  </span>
                  {row.sku ? (
                    <span className="block truncate font-mono text-xs text-muted-foreground">
                      {row.sku}
                    </span>
                  ) : null}
                </span>
                <span className="text-xs text-muted-foreground">
                  warn at {row.lowStockThreshold}
                </span>
                <Badge
                  className={
                    row.available <= 0
                      ? 'bg-destructive/10 text-destructive'
                      : row.available <= row.lowStockThreshold
                        ? 'bg-warning/10 text-warning'
                        : 'bg-muted text-muted-foreground'
                  }
                >
                  {row.available <= 0 ? (
                    <>
                      <AlertTriangle className="mr-1 size-3" aria-hidden />
                      Out of stock
                    </>
                  ) : (
                    `${row.available} left`
                  )}
                </Badge>
              </li>
            ))}
          </ul>
        )}

        {rows.length > 0 ? (
          <p className="mt-4 text-xs text-muted-foreground">
            Available is what you can sell now: {rows.length === 1 ? 'this item' : 'these items'}{' '}
            minus stock already reserved for orders you have accepted.
          </p>
        ) : null}
      </CardContent>
    </Card>
  );
}

// Small inline glyphs, so the KPI icons match the reference without a second icon set.
const CartIcon = () => (
  <svg viewBox="0 0 24 24" className="size-5" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden>
    <path d="M3 4h2l2.4 10.5a2 2 0 0 0 2 1.5h7.7a2 2 0 0 0 2-1.5L21 8H6" strokeLinecap="round" strokeLinejoin="round" />
    <circle cx="10" cy="20" r="1.3" />
    <circle cx="18" cy="20" r="1.3" />
  </svg>
);
const UsersIcon = () => (
  <svg viewBox="0 0 24 24" className="size-5" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden>
    <circle cx="9" cy="8" r="3" />
    <path d="M3 19a6 6 0 0 1 12 0M17 11a2.6 2.6 0 1 0 0-5.2M17.5 19H21a5 5 0 0 0-3.2-4.6" strokeLinecap="round" />
  </svg>
);
const BoxIcon = () => (
  <svg viewBox="0 0 24 24" className="size-5" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden>
    <path d="M12 2.8 20.5 7v10L12 21.2 3.5 17V7L12 2.8Z" strokeLinejoin="round" />
    <path d="M3.5 7 12 11.3 20.5 7M12 11.3V21.2" strokeLinejoin="round" />
  </svg>
);
