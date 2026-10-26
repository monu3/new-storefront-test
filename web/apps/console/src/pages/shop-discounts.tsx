import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Percent, Plus, Tag, Truck, Wallet } from 'lucide-react';

import { authorizedFetch, postJson } from '@store-app/auth';
import type { AuthAdapter } from '@store-app/auth';
import type { Discount, DiscountDraft, DiscountType, OffsetPage } from '@store-app/api-client';
import {
  Badge,
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Label,
  Progress,
} from '@store-app/ui';
import { cn } from '@store-app/ui';

import { DataTable, type DataTableColumn } from '@store-app/ui';
import {
  formatDateTime,
  fromDateTimeInput,
  minorToInput,
  paisaToRupees,
  readProblem,
  rupeesToPaisa,
  toDateTimeInput,
} from '../lib/format';

/**
 * Discounts (Phase 4).
 *
 * <p>The arithmetic is not on this screen. `usableNow`, `notUsableReason` and `remainingUses` all
 * come from the server, and the value field's meaning changes with the type — a percentage is sent
 * as basis points, a fixed amount as paisa, and free shipping as zero. The console converts once,
 * at the boundary, and never re-derives what a discount is worth.
 *
 * <p><b>No delete button.</b> The server pauses instead, because deleting resets the usage counters
 * and a campaign redeployed next season would let everyone who already used it use it again. The UI
 * reflects that: "Pause", never "Delete".
 *
 * <p><b>Automatic discounts have no code.</b> They are applied without the customer doing anything,
 * which the form makes explicit rather than leaving an owner to wonder whether a blank code field
 * means "auto" or "broken".
 */

const TYPE_OPTIONS: Array<{ value: DiscountType; label: string; icon: typeof Tag; blurb: string }> = [
  {
    value: 'PERCENT',
    label: 'Percentage off',
    icon: Percent,
    blurb: 'A percentage of the subtotal. 10% off is entered as 10.',
  },
  {
    value: 'FIXED',
    label: 'Fixed amount off',
    icon: Wallet,
    blurb: 'A set amount taken off the subtotal.',
  },
  {
    value: 'FREE_SHIPPING',
    label: 'Free shipping',
    icon: Truck,
    blurb: 'Waives the delivery charge. Takes no amount.',
  },
];

export function ShopDiscounts({ auth }: { auth: AuthAdapter }) {
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState<Discount | null | 'new'>(null);

  const { data } = useQuery({
    queryKey: ['discounts', page],
    queryFn: async () => {
      const response = await authorizedFetch(
        auth,
        `/api/v1/shop/discounts?page=${page}&size=20`,
      );
      if (!response.ok) throw new Error(`Discounts request failed: ${response.status}`);
      return (await response.json()) as OffsetPage<Discount>;
    },
  });

  const toggle = useMutation({
    mutationFn: async (d: { id: string; resume: boolean }) => {
      const response = d.resume
        ? await postJson(auth, `/api/v1/shop/discounts/${d.id}/resume`, {}, 'PATCH')
        : await authorizedFetch(auth, `/api/v1/shop/discounts/${d.id}`, { method: 'DELETE' });
      if (!response.ok) throw new Error(await readProblem(response));
      return response.json();
    },
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['discounts'] }),
  });

  const columns: DataTableColumn<Discount>[] = [
    {
      key: 'name',
      header: 'Discount',
      render: (d) => (
        <div className="min-w-0">
          <p className="truncate font-medium">{d.name}</p>
          <p className="font-mono text-xs text-muted-foreground">
            {d.code ?? 'Automatic — applied without a code'}
          </p>
        </div>
      ),
    },
    {
      key: 'type',
      header: 'Type',
      render: (d) => {
        const option = TYPE_OPTIONS.find((o) => o.value === d.type);
        const Icon = option?.icon ?? Tag;
        return (
          <div className="flex items-center gap-1.5 text-sm">
            <Icon className="size-4 text-muted-foreground" aria-hidden />
            <span>{option?.label ?? d.type}</span>
          </div>
        );
      },
    },
    {
      key: 'value',
      header: 'Value',
      render: (d) => (
        <span className="whitespace-nowrap text-sm">
          {d.type === 'PERCENT'
            ? `${paisaToRupees(d.value).replace(/\.00$/, '')}%`
            : d.type === 'FIXED'
              ? `NRP ${paisaToRupees(d.value)}`
              : '—'}
        </span>
      ),
    },
    {
      key: 'conditions',
      header: 'Conditions',
      render: (d) => (
        <div className="text-xs text-muted-foreground">
          {d.minOrderMinor ? (
            <p>Min {`NRP ${paisaToRupees(d.minOrderMinor)}`}</p>
          ) : null}
          {d.maxDiscountMinor ? (
            <p>Max off {`NRP ${paisaToRupees(d.maxDiscountMinor)}`}</p>
          ) : null}
          {d.perCustomerLimit ? <p>{d.perCustomerLimit} per customer</p> : null}
          {!d.minOrderMinor && !d.maxDiscountMinor && !d.perCustomerLimit ? (
            <span>No minimum</span>
          ) : null}
        </div>
      ),
    },
    {
      key: 'window',
      header: 'Window',
      render: (d) => (
        <div className="text-xs text-muted-foreground">
          <p>{d.startsAt ? formatDateTime(d.startsAt) : 'Always on'}</p>
          <p>{d.endsAt ? `Until ${formatDateTime(d.endsAt)}` : 'No end date'}</p>
        </div>
      ),
    },
    {
      key: 'usage',
      header: 'Used',
      render: (d) => {
        if (d.usageLimit == null) {
          return <span className="text-xs text-muted-foreground">
            {d.usageUsed} / unlimited
          </span>;
        }
        const pct = Math.min(100, Math.round((d.usageUsed / d.usageLimit) * 100));
        return (
          <div className="w-24">
            <p className="text-xs">
              {d.usageUsed} / {d.usageLimit}
            </p>
            <Progress value={pct} className="mt-1 h-1.5" />
          </div>
        );
      },
    },
    {
      key: 'state',
      header: 'State',
      render: (d) => (
        <div className="space-y-1">
          {!d.active ? (
            <Badge variant="secondary">Paused</Badge>
          ) : d.usableNow ? (
            <Badge className="bg-success/10 text-success">Working</Badge>
          ) : (
            <div>
              <Badge className="bg-warning/10 text-warning">Not usable</Badge>
              {d.notUsableReason ? (
                <p className="mt-0.5 max-w-40 text-xs text-muted-foreground">
                  {d.notUsableReason}
                </p>
              ) : null}
            </div>
          )}
        </div>
      ),
    },
    {
      key: 'actions',
      header: '',
      className: 'text-right',
      render: (d) => (
        <div className="flex justify-end gap-1">
          <Button size="sm" variant="ghost" onClick={() => setEditing(d)}>
            Edit
          </Button>
          {d.active ? (
            <Button
              size="sm"
              variant="ghost"
              disabled={toggle.isPending}
              onClick={() => toggle.mutate({ id: d.id, resume: false })}
            >
              Pause
            </Button>
          ) : (
            <Button
              size="sm"
              variant="ghost"
              disabled={toggle.isPending}
              onClick={() => toggle.mutate({ id: d.id, resume: true })}
            >
              Resume
            </Button>
          )}
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold">Discounts</h2>
          <p className="text-sm text-muted-foreground">
            Codes customers type at checkout, and automatic discounts that apply on their own.
          </p>
        </div>
        <Button
          onClick={() => setEditing('new')}
          style={{ background: '#22c55e', color: '#fff' }}
          className="border-0"
        >
          <Plus className="mr-1.5 size-4" aria-hidden />
          New discount
        </Button>
      </div>

      <DataTable
        columns={columns}
        rows={data?.content ?? []}
        rowKey={(d) => d.id}
        meta={data}
        onPageChange={setPage}
        empty={
          <div className="p-8 text-center">
            <Tag className="mx-auto size-8 text-muted-foreground" aria-hidden />
            <p className="mt-3 font-medium">No discounts yet</p>
            <p className="mt-1 text-sm text-muted-foreground">
              Create a code for customers to type, or an automatic discount that applies to every
              basket.
            </p>
          </div>
        }
        data-testid="discounts-table"
      />

      {editing ? (
        <DiscountDialog
          auth={auth}
          discount={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
        />
      ) : null}
    </div>
  );
}

// ---------------------------------------------------------------------- dialog

function DiscountDialog({
  auth,
  discount,
  onClose,
}: {
  auth: AuthAdapter;
  discount: Discount | null;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState<DiscountDraft>(() => (discount ? toDraft(discount) : blank()));
  const [error, setError] = useState<string | null>(null);

  const set = (patch: Partial<DiscountDraft>) => setDraft((c) => ({ ...c, ...patch }));

  const save = useMutation({
    mutationFn: async () => {
      const response = discount
        ? await postJson(auth, `/api/v1/shop/discounts/${discount.id}`, draft, 'PUT')
        : await postJson(auth, '/api/v1/shop/discounts', draft);
      if (!response.ok) throw new Error(await readProblem(response));
      return response.json();
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['discounts'] });
      onClose();
    },
    onError: (e: Error) => setError(e.message),
  });

  const typeOption = TYPE_OPTIONS.find((o) => o.value === draft.type);
  const TypeIcon = typeOption?.icon ?? Tag;

  const freeShipping = draft.type === 'FREE_SHIPPING';

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[85vh] max-w-lg overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{discount ? 'Edit discount' : 'New discount'}</DialogTitle>
          <DialogDescription>
            {discount
              ? 'The code cannot be changed. Pause this discount and create another if you need a different code.'
              : 'A code customers type, or leave blank for an automatic discount.'}
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          <div>
            <Label htmlFor="d-name">Name</Label>
            <Input
              id="d-name"
              className="mt-1.5"
              value={draft.name}
              onChange={(e) => set({ name: e.target.value })}
              placeholder="e.g. Festival sale"
            />
            <p className="mt-1 text-xs text-muted-foreground">For you, not for customers.</p>
          </div>

          <div>
            <Label htmlFor="d-code">Code</Label>
            <Input
              id="d-code"
              className="mt-1.5 font-mono uppercase"
              value={draft.code ?? ''}
              onChange={(e) => set({ code: e.target.value })}
              placeholder="FESTIVAL20"
              disabled={Boolean(discount)}
            />
            <p className="mt-1 text-xs text-muted-foreground">
              {discount
                ? 'Codes cannot be changed once customers have seen them.'
                : 'Leave blank and it applies automatically, with nothing for the customer to type.'}
            </p>
          </div>

          <div>
            <Label>Type</Label>
            <div className="mt-1.5 grid gap-2 sm:grid-cols-3">
              {TYPE_OPTIONS.map((option) => {
                const Icon = option.icon;
                const active = draft.type === option.value;
                return (
                  <button
                    key={option.value}
                    type="button"
                    onClick={() =>
                      set({
                        type: option.value,
                        // Free shipping has no value; carrying the old one forward would be
                        // stored and then ignored.
                        value: option.value === 'FREE_SHIPPING' ? 0 : draft.value,
                      })
                    }
                    aria-pressed={active}
                    className={cn(
                      'flex flex-col items-start gap-1 rounded-lg border p-3 text-left text-xs transition-colors',
                      active
                        ? 'border-emerald-600 bg-success/10 text-emerald-900'
                        : 'hover:bg-muted',
                    )}
                  >
                    <Icon className="size-4" aria-hidden />
                    <span className="font-medium">{option.label}</span>
                  </button>
                );
              })}
            </div>
            <p className="mt-1.5 flex items-center gap-1 text-xs text-muted-foreground">
              <TypeIcon className="size-3.5" aria-hidden />
              {typeOption?.blurb}
            </p>
          </div>

          {!freeShipping ? (
            <div>
              <Label htmlFor="d-value">
                {draft.type === 'PERCENT' ? 'Percentage off' : 'Amount off (NPR)'}
              </Label>
              <Input
                id="d-value"
                className="mt-1.5"
                type="number"
                min="0"
                step={draft.type === 'PERCENT' ? '1' : '0.01'}
                max={draft.type === 'PERCENT' ? '100' : undefined}
                value={
                  draft.type === 'PERCENT' ? draft.value / 100 : minorToInput(draft.value)
                }
                onChange={(e) =>
                  set({
                    value:
                      draft.type === 'PERCENT'
                        ? Math.round(Number(e.target.value || 0) * 100)
                        : rupeesToPaisa(e.target.value),
                  })
                }
              />
              {draft.type === 'PERCENT' ? (
                <p className="mt-1 text-xs text-muted-foreground">
                  Stored as basis points so 10% is exactly 10%, never 0.1%.
                </p>
              ) : null}
            </div>
          ) : null}

          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <Label htmlFor="d-min">Minimum order (NPR)</Label>
              <Input
                id="d-min"
                className="mt-1.5"
                type="number"
                min="0"
                step="0.01"
                value={minorToInput(draft.minOrderMinor ?? 0)}
                onChange={(e) =>
                  set({ minOrderMinor: e.target.value === '' ? null : rupeesToPaisa(e.target.value) })
                }
              />
            </div>
            <div>
              <Label htmlFor="d-max">Maximum discount (NPR)</Label>
              <Input
                id="d-max"
                className="mt-1.5"
                type="number"
                min="0"
                step="0.01"
                value={minorToInput(draft.maxDiscountMinor ?? 0)}
                onChange={(e) =>
                  set({ maxDiscountMinor: e.target.value === '' ? null : rupeesToPaisa(e.target.value) })
                }
              />
            </div>
          </div>
          <p className="-mt-2 text-xs text-muted-foreground">
            Leave the maximum blank for no cap. It must be below the minimum, or no order qualifies.
          </p>

          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <Label htmlFor="d-usage">Total uses</Label>
              <Input
                id="d-usage"
                className="mt-1.5"
                type="number"
                min="0"
                step="1"
                value={draft.usageLimit ?? ''}
                onChange={(e) =>
                  set({ usageLimit: e.target.value === '' ? null : Number(e.target.value) })
                }
                placeholder="Unlimited"
              />
            </div>
            <div>
              <Label htmlFor="d-percust">Uses per customer</Label>
              <Input
                id="d-percust"
                className="mt-1.5"
                type="number"
                min="0"
                step="1"
                value={draft.perCustomerLimit ?? ''}
                onChange={(e) =>
                  set({
                    perCustomerLimit: e.target.value === '' ? null : Number(e.target.value),
                  })
                }
                placeholder="Unlimited"
              />
            </div>
          </div>

          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <Label htmlFor="d-start">Starts</Label>
              <Input
                id="d-start"
                className="mt-1.5"
                type="datetime-local"
                value={toDateTimeInput(draft.startsAt)}
                onChange={(e) => set({ startsAt: fromDateTimeInput(e.target.value) })}
              />
            </div>
            <div>
              <Label htmlFor="d-end">Ends</Label>
              <Input
                id="d-end"
                className="mt-1.5"
                type="datetime-local"
                value={toDateTimeInput(draft.endsAt)}
                onChange={(e) => set({ endsAt: fromDateTimeInput(e.target.value) })}
              />
            </div>
          </div>

          <div className="flex items-center justify-between rounded-lg border p-3">
            <div>
              <Label htmlFor="d-active" className="text-sm">
                Active
              </Label>
              <p className="text-xs text-muted-foreground">
                An inactive discount keeps its usage counts.
              </p>
            </div>
            <input
              id="d-active"
              type="checkbox"
              className="size-4"
              checked={draft.active}
              onChange={(e) => set({ active: e.target.checked })}
            />
          </div>

          {error ? (
            <p role="alert" className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
              {error}
            </p>
          ) : null}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={save.isPending}>
            Cancel
          </Button>
          <Button
            onClick={() => save.mutate()}
            disabled={save.isPending || draft.name.trim() === ''}
            style={{ background: '#22c55e', color: '#fff' }}
            className="border-0"
          >
            {save.isPending ? 'Saving…' : discount ? 'Save changes' : 'Create discount'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function blank(): DiscountDraft {
  return {
    code: null,
    name: '',
    type: 'PERCENT',
    value: 1000,
    minOrderMinor: null,
    maxDiscountMinor: null,
    usageLimit: null,
    perCustomerLimit: null,
    startsAt: null,
    endsAt: null,
    active: true,
  };
}

function toDraft(d: Discount): DiscountDraft {
  return {
    code: d.code,
    name: d.name,
    type: d.type,
    value: d.value,
    minOrderMinor: d.minOrderMinor,
    maxDiscountMinor: d.maxDiscountMinor,
    usageLimit: d.usageLimit,
    perCustomerLimit: d.perCustomerLimit,
    startsAt: d.startsAt,
    endsAt: d.endsAt,
    active: d.active,
  };
}