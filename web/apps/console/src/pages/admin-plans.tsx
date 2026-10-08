import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Ban, Plus, ShieldAlert } from 'lucide-react';

import { authorizedFetch, postJson } from '@store-app/auth';
import type { AuthAdapter } from '@store-app/auth';
import type { PlanDetail } from '@store-app/api-client';
import { DataTable, type DataTableColumn } from '@store-app/ui';
import {
  Badge,
  Button,
  Card,
  CardContent,
  Checkbox,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Label,
  Skeleton,
  Textarea,
} from '@store-app/ui';

import { featureLabel } from '../lib/entitlements';
import { minorToInput, paisaToRupees, readProblem, rupeesToPaisa } from '../lib/format';

/**
 * Plan management (Phase 6).
 *
 * <p><b>The feature list comes from the server</b> (`GET /admin/plans/features`), so this screen
 * cannot offer a key the backend would reject. The alternative — hardcoding the list here — is how
 * an entitlement gets added to the Java enum and quietly becomes unsellable because the admin UI has
 * never heard of it.
 *
 * <p><b>"Withdraw", never "Delete".</b> A cancelled subscriber's history must still be able to name
 * the plan they were on, and deleting the row cascades away the entitlements it granted. The button
 * says what it does.
 *
 * <p><b>The live-plan refusal is shown, not swallowed.</b> Editing a plan a subscriber holds is
 * refused server-side, and the reason — "it would not reach them" — is the most useful sentence on
 * this screen. Replacing it with "could not save" would send an admin looking for a syntax error.
 */
