import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Building2, Coins, Percent, ShoppingBag, Store, TrendingUp, Users } from 'lucide-react';

import { authorizedFetch } from '@store-app/auth';
import type { AuthAdapter } from '@store-app/auth';
import {
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Skeleton,
} from '@store-app/ui';
import { cn } from '@store-app/ui';

import { formatDateTime, readProblem } from '../lib/format';

/**
 * One vertical's money, which is the D17 payoff made visible.
 *
 * <p>Nineteen shop types on one platform, and "which of them actually make money" has no answer
 * without grouping by type — revenue summed across verticals is one number that describes no
 * vertical at all. So this table is the reason the dashboard exists rather than a nice extra.
 *
 * <p><b>A vertical with no orders is still listed, with zero.</b> A {@code LEFT JOIN} that dropped
 * it would make the type look as though it had ceased to exist, and that is the one conclusion this
 * table exists to prevent.
 */
interface TypeRow {
  shopType: string;
  label: string;
  shops: number;
  activeShops: number;
  orders: number;
  gmvMinor: number;
  gmvDisplay: string;
  commissionMinor: number;
  commissionDisplay: string;
  commissionSharePercent: number;
}

interface ShopRow {
  shopId: string;
  shopType: string;
  shopStatus: string;
  featured: boolean;
  orders: number;
  gmvMinor: number;
  gmvDisplay: string;
  commissionMinor: number;
}

interface KpiDashboard {
  asOf: string;
  windowDays: number;
  window: string;
  orders: number;
  previousOrders: number;
  orderGrowthPercent: number | null;
  gmvMinor: number;
  gmvDisplay: string;
  previousGmvMinor: number;
  gmvGrowthPercent: number | null;
  commissionMinor: number;
  commissionDisplay: string;
  waivedMinor: number;
  waivedDisplay: string;
  commissionRateBp: number;
  commissionRatePercent: number;
  buyers: number;
  activeShops: number;
  totalShops: number;
  verifiedShops: number;
  churnedShops: number;
  churnRatePercent: number;
  arpuPerActiveShopMinor: number;
  arpuPerActiveShopDisplay: string;
  arpuPerVerifiedShopMinor: number;
  arpuPerVerifiedShopDisplay: string;
  revenueByType: TypeRow[];
  topShops: ShopRow[];
  peopleByRealmAndStatus: Record<string, number>;
  mrrDisplay: string;
  mrrMinor: number;
  paidSubscribers: number;
}

/**
 * The platform dashboard (Phase 7).
 *
 * <p><b>Every ratio names its denominator.</b> ARPU is shown twice — per shop that traded, and per
 * verified shop — because those answer different questions and "ARPU" on its own means whichever
 * one the author happened to pick. The same applies to churn, which is a percentage <em>of shops
 * that traded in the previous window</em>, not of all shops.
 *
 * <p><b>Growth reads "no prior period" rather than 0% when there is no baseline.</b> "Up 0%" from
 * no prior orders is a claim; "we have nothing to compare against" is the truth, and a dashboard
 * that says 0% is telling an operator growth was zero when it is unknown.
 *
 * <p><b>Commission share of GMV is the one figure that says whether a vertical is worth the
 * platform's attention</b> — a high share means lots of small orders, which is where 5% actually
 * costs a shop something. A vertical with low GMV and high share is where the commission hurts
 * most relative to the revenue it brings.
 */
