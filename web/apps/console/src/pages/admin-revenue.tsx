import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, ArrowDownRight, Coins, RefreshCw, TrendingUp, Users } from 'lucide-react';

import { authorizedFetch, postJson } from '@store-app/auth';
import type { AuthAdapter } from '@store-app/auth';
import type { RevenueSummary } from '@store-app/api-client';
import { Button, Card, CardContent, CardDescription, CardHeader, CardTitle, Skeleton } from '@store-app/ui';
import { cn } from '@store-app/ui';

import { formatDateTime, readProblem } from '../lib/format';

/**
 * Revenue (Phase 6).
 *
 * <p><b>Every ratio names its denominator, and the window is stated at the top.</b> A revenue
 * dashboard is mostly ratios, and a ratio without its denominator is a number that means something
 * different on every screen it appears on. So: subscription ARPU is *per subscriber*, revenue per
 * trading shop is *per verified shop and counts commission too*, and the panel says which is which
 * rather than letting the label "ARPU" do the work.
 *
 * <p><b>Realised and waived commission are shown as separate figures, and both matter.</b> Realised
 * is what the ledger debited. Waived is what orders did *not* charge because the shop was
 * subscribed. Reporting only realised makes a subscription programme that is working look exactly
 * like falling revenue — which is the mistake that leads to a platform killing the thing that is
 * retaining its shops.
 *
 * <p><b>The trend is a hand-drawn SVG bar chart, not a charting library.</b> Six bars of one
 * series is not what a chart library is for, and pulling recharts in for it would add a dependency
 * and a bundle to draw six rectangles.
 */
export function AdminRevenue({ auth }: { auth: AuthAdapter }) {
  const queryClient = useQueryClient();
  const [months, setMonths] = useState(6);

  const { data, isLoading, error } = useQuery({
    queryKey: ['admin', 'revenue', months],
    queryFn: async () => {
      const response = await authorizedFetch(auth, `/api/v1/admin/revenue?months=${months}`);
      if (!response.ok) throw new Error(await readProblem(response));
      return (await response.json()) as RevenueSummary;
    },
  });

  const expire = useMutation({
    mutationFn: async () => {
      const response = await postJson(auth, '/api/v1/admin/subscriptions/expire', {});
      if (!response.ok) throw new Error(await readProblem(response));
      return (await response.json()) as { closed: number; message: string };
    },
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['admin', 'revenue'] }),
  });

  if (isLoading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-8 w-48" />
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {Array.from({ length: 4 }).map((_, i) => (
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
          <p className="font-medium">Could not load revenue</p>
          <p className="mt-1 text-sm text-muted-foreground">{error?.message}</p>
        </CardContent>
      </Card>
    );
  }

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">Revenue</h1>
          <p className="text-muted-foreground">
            {data.window}. As of {formatDateTime(data.asOf)}.
          </p>
        </div>
        <Button variant="outline" onClick={() => expire.mutate()} disabled={expire.isPending}>
          <RefreshCw className={cn('mr-1.5 size-4', expire.isPending && 'animate-spin')} aria-hidden />
          Close lapsed subscriptions
        </Button>
      </header>

      {expire.data ? (
        <p className="rounded-md border bg-muted/40 p-3 text-sm" role="status">
          {expire.data.message}
        </p>
      ) : null}
      {expire.error ? (
        <p role="alert" className="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm">
          {expire.error.message}
        </p>
      ) : null}

      {/* --- subscriptions */}
      <section className="space-y-3">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-muted-foreground">
          Subscriptions
        </h2>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Stat
            label="MRR"
            value={data.mrrDisplay}
            hint="Live. An annual plan counts as a twelfth of its price, so this is comparable month to month."
            icon={<TrendingUp className="size-4" aria-hidden />}
          />
          <Stat
            label="Paying subscribers"
            value={data.paidSubscribers.toLocaleString('en-US')}
            hint="Shops with a running paid plan right now."
            icon={<Users className="size-4" aria-hidden />}
          />
          <Stat
            label="Commission-free shops"
            value={data.commissionFreeShops.toLocaleString('en-US')}
            hint="Shops whose entitlements currently grant no commission."
          />
          <Stat
            label="Shops"
            value={`${data.tradingShops.toLocaleString('en-US')} trading`}
            hint={`of ${data.totalShops.toLocaleString('en-US')} total. "Trading" means verified.`}
          />
        </div>
      </section>

      {/* --- commission, both halves */}
      <section className="space-y-3">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-muted-foreground">
          Commission
        </h2>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Stat
            label="Realised"
            value={data.commissionRealisedDisplay}
            hint="Charged and kept, net of reversals. From the ledger, because that is money that moved."
          />
          <Stat
            label="Waived"
            value={data.commissionWaivedDisplay}
            hint="Not charged because the shop was subscribed. Revenue forgone — the cost of the programme."
            tone={data.commissionWaivedMinor > 0 ? 'warn' : 'plain'}
          />
          <Stat
            label="Reversed"
            value={data.commissionReversedDisplay}
            hint="Given back on cancelled orders."
          />
          <Stat
            label="Accepted orders"
            value={data.ordersAccepted.toLocaleString('en-US')}
            hint="The denominator for any per-order figure."
          />
        </div>

        {data.waiverCoverage ? (
          <Card
            className={cn(
              Number(data.waiverCoverage) < 1 && 'border-warning/40 bg-warning/10/40',
            )}
          >
            <CardContent className="p-4">
              <p className="text-sm">
                <strong>Waiver coverage: {data.waiverCoverage}×.</strong> Every rupee of subscription
                revenue is earned against {data.waiverCoverage} rupees of commission forgone.
              </p>
              <p className="mt-1 text-sm text-muted-foreground">
                {Number(data.waiverCoverage) < 1
                  ? 'Below 1.0, subscribers cost more commission than they pay — which is what happens when a plan is priced under 5% of monthly GMV. Worth revisiting before the next pricing change.'
                  : 'Above 1.0, subscribers pay more than the commission they cost you.'}
              </p>
            </CardContent>
          </Card>
        ) : null}
      </section>

      {/* --- money in, and the ratios */}
      <section className="space-y-3">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-muted-foreground">
          Money in, and per-shop revenue
        </h2>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          <Stat
            label="Bank transfers in"
            value={data.topUpsDisplay}
            hint="Includes subscription purchases — buying one is a transfer in."
            icon={<Coins className="size-4" aria-hidden />}
          />
          <Stat
            label="Subscription charges"
            value={data.subscriptionChargesDisplay}
            hint="What those subscriptions cost. Not netted against the transfers above."
          />
          <Stat
            label="Subscription ARPU"
            value={data.subscriptionArpuDisplay}
            hint="MRR ÷ paying subscribers."
          />
          <Stat
            label="Per trading shop"
            value={data.platformPerTradingShopDisplay}
            hint="MRR + commission ÷ verified shops. Counts both, because a free shop pays commission and a subscriber pays a subscription."
          />
          <Stat
            label="Commission per subscriber"
            value={data.commissionPerSubscriberDisplay}
            hint="The commission the programme is giving up, per subscriber."
            icon={<ArrowDownRight className="size-4" aria-hidden />}
            tone={data.commissionPerSubscriberMinor > 0 ? 'warn' : 'plain'}
          />
        </div>
      </section>

      {/* --- the trend */}
      <section className="space-y-3">
        <div className="flex items-center justify-between gap-3">
          <h2 className="text-sm font-semibold uppercase tracking-wide text-muted-foreground">
            MRR by month
          </h2>
          <select
            value={months}
            onChange={(e) => setMonths(Number(e.target.value))}
            aria-label="How many months to show"
            className="rounded-md border bg-background px-2 py-1 text-sm"
          >
            {[3, 6, 12].map((n) => (
              <option key={n} value={n}>
                Last {n} months
              </option>
            ))}
          </select>
        </div>
        <TrendChart data={data} months={months} />
      </section>
    </div>
  );
}

/** One figure, with the sentence that says what it counts. */
function Stat({
  label,
  value,
  hint,
  icon,
  tone = 'plain',
}: {
  label: string;
  value: string;
  hint: string;
  icon?: React.ReactNode;
  tone?: 'plain' | 'warn';
}) {
  return (
    <Card className={cn(tone === 'warn' && 'border-warning/40')}>
      <CardContent className="p-4">
        <p className="flex items-center gap-1.5 text-xs font-medium uppercase tracking-wide text-muted-foreground">
          {icon}
          {label}
        </p>
        <p className="mt-1 text-xl font-semibold tabular-nums">{value}</p>
        <p className="mt-1 text-xs text-muted-foreground">{hint}</p>
      </CardContent>
    </Card>
  );
}

/**
 * Six bars of one series, drawn by hand.
 *
 * <p>A charting library would be a dependency and several hundred kilobytes to draw six rectangles.
 * The one thing worth handling properly is the empty case: a bar chart of nothing is a set of zero
 * lines that reads as "no data" only if you already know.
 */
/** The bar area's height in pixels, matching the `h-40` on the chart container. */
const CHART_PX = 160;

function TrendChart({ data, months }: { data: RevenueSummary; months: number }) {
  const points = data.trend.slice(-months);
  const peak = Math.max(...points.map((p) => p.mrrMinor), 0);
  const empty = points.length === 0 || peak === 0;

  return (
    <Card>
      <CardHeader>
        <CardTitle>Monthly recurring revenue</CardTitle>
        <CardDescription>
          Reconstructed from the subscription windows that were live in each month, so the history is
          right for the whole series rather than from the day this dashboard was built.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {empty ? (
          <div className="flex flex-col items-center justify-center gap-2 py-10 text-center">
            <AlertTriangle className="size-6 text-muted-foreground" aria-hidden />
            <p className="text-sm font-medium">No subscription revenue yet</p>
            <p className="max-w-md text-sm text-muted-foreground">
              Every month here is zero because no shop has an active paid plan. MRR appears as soon
              as the first subscription is approved.
            </p>
          </div>
        ) : (
          <>
            {/* 
              Heights are PIXELS, not percentages.

              <p>`height: N%` resolves against the parent's height, and the parent's height here is
              indefinite: a `flex items-end` container sizes its children to content, so each column
              collapses to the height of its own label and the bar's percentage is taken against
              zero. The result is an axis with month labels, a correct "Peak Rs. 2,000.00" caption
              above it, and no bars at all — which is what the first screenshot of this page showed.

              <p>A test that counted `[role="img"] > div` reported six bars, because those are the
              *columns*. Counting the bars is what caught it.
            */}
            <div
              className="flex h-40 items-stretch gap-2"
              role="img"
              aria-label={`Monthly recurring revenue for the last ${points.length} months, peaking at ${
                points.find((p) => p.mrrMinor === peak)?.mrrDisplay ?? ''
              }`}
            >
              {points.map((point) => {
                // A zero month still gets a 2px stub, so "no revenue" is visibly a bar rather than a
                // missing one — an absent bar reads as missing data.
                const barHeight =
                  point.mrrMinor === 0 ? 2 : Math.max(Math.round((point.mrrMinor / peak) * (CHART_PX - 4)), 3);

                return (
                  <div key={point.month} className="flex flex-1 flex-col justify-end gap-1">
                    <div
                      data-testid="mrr-bar"
                      className={cn(
                        'w-full rounded-t',
                        point.mrrMinor === 0
                          ? 'bg-muted'
                          : 'bg-primary/70 transition-colors hover:bg-primary',
                      )}
                      style={{ height: `${barHeight}px` }}
                      title={`${point.month}: ${point.mrrDisplay} from ${point.subscribers} subscriber(s)`}
                    />
                    <span className="text-center text-[10px] tabular-nums text-muted-foreground">
                      {point.month.slice(5)}
                    </span>
                  </div>
                );
              })}
            </div>

            <div className="mt-3 flex flex-wrap gap-x-6 gap-y-1 text-xs text-muted-foreground">
              <span>Peak {points.find((p) => p.mrrMinor === peak)?.mrrDisplay}</span>
              <span>
                Latest{' '}
                {points[points.length - 1]?.mrrDisplay} from{' '}
                {points[points.length - 1]?.subscribers ?? 0} subscriber(s)
              </span>
            </div>
          </>
        )}
      </CardContent>
    </Card>
  );
}