export function AdminPlans({ auth }: { auth: AuthAdapter }) {
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState<PlanDetail | 'new' | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const plans = useQuery({
    queryKey: ['admin', 'plans', page],
    queryFn: async () => {
      const response = await authorizedFetch(
        auth,
        `/api/v1/admin/plans?page=${page}&size=50`,
      );
      if (!response.ok) throw new Error(await readProblem(response));
      return (await response.json()) as {
        content: PlanDetail[];
        page: number;
        size: number;
        totalElements: number;
        totalPages: number;
      };
    },
  });

  const features = useQuery({
    queryKey: ['admin', 'plan-features'],
    queryFn: async () => {
      const response = await authorizedFetch(auth, '/api/v1/admin/plans/features');
      if (!response.ok) throw new Error(await readProblem(response));
      return (await response.json()) as string[];
    },
  });

  const withdraw = useMutation({
    mutationFn: async (planId: string) => {
      const response = await authorizedFetch(auth, `/api/v1/admin/plans/${planId}`, {
        method: 'DELETE',
      });
      if (!response.ok) throw new Error(await readProblem(response));
      return (await response.json()) as PlanDetail;
    },
    onSuccess: (plan) => {
      setNotice(`${plan.name} is no longer on sale. Existing subscribers keep it.`);
      void queryClient.invalidateQueries({ queryKey: ['admin', 'plans'] });
    },
    onError: (e: Error) => setNotice(e.message),
  });

  const columns: DataTableColumn<PlanDetail>[] = useMemo(
    () => [
      {
        key: 'name',
        header: 'Plan',
        render: (plan) => (
          <div className="min-w-0">
            <p className="truncate font-medium">
              {plan.name}
              {plan.activeSubscribers > 0 ? (
                <span className="ml-2 text-xs font-normal text-muted-foreground">
                  {plan.activeSubscribers} subscriber{plan.activeSubscribers === 1 ? '' : 's'}
                </span>
              ) : null}
            </p>
            <p className="font-mono text-xs text-muted-foreground">{plan.code}</p>
          </div>
        ),
      },
      {
        key: 'price',
        header: 'Price',
        render: (plan) => (
          <div>
            <p className="font-medium tabular-nums">{plan.priceDisplay}</p>
            {plan.billingPeriod === 'ANNUAL' ? (
              // Shown because MRR divides it, and an admin checking the dashboard's arithmetic
              // needs to be able to confirm the division rather than trust it.
              <p className="text-xs text-muted-foreground">
                counts {paisaToRupees(plan.monthlyEquivalentMinor)}/mo toward MRR
              </p>
            ) : null}
          </div>
        ),
      },
      {
        key: 'grants',
        header: 'Grants',
        render: (plan) =>
          plan.entitlements.filter((e) => e.enabled).length === 0 ? (
            <span className="text-sm text-muted-foreground">Nothing</span>
          ) : (
            <div className="flex flex-wrap gap-1">
              {plan.entitlements
                .filter((e) => e.enabled)
                .slice(0, 4)
                .map((e) => (
                  <Badge key={e.feature} variant="outline" className="text-xs">
                    {e.limit === null ? featureLabel(e.feature) : `${e.limit}× ${featureLabel(e.feature)}`}
                  </Badge>
                ))}
              {plan.entitlements.filter((e) => e.enabled).length > 4 ? (
                <Badge variant="outline" className="text-xs">
                  +{plan.entitlements.filter((e) => e.enabled).length - 4}
                </Badge>
              ) : null}
            </div>
          ),
      },
      {
        key: 'status',
        header: 'Status',
        render: (plan) =>
          plan.isActive ? (
            <Badge className="bg-emerald-50 text-emerald-700">On sale</Badge>
          ) : (
            <Badge className="bg-muted text-muted-foreground">Withdrawn</Badge>
          ),
      },
      {
        key: 'actions',
        header: '',
        headerClassName: 'text-right',
        className: 'text-right',
        render: (plan) => (
          <div className="flex justify-end gap-1">
            <Button size="sm" variant="ghost" onClick={() => setEditing(plan)}>
              Edit
            </Button>
            {plan.isActive ? (
              <Button
                size="sm"
                variant="ghost"
                disabled={withdraw.isPending}
                onClick={() => withdraw.mutate(plan.id)}
              >
                <Ban className="mr-1 size-3.5" aria-hidden />
                Withdraw
              </Button>
            ) : null}
          </div>
        ),
      },
    ],
    [withdraw.isPending],
  );

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">Plans</h1>
          <p className="text-muted-foreground">
            What a shop can buy, and what each plan unlocks. Withdrawing stops sales; it never
            deletes.
          </p>
        </div>
        <Button onClick={() => setEditing('new')} disabled={!features.data}>
          <Plus className="mr-1.5 size-4" aria-hidden />
          New plan
        </Button>
      </header>

      {notice ? (
        <p role="status" className="rounded-md border bg-muted/40 p-3 text-sm">
          {notice}
        </p>
      ) : null}

      {plans.isLoading ? (
        <Skeleton className="h-64 rounded-xl" />
      ) : plans.error ? (
        <Card>
          <CardContent className="p-10 text-center">
            <p className="font-medium">Could not load plans</p>
            <p className="mt-1 text-sm text-muted-foreground">{plans.error.message}</p>
          </CardContent>
        </Card>
      ) : (
        <DataTable
          columns={columns}
          rows={plans.data?.content ?? []}
          rowKey={(plan) => plan.id}
          meta={plans.data ? { ...plans.data } : undefined}
          onPageChange={setPage}
          empty={
            <p className="text-sm text-muted-foreground">
              No plans yet. The free tier is seeded by migration, so there should be at least one.
            </p>
          }
        />
      )}

      {editing ? (
        <PlanDialog
          auth={auth}
          plan={editing === 'new' ? null : editing}
          features={features.data ?? []}
          onClose={() => setEditing(null)}
          onSaved={(message) => {
            setEditing(null);
            setNotice(message);
            void queryClient.invalidateQueries({ queryKey: ['admin', 'plans'] });
          }}
        />
      ) : null}
    </div>
  );
}

// -------------------------------------------------------------------- the dialog

/** One editable entitlement row. */
interface GrantDraft {
  feature: string;
  enabled: boolean;
  limit: string;
}

function PlanDialog({
  auth,
  plan,
  features,
  onClose,
  onSaved,
}: {
  auth: AuthAdapter;
  plan: PlanDetail | null;
  features: string[];
  onClose: () => void;
  onSaved: (message: string) => void;
}) {
  const [code, setCode] = useState(plan?.code ?? '');
  const [name, setName] = useState(plan?.name ?? '');
  const [description, setDescription] = useState(plan?.description ?? '');
  const [period, setPeriod] = useState(plan?.billingPeriod ?? 'MONTHLY');
  const [price, setPrice] = useState(plan ? minorToInput(plan.priceMinor) : '');
  const [durationDays, setDurationDays] = useState(String(plan?.durationDays ?? 31));
  const [sortOrder, setSortOrder] = useState(String(plan?.sortOrder ?? 0));
  const [grants, setGrants] = useState<GrantDraft[]>(
    features.map((feature) => {
      const existing = plan?.entitlements.find((e) => e.feature === feature);
      return {
        feature,
        enabled: existing?.enabled ?? false,
        limit: existing?.limit === null || existing?.limit === undefined ? '' : String(existing.limit),
      };
    }),
  );
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const isFree = period === 'NONE';

  useEffect(() => {
    if (!saving) return;
  }, [saving]);

  const toggle = (feature: string, enabled: boolean) => {
    setGrants((current) =>
      current.map((g) => (g.feature === feature ? { ...g, enabled } : g)),
    );
  };

  const setLimit = (feature: string, limit: string) => {
    setGrants((current) =>
      current.map((g) => (g.feature === feature ? { ...g, limit } : g)),
    );
  };

  const save = async () => {
    setSaving(true);
    setError(null);
    try {
      const body = {
        code,
        name,
        description: description || null,
        billingPeriod: period,
        priceMinor: isFree ? 0 : rupeesToPaisa(price),
        durationDays: isFree ? null : Number(durationDays),
        sortOrder: Number(sortOrder),
        entitlements: grants.map((g) => ({
          feature: g.feature,
          enabled: g.enabled,
          limit: g.limit === '' ? null : Number(g.limit),
        })),
      };

      const response = plan
        ? await postJson(auth, `/api/v1/admin/plans/${plan.id}`, body, 'PUT')
        : await postJson(auth, '/api/v1/admin/plans', body, 'POST');

      if (!response.ok) throw new Error(await readProblem(response));
      const saved = (await response.json()) as PlanDetail;
      onSaved(plan ? `${saved.name} updated.` : `${saved.name} created and on sale.`);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not save that plan');
    } finally {
      setSaving(false);
    }
  };

  const missing = [
    !code.trim() && 'a code',
    !name.trim() && 'a name',
    !isFree && !price.trim() && 'a price',
    !isFree && (!durationDays || Number(durationDays) <= 0) && 'a duration',
  ].filter(Boolean);

  return (
    <Dialog open onOpenChange={(open) => !open && !saving && onClose()}>
      <DialogContent className="max-h-[88vh] max-w-2xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{plan ? `Edit ${plan.name}` : 'New plan'}</DialogTitle>
          <DialogDescription>
            {plan && plan.activeSubscribers > 0
              ? `${plan.activeSubscribers} active subscriber(s). Changing the price, duration or what it grants will be refused — those are the terms they bought. Renaming it is fine.`
              : 'A paid plan must say how many days it lasts. Without that, a subscriber’s commission waiver would never end.'}
          </DialogDescription>
        </DialogHeader>

        {plan && plan.activeSubscribers > 0 ? (
          <div className="flex items-start gap-2 rounded-md border border-amber-300 bg-amber-50 p-3 text-sm">
            <ShieldAlert className="mt-0.5 size-4 shrink-0 text-amber-700" aria-hidden />
            <p>
              Display fields can be edited. The price, the duration and the feature list cannot — and
              the server will refuse, which is better than an edit that appears to save and silently
              does not reach anybody.
            </p>
          </div>
        ) : null}

        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Code" id="p-code" required value={code} onChange={setCode}>
            <p className="mt-1 text-xs text-muted-foreground">
              Stable and never shown alone — it appears in bank references.
            </p>
          </Field>

          <Field label="Name" id="p-name" required value={name} onChange={setName} />

          <div className="sm:col-span-2">
            <Field label="Description" id="p-desc" value={description} onChange={setDescription}>
              <Textarea
                id="p-desc"
                rows={2}
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="What this plan is for, in one sentence"
              />
            </Field>
          </div>

          <div>
            <Label htmlFor="p-period">Billing period</Label>
            <select
              id="p-period"
              value={period}
              onChange={(e) => setPeriod(e.target.value as typeof period)}
              className="mt-1 block w-full rounded-md border bg-background px-3 py-2 text-sm"
            >
              <option value="MONTHLY">Monthly</option>
              <option value="ANNUAL">Annual</option>
              <option value="NONE">None — free plan</option>
            </select>
          </div>

          <Field
            label="Price"
            id="p-price"
            required={!isFree}
            value={isFree ? '' : price}
            onChange={setPrice}
            placeholder="2000"
            hint={isFree ? 'A free plan cannot have a price.' : 'Rupees. Entered as 2000 for Rs. 2,000.'}
          />

          {!isFree ? (
            <Field
              label="Duration (days)"
              id="p-days"
              required
              value={durationDays}
              onChange={setDurationDays}
              hint="31 for monthly, 366 for annual."
            />
          ) : null}

          <Field
            label="Sort order"
            id="p-sort"
            value={sortOrder}
            onChange={setSortOrder}
            hint="Lower shows first on the pricing screen."
          />
        </div>

        <div className="space-y-2">
          <Label>What this plan grants</Label>
          <ul className="divide-y rounded-md border">
            {grants.map((grant) => (
              <li key={grant.feature} className="flex items-center gap-3 p-3">
                <Checkbox
                  id={`g-${grant.feature}`}
                  checked={grant.enabled}
                  onCheckedChange={(checked) => toggle(grant.feature, checked === true)}
                  aria-label={`Grant ${featureLabel(grant.feature)}`}
                />
                <Label htmlFor={`g-${grant.feature}`} className="flex-1 font-normal">
                  {featureLabel(grant.feature)}
                  <span className="ml-2 font-mono text-xs text-muted-foreground">
                    {grant.feature}
                  </span>
                </Label>
                <Input
                  value={grant.limit}
                  onChange={(e) => setLimit(grant.feature, e.target.value)}
                  placeholder="No limit"
                  className="w-32"
                  aria-label={`Limit for ${featureLabel(grant.feature)}`}
                  disabled={!grant.enabled || !LIMITED_FEATURES.includes(grant.feature)}
                />
              </li>
            ))}
          </ul>
          <p className="text-xs text-muted-foreground">
            Only features with a cap take a number. A switch that also has a limit would be a
            contradiction, so the server refuses it.
          </p>
        </div>

        {missing.length > 0 ? (
          <p className="rounded-md bg-amber-50 px-3 py-2 text-sm text-amber-800">
            Still needed: {missing.join(', ')}.
          </p>
        ) : null}
        {error ? (
          <p role="alert" className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
            {error}
          </p>
        ) : null}

        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={saving}>
            Cancel
          </Button>
          <Button onClick={() => void save()} disabled={saving || missing.length > 0}>
            {saving ? 'Saving…' : plan ? 'Save changes' : 'Create plan'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/**
 * The features that take a cap.
 *
 * <p>Only `max_products` is countable today. The server enforces this too — a switch with a limit is
 * refused — but the input is disabled rather than left to fail on save, because a field that can
 * only be wrong is a field that teaches the admin it does not work.
 */
const LIMITED_FEATURES = ['max_products'];

function Field({
  label,
  id,
  value,
  onChange,
  required,
  hint,
  placeholder,
  children,
}: {
  label: string;
  id: string;
  value: string;
  onChange: (value: string) => void;
  required?: boolean;
  hint?: string;
  placeholder?: string;
  children?: React.ReactNode;
}) {
  return (
    <div>
      <Label htmlFor={id} className="flex items-center gap-1">
        {label}
        {required ? (
          <span className="text-destructive" aria-hidden>
            *
          </span>
        ) : null}
      </Label>
      {children ?? (
        <Input
          id={id}
          className="mt-1"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
        />
      )}
      {hint ? <p className="mt-1 text-xs text-muted-foreground">{hint}</p> : null}
    </div>
  );
}