export function AdminDashboard({ auth }: { auth: AuthAdapter }) {
  const [days, setDays] = useState(30);

  const { data, isLoading, error } = useQuery({
    queryKey: ['admin', 'kpis', days],
    queryFn: async () => {
      const response = await authorizedFetch(auth, `/api/v1/admin/kpis?days=${days}`);
      if (!response.ok) throw new Error(await readProblem(response));
      return (await response.json()) as KpiDashboard;
    },
  });

  if (isLoading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-8 w-64" />
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {Array.from({ length: 8 }).map((_, i) => (
            <Skeleton key={i} className="h-24 rounded-xl" />
          ))}
        </div>
        <Skeleton className="h-64 rounded-xl" />
      </div>
    );
  }

  if (error || !data) {
    return (
      <Card>
        <CardContent className="p-10 text-center">
          <p className="font-medium">Could not load the dashboard</p>
          <p className="mt-1 text-sm text-muted-foreground">{error?.message}</p>
        </CardContent>
      </Card>
    );
  }

  const maxGmv = Math.max(1, ...data.revenueByType.map((row) => row.gmvMinor));

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">Platform</h1>
          {/* The window goes at the top of the screen, once, rather than on each tile. Every
              figure below it is for this window and nothing else. */}
          <p className="text-muted-foreground">
            {data.window}. As of {formatDateTime(data.asOf)}.
          </p>
        </div>
        <div className="flex gap-1" role="group" aria-label="Reporting window">
          {[7, 30, 90, 365].map((option) => (
            <Button
              key={option}
              size="sm"
              variant={option === days ? 'default' : 'outline'}
              onClick={() => setDays(option)}
              aria-pressed={option === days}
            >
              {option === 365 ? '1y' : `${option}d`}
            </Button>
          ))}
        </div>
      </header>

      {/* --- trading */}
      <section className="space-y-3">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-muted-foreground">
          Trading
        </h2>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Stat
            label="GMV"
            value={data.gmvDisplay}
            hint={`What customers spent, delivery included. ${growth(data.gmvGrowthPercent)}`}
            icon={<ShoppingBag className="size-4" aria-hidden />}
          />
          <Stat
            label="Orders"
            value={data.orders.toLocaleString('en-US')}
            hint={`Placed in this window. ${growth(data.orderGrowthPercent)}`}
            icon={<Coins className="size-4" aria-hidden />}
          />
          <Stat
            label="Commission"
            value={data.commissionDisplay}
            hint={`${data.commissionRatePercent}% of GMV on orders that did not waive it.`}
            icon={<Percent className="size-4" aria-hidden />}
          />
          <Stat
            label="Commission waived"
            value={data.waivedDisplay}
            hint="Not charged because the shop was subscribed. Revenue forgone."
            tone={data.waivedMinor > 0 ? 'warn' : 'plain'}
          />
        </div>
      </section>

      {/* --- shops and people. Each ratio names what it is over. */}
      <section className="space-y-3">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-muted-foreground">
          Shops and people
        </h2>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Stat
            label="Shops trading"
            value={data.activeShops.toLocaleString('en-US')}
            hint={`Shops with at least one order in this window, of ${data.verifiedShops.toLocaleString(
              'en-US',
            )} verified and ${data.totalShops.toLocaleString('en-US')} total.`}
            icon={<Store className="size-4" aria-hidden />}
          />
          <Stat
            label="Churn"
            value={`${data.churnRatePercent.toFixed(1)}%`}
            hint={`${data.churnedShops} shops traded in the previous ${data.windowDays} days and not in this one. Activity churn, not subscription lapses — a dormant shop is churned whether or not it still pays.`}
            tone={data.churnRatePercent > 20 ? 'warn' : 'plain'}
          />
          <Stat
            label="ARPU, per trading shop"
            value={data.arpuPerActiveShopDisplay}
            hint="GMV divided by the shops that took at least one order. The flattering version."
            icon={<TrendingUp className="size-4" aria-hidden />}
          />
          <Stat
            label="ARPU, per verified shop"
            value={data.arpuPerVerifiedShopDisplay}
            hint="GMV divided by every verified shop, including the ones that sold nothing. The honest version."
            icon={<Building2 className="size-4" aria-hidden />}
          />
        </div>

        <div className="grid gap-3 sm:grid-cols-3">
          <Stat
            label="Buyers"
            value={data.buyers.toLocaleString('en-US')}
            hint="Distinct customers who placed at least one order in this window."
            icon={<Users className="size-4" aria-hidden />}
          />
          <Stat
            label="MRR"
            value={data.mrrDisplay}
            hint={`Live, not windowed. ${data.paidSubscribers} paid subscribers.`}
          />
          <Stat
            label="Accounts"
            value={Object.values(data.peopleByRealmAndStatus)
              .reduce((sum, n) => sum + n, 0)
              .toLocaleString('en-US')}
            hint="Across both Keycloak realms, so an operator can see one list rather than two."
          />
        </div>

        {Object.keys(data.peopleByRealmAndStatus).length > 0 ? (
          <Card>
            <CardHeader>
              <CardTitle className="text-base">People by realm</CardTitle>
              <CardDescription>
                Two realms, one list. An admin's questions cross the line constantly — &ldquo;this
                person cannot log in to their shop&rdquo; is an operator, &ldquo;this customer is
                spamming orders&rdquo; is a customer — so showing one realm at a time makes them
                guess at the other.
              </CardDescription>
            </CardHeader>
            <CardContent className="flex flex-wrap gap-2">
              {Object.entries(data.peopleByRealmAndStatus).map(([key, count]) => (
                <span key={key} className="rounded-full border px-3 py-1 text-xs">
                  {key}: <strong>{count.toLocaleString('en-US')}</strong>
                </span>
              ))}
            </CardContent>
          </Card>
        ) : null}
      </section>

      {/* --- the D17 payoff */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Revenue by shop type</CardTitle>
          <CardDescription>
            Every vertical, including the ones with no orders in this window. Commission share is
            commission over GMV: a high share means lots of small orders, which is where the rate
            costs a shop most.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-2">
          {data.revenueByType.length === 0 ? (
            <EmptyState />
          ) : (
            data.revenueByType.map((row) => (
              <div key={row.shopType} className="grid items-center gap-3 sm:grid-cols-[1fr_2fr_auto]">
                <div className="min-w-0">
                  <p className="truncate text-sm font-medium">{row.label}</p>
                  <p className="text-xs text-muted-foreground">
                    {row.activeShops} of {row.shops} trading
                  </p>
                </div>
                {/* A hand-drawn bar, not a chart library: this is one number per row, and a
                    charting dependency to render it would be a bundle for eight divs. */}
                <div className="h-2.5 w-full overflow-hidden rounded-full bg-muted">
                  <div
                    className="h-full rounded-full bg-primary"
                    style={{ width: `${Math.max(1, (row.gmvMinor / maxGmv) * 100)}%` }}
                  />
                </div>
                <div className="text-right text-sm">
                  <p className="font-medium">{row.gmvDisplay}</p>
                  <p className="text-xs text-muted-foreground">
                    {row.commissionDisplay} · {row.commissionSharePercent.toFixed(2)}% of GMV
                  </p>
                </div>
              </div>
            ))
          )}
        </CardContent>
      </Card>

      {/* --- who is actually selling */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Top shops</CardTitle>
          <CardDescription>
            By GMV in this window. Shops with no orders are left out here — this is a leaderboard,
            and the shops report is where a shop that has gone quiet shows up.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-1">
          {data.topShops.length === 0 ? (
            <EmptyState />
          ) : (
            data.topShops.map((shop, index) => (
              <div
                key={shop.shopId}
                className="flex items-center justify-between gap-3 border-b py-2 last:border-0"
              >
                <div className="flex min-w-0 items-center gap-3">
                  <span className="w-5 text-xs text-muted-foreground">{index + 1}</span>
                  <span className="truncate text-sm font-mono text-xs text-muted-foreground">
                    {shop.shopId.slice(0, 8)}
                  </span>
                  {shop.featured ? (
                    <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[10px] text-amber-800">
                      Featured
                    </span>
                  ) : null}
                </div>
                <div className="text-right text-sm">
                  <p className="font-medium">{shop.gmvDisplay}</p>
                  <p className="text-xs text-muted-foreground">
                    {shop.orders.toLocaleString('en-US')} orders · {shop.shopType}
                  </p>
                </div>
              </div>
            ))
          )}
        </CardContent>
      </Card>
    </div>
  );
}

/**
 * Honest empty state, not invented data.
 *
 * <p>A vertical list with rows in it is what makes a dashboard worth reading; a screen that fills
 * itself with made-up bars when there is nothing to show teaches an operator to trust a chart that
 * is not true.
 */
function EmptyState() {
  return (
    <p className="py-6 text-center text-sm text-muted-foreground">
      Nothing to show yet. Nothing has happened in this window.
    </p>
  );
}

/**
 * A growth figure, or the absence of one.
 *
 * <p>Null is not zero. "Up 0% from no prior sales" is a claim; "no previous period to compare
 * against" is the truth, and a dashboard that prints 0% is asserting that growth was zero when it
 * is merely unknown.
 */
function growth(percent: number | null): string {
  if (percent === null) return 'no previous period to compare against';
  const rounded = Math.round(percent * 10) / 10;
  return rounded === 0
    ? 'unchanged on the previous period'
    : `${rounded > 0 ? 'up' : 'down'} ${Math.abs(rounded)}% on the previous period`;
}

function Stat({
  label,
  value,
  hint,
  icon,
  tone = 'plain',
}: {
  label: string;
  value: string;
  hint?: string;
  icon?: React.ReactNode;
  tone?: 'plain' | 'warn';
}) {
  return (
    <Card className={cn(tone === 'warn' && 'border-amber-300 bg-amber-50/40')}>
      <CardContent className="p-4">
        <p className="flex items-center gap-1.5 text-xs font-medium uppercase tracking-wide text-muted-foreground">
          {icon}
          {label}
        </p>
        <p className="mt-1 text-2xl font-semibold tracking-tight">{value}</p>
        {hint ? <p className="mt-1 text-xs text-muted-foreground">{hint}</p> : null}
      </CardContent>
    </Card>
  );
}