import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AlertTriangle,
  BadgeCheck,
  Check,
  CircleDollarSign,
  Clock,
  History,
  Sparkles,
  X,
} from 'lucide-react';
import { Link } from 'react-router-dom';

import { authorizedFetch, postJson } from '@store-app/auth';
import type { AuthAdapter } from '@store-app/auth';
import type {
  FeatureGrant,
  MySubscription,
  Plan,
  RequestResult,
  SubscriptionHistoryEntry,
} from '@store-app/api-client';
import {
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Input,
  Label,
  Skeleton,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  Textarea,
} from '@store-app/ui';
import { cn } from '@store-app/ui';

import { describeGrant, featureLabel } from '../lib/entitlements';
import { formatDateTime, readProblem } from '../lib/format';

/**
 * The shop's subscription screen (Phase 6).
 *
 * <p><b>Buying is two steps and this screen shows both.</b> A plan request queues intent and grants
 * nothing; the shop then transfers the money and submits the top-up; an admin approves it. That is
 * D7's manual model, and a single "Subscribe" button would imply a charge that has not happened.
 * So after requesting, the screen *stays* and shows the amount and the reference to quote — the
 * moment an owner most needs those is the moment they are off to a bank.
 *
 * <p><b>What it does not do is re-derive anything.</b> `paysCommission` comes from the server
 * because that is the flag the order-accept path will use; the days remaining come from the server
 * because they depend on a window the console cannot see. The console converts money once, at the
 * boundary, and never decides what a plan costs or what a limit is.
 */
export function ShopSubscription({ auth }: { auth: AuthAdapter }) {
  const queryClient = useQueryClient();
  // Set only so the step appears *immediately* after a request. On any later load it comes from
  // `me.history` instead — see `pending`. Deriving it purely from local state meant a refresh lost
  // the payment step and left a queued request with no way to pay for it.
  const [justRequested, setJustRequested] = useState<RequestResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const state = useQuery({
    queryKey: ['subscription', 'me'],
    queryFn: async () => {
      const response = await authorizedFetch(auth, '/api/v1/subscriptions/me');
      if (!response.ok) throw new Error(await readProblem(response));
      return (await response.json()) as MySubscription;
    },
  });

  const plans = useQuery({
    queryKey: ['subscription', 'plans'],
    queryFn: async () => {
      const response = await authorizedFetch(auth, '/api/v1/subscriptions/plans');
      if (!response.ok) throw new Error(await readProblem(response));
      return (await response.json()) as Plan[];
    },
  });

  const requestPlan = useMutation({
    mutationFn: async (planId: string) => {
      const response = await postJson(auth, '/api/v1/subscriptions/requests', { planId });
      if (!response.ok) throw new Error(await readProblem(response));
      return (await response.json()) as RequestResult;
    },
    onSuccess: (result) => {
      setJustRequested(result);
      setError(null);
      void queryClient.invalidateQueries({ queryKey: ['subscription'] });
    },
    onError: (e: Error) => setError(e.message),
  });

  const cancel = useMutation({
    mutationFn: async (subscriptionId: string) => {
      const response = await postJson(
        auth,
        `/api/v1/subscriptions/${subscriptionId}/cancel`,
        {},
      );
      if (!response.ok) throw new Error(await readProblem(response));
      return response.json();
    },
    onSuccess: () => {
      setError(null);
      void queryClient.invalidateQueries({ queryKey: ['subscription'] });
    },
    onError: (e: Error) => setError(e.message),
  });

  if (state.isLoading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-8 w-56" />
        <Skeleton className="h-40 rounded-xl" />
        <Skeleton className="h-64 rounded-xl" />
      </div>
    );
  }

  if (state.error || !state.data) {
    return (
      <Card>
        <CardContent className="p-10 text-center">
          <p className="font-medium">Could not load your subscription</p>
          <p className="mt-1 text-sm text-muted-foreground">{state.error?.message}</p>
        </CardContent>
      </Card>
    );
  }

  const me = state.data;
  const current = me.current;

  // The newest queued request, if there is one. This is what makes the payment step survivable.
  const queued = me.history.find((row) => row.status === 'PENDING_PAYMENT');
  const step: PaymentDetails | null = justRequested
    ? {
        subscriptionId: justRequested.subscriptionId,
        planName: justRequested.planName,
        amountDisplay: justRequested.amountDisplay,
        reference: justRequested.reference,
      }
    : queued
      ? {
          subscriptionId: queued.subscriptionId,
          planName: queued.planName,
          amountDisplay: queued.amountDisplay,
          reference: queued.reference ?? '',
        }
      : null;

  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-2xl font-semibold">Plan &amp; billing</h1>
        <p className="text-muted-foreground">
          What your shop can do, what it costs, and what the platform takes.
        </p>
      </header>

      {error ? (
        <div
          role="alert"
          className="flex items-start gap-2 rounded-lg border border-destructive/40 bg-destructive/10 p-3 text-sm"
        >
          <AlertTriangle className="mt-0.5 size-4 shrink-0 text-destructive" aria-hidden />
          <span>{error}</span>
        </div>
      ) : null}

      <CurrentPlanCard
        me={me}
        busy={cancel.isPending}
        onCancel={() => current && cancel.mutate(current.subscriptionId)}
      />

      {step ? <PaymentStep step={step} auth={auth} onDone={() => setJustRequested(null)} /> : null}

      <FeaturesCard features={me.features} />

      <PlansCard
        plans={plans.data ?? []}
        currentCode={current?.planCode ?? null}
        busy={requestPlan.isPending}
        onRequest={(id) => requestPlan.mutate(id)}
      />

      <HistoryCard history={me.history} />
    </div>
  );
}

// -------------------------------------------------------------------- current plan

/**
 * The plan the shop is on, and what cancelling it would and would not do.
 *
 * <p>The wording on the cancel button is the important part. It says "keep my plan until <date>"
 * rather than "Cancel", because that is exactly what happens — and a button labelled "Cancel" on a
 * subscription the owner has paid for reads as an immediate revocation, which is the one thing it
 * is not.
 */
function CurrentPlanCard({
  me,
  busy,
  onCancel,
}: {
  me: MySubscription;
  busy: boolean;
  onCancel: () => void;
}) {
  const { current } = me;

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <CardTitle>Your plan</CardTitle>
            <CardDescription>
              {current
                ? `Renews by manual top-up. ${current.daysRemaining} day${
                    current.daysRemaining === 1 ? '' : 's'
                  } left.`
                : 'You are on the free tier.'}
            </CardDescription>
          </div>
          <CommissionBadge paysCommission={me.paysCommission} />
        </div>
      </CardHeader>

      <CardContent className="space-y-4">
        {!current ? (
          <p className="text-sm text-muted-foreground">
            No subscription, so every accepted order is charged 5% commission. Choose a plan below
            to stop that and raise your product limit.
          </p>
        ) : (
          <>
            <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
              <span className="text-lg font-semibold">{current.planName}</span>
              {current.willNotRenew ? (
                <Badge className="bg-warning/10 text-warning">Will not renew</Badge>
              ) : null}
            </div>

            <dl className="grid gap-3 text-sm sm:grid-cols-3">
              <div>
                <dt className="text-muted-foreground">Started</dt>
                <dd>{formatDateTime(current.startedAt)}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">Ends</dt>
                <dd>{formatDateTime(current.expiresAt)}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">Commission</dt>
                <dd>{me.paysCommission ? '5% per accepted order' : 'None'}</dd>
              </div>
            </dl>

            <div className="rounded-md border bg-muted/40 p-3 text-sm">
              {current.willNotRenew ? (
                <p>
                  <strong>Not renewing.</strong> You keep {current.planName} and everything it
                  unlocks until {formatDateTime(current.expiresAt)}, then you return to the free
                  tier. Nothing has been refunded, and nothing will be — the period is already paid
                  for.
                </p>
              ) : (
                <p className="text-muted-foreground">
                  To stop renewing, use the button below. Your plan and its features stay on until{' '}
                  {formatDateTime(current.expiresAt)}.
                </p>
              )}
            </div>

            <div>
              <Button
                variant="outline"
                disabled={busy || current.willNotRenew}
                onClick={onCancel}
              >
                <X className="mr-1.5 size-4" aria-hidden />
                {current.willNotRenew
                  ? 'Already not renewing'
                  : `Keep my plan until ${formatDateTime(current.expiresAt)}`}
              </Button>
            </div>
          </>
        )}
      </CardContent>
    </Card>
  );
}

/**
 * Whether this shop pays commission, and what it costs if it does.
 *
 * <p>Shown on the billing screen rather than buried in a report, because the number is the single
 * biggest argument for upgrading and an owner should not have to go looking for it.
 */
function CommissionBadge({ paysCommission }: { paysCommission: boolean }) {
  if (paysCommission) {
    return (
      <Badge className="bg-warning/10 text-warning" title="Charged on each order you accept">
        <CircleDollarSign className="mr-1 size-3.5" aria-hidden />
        5% commission
      </Badge>
    );
  }
  return (
    <Badge className="bg-success/10 text-success">
      <BadgeCheck className="mr-1 size-3.5" aria-hidden />
      No commission
    </Badge>
  );
}

// -------------------------------------------------------------------- payment step

/**
 * Step two of buying: proof of payment.
 *
 * <p>Appears only after a request exists, and quotes the server's own reference rather than
 * inventing one — an admin matching a bank statement to a request can do it by eye, which is the
 * entire manual process (D7) resting on that string.
 */
interface PaymentDetails {
  subscriptionId: string;
  planName: string;
  amountDisplay: string;
  reference: string;
}

function PaymentStep({
  step,
  auth,
  onDone,
}: {
  step: PaymentDetails;
  auth: AuthAdapter;
  onDone: () => void;
}) {
  const queryClient = useQueryClient();
  const [method, setMethod] = useState<'ESAWA' | 'BANK_TRANSFER'>('BANK_TRANSFER');
  const [reference, setReference] = useState(step.reference);
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(null);

  const submit = useMutation({
    mutationFn: async () => {
      const response = await postJson(
        auth,
        `/api/v1/subscriptions/requests/${step.subscriptionId}/topup`,
        { method, reference, note: note || null },
      );
      if (!response.ok) throw new Error(await readProblem(response));
      return response.json();
    },
    onSuccess: () => {
      setError(null);
      onDone();
      void queryClient.invalidateQueries({ queryKey: ['subscription'] });
    },
    onError: (e: Error) => setError(e.message),
  });

  return (
    <Card className="border-warning/40">
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Clock className="size-4" aria-hidden />
          Transfer the money, then tell us
        </CardTitle>
        <CardDescription>
          Your request is queued. Nothing has been charged and no features have changed — an admin
          approves it after the money arrives.
        </CardDescription>
      </CardHeader>

      <CardContent className="space-y-4">
        <div className="rounded-md border bg-muted/40 p-3 text-sm">
          <p className="font-medium">
            Send {step.amountDisplay} for {step.planName}
          </p>
          <p className="mt-1 text-muted-foreground">
            Quote this reference so it can be matched to your transfer:
          </p>
          <p className="mt-1 font-mono text-sm font-semibold">{step.reference}</p>
        </div>

        <div className="space-y-2">
          <Label htmlFor="sub-method">How did you pay?</Label>
          <select
            id="sub-method"
            value={method}
            onChange={(e) => setMethod(e.target.value as 'ESAWA' | 'BANK_TRANSFER')}
            className="block w-full rounded-md border bg-background px-3 py-2 text-sm"
          >
            <option value="BANK_TRANSFER">Bank transfer</option>
            <option value="ESAWA">eSewa</option>
          </select>
        </div>

        <div className="space-y-2">
          <Label htmlFor="sub-ref">Transfer reference</Label>
          <Input
            id="sub-ref"
            value={reference}
            onChange={(e) => setReference(e.target.value)}
            placeholder="The reference from your bank or eSewa receipt"
          />
          <p className="text-xs text-muted-foreground">
            Pre-filled from your plan. Change it only if your bank gave you a different one.
          </p>
        </div>

        <div className="space-y-2">
          <Label htmlFor="sub-note">Anything the admin should know? (optional)</Label>
          <Textarea
            id="sub-note"
            value={note}
            rows={2}
            onChange={(e) => setNote(e.target.value)}
            placeholder="e.g. transferred from my wife’s account"
          />
        </div>

        {error ? (
          <p role="alert" className="rounded-md bg-destructive/10 p-3 text-sm text-destructive">
            {error}
          </p>
        ) : null}

        <Button onClick={() => submit.mutate()} disabled={submit.isPending}>
          {submit.isPending ? 'Submitting…' : 'I have paid — submit for approval'}
        </Button>
      </CardContent>
    </Card>
  );
}

// -------------------------------------------------------------------- features

/**
 * What the shop can do, and — just as important — why it cannot do something else.
 *
 * <p>Lists every feature rather than only the granted ones. An owner who discovers that paid
 * templates are missing, with no list to explain it, concludes the product is broken; one who sees
 * "Paid templates — not in your plan" with a link to pricing knows exactly what to do.
 */
function FeaturesCard({ features }: { features: FeatureGrant[] }) {
  const granted = features.filter((f) => f.enabled);
  const missing = features.filter((f) => !f.enabled);

  return (
    <Card>
      <CardHeader>
        <CardTitle>What your plan includes</CardTitle>
        <CardDescription>
          {granted.length} of {features.length} features. Limits come from your plan; anything not
          listed here is on the free tier.
        </CardDescription>
      </CardHeader>

      <CardContent className="space-y-4">
        <ul className="grid gap-2 sm:grid-cols-2">
          {granted.map((feature) => (
            <li
              key={feature.key}
              className="flex items-start gap-2 rounded-md border p-3 text-sm"
            >
              <Check className="mt-0.5 size-4 shrink-0 text-success" aria-hidden />
              <div className="min-w-0">
                <p className="font-medium">{describeGrant(feature.key, feature.limit)}</p>
                {feature.materialised ? null : (
                  <p className="text-xs text-muted-foreground">From your free tier</p>
                )}
              </div>
            </li>
          ))}
        </ul>

        {missing.length > 0 ? (
          <div className="rounded-md border border-dashed p-3">
            <p className="mb-2 text-sm font-medium text-muted-foreground">
              Not in your plan
            </p>
            <ul className="flex flex-wrap gap-2">
              {missing.map((feature) => (
                <li key={feature.key}>
                  <Badge variant="outline" className="gap-1">
                    <X className="size-3" aria-hidden />
                    {featureLabel(feature.key)}
                  </Badge>
                </li>
              ))}
            </ul>
            <Button asChild size="sm" variant="ghost" className="mt-3 px-0">
              <Link to="#plans">See what a paid plan adds</Link>
            </Button>
          </div>
        ) : null}
      </CardContent>
    </Card>
  );
}

// -------------------------------------------------------------------- pricing

/**
 * The pricing screen.
 *
 * <p>The current plan is shown but not selectable, and the free tier is not offered — the server
 * refuses a downgrade while a period is paid for, and offering a button that always 409s is worse
 * than not offering it. The reason is on the current-plan card instead, where someone actually
 * thinking about it will read it.
 */
function PlansCard({
  plans,
  currentCode,
  busy,
  onRequest,
}: {
  plans: Plan[];
  currentCode: string | null;
  busy: boolean;
  onRequest: (planId: string) => void;
}) {
  const paid = plans.filter((p) => p.billingPeriod !== 'NONE');

  return (
    <Card id="plans">
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Sparkles className="size-4" aria-hidden />
          Plans
        </CardTitle>
        <CardDescription>
          No automatic charging. You transfer the money, and an admin approves it.
        </CardDescription>
      </CardHeader>

      <CardContent>
        {paid.length === 0 ? (
          <p className="text-sm text-muted-foreground">No paid plans are on sale right now.</p>
        ) : (
          <ul className="grid gap-3 lg:grid-cols-2">
            {paid.map((plan) => {
              const isCurrent = plan.code === currentCode;
              return (
                <li key={plan.id} className="flex flex-col rounded-lg border p-4">
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <p className="font-semibold">{plan.name}</p>
                      {plan.description ? (
                        <p className="mt-0.5 text-sm text-muted-foreground">{plan.description}</p>
                      ) : null}
                    </div>
                    {isCurrent ? <Badge>Current</Badge> : null}
                  </div>

                  <p className="mt-3 text-lg font-semibold">{plan.priceDisplay}</p>
                  {plan.billingPeriod === 'ANNUAL' ? (
                    <p className="text-xs text-muted-foreground">
                      Counted as 1/12th per month on the platform&apos;s revenue figures.
                    </p>
                  ) : null}

                  <ul className="mt-3 flex-1 space-y-1">
                    {plan.grants.map((grant) => (
                      <li key={grant} className="flex items-start gap-1.5 text-sm">
                        <Check className="mt-0.5 size-3.5 shrink-0 text-success" aria-hidden />
                        <span>{grant}</span>
                      </li>
                    ))}
                  </ul>

                  <div className="mt-4">
                    <Button
                      className="w-full"
                      disabled={busy || isCurrent}
                      onClick={() => onRequest(plan.id)}
                    >
                      {isCurrent ? 'Your current plan' : 'Request this plan'}
                    </Button>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}

// -------------------------------------------------------------------- history

/**
 * Every subscription this shop has had.
 *
 * <p>Kept because "what happened to my subscription" is a question owners ask, and the answer
 * includes the cancelled and expired ones — the ones that are not visible anywhere else.
 */
function HistoryCard({ history }: { history: SubscriptionHistoryEntry[] }) {
  if (history.length === 0) {
    return (
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <History className="size-4" aria-hidden />
            History
          </CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground">
            No subscriptions yet. Your first one will appear here once it is approved.
          </p>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <History className="size-4" aria-hidden />
          History
        </CardTitle>
        <CardDescription>Every plan this shop has subscribed to.</CardDescription>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Plan</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>Started</TableHead>
              <TableHead>Ended</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {history.map((entry) => (
              <TableRow key={entry.subscriptionId}>
                <TableCell className="font-medium">{entry.planName}</TableCell>
                <TableCell>
                  <StatusBadge status={entry.status} />
                </TableCell>
                <TableCell className="text-muted-foreground">
                  {formatDateTime(entry.startedAt)}
                </TableCell>
                <TableCell className="text-muted-foreground">
                  {entry.cancelledAt
                    ? `Cancelled ${formatDateTime(entry.cancelledAt)}`
                    : formatDateTime(entry.expiresAt)}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}

/** The database enum on the left, what it means on the right. */
function StatusBadge({ status }: { status: string }) {
  const tone: Record<string, string> = {
    ACTIVE: 'bg-success/10 text-success',
    PENDING_PAYMENT: 'bg-warning/10 text-warning',
    EXPIRED: 'bg-muted text-muted-foreground',
    CANCELLED: 'bg-muted text-muted-foreground',
  };
  const label: Record<string, string> = {
    ACTIVE: 'Running',
    PENDING_PAYMENT: 'Awaiting payment',
    EXPIRED: 'Ran out',
    CANCELLED: 'Cancelled',
  };
  return (
    <Badge className={cn(tone[status] ?? 'bg-muted text-muted-foreground')}>
      {label[status] ?? status}
    </Badge>
  );
}
