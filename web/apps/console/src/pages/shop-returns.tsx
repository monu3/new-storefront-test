import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Check, PackageX, RotateCcw, X } from 'lucide-react';

import { authorizedFetch, postJson } from '@store-app/auth';
import type { AuthAdapter } from '@store-app/auth';
import {
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  DataTable,
  Input,
  Skeleton,
  type DataTableColumn,
} from '@store-app/ui';
import { cn } from '@store-app/ui';

import { formatDateTime, readProblem } from '../lib/format';

/** One return, as the shop sees it. */
interface ReturnView {
  id: string;
  orderId: string;
  status: string;
  reason: string;
  resolutionNote: string | null;
  requestedMinor: number;
  approvedMinor: number;
  commissionReversedMinor: number;
  requestedAt: string;
  decidedAt: string | null;
  items: ReturnItemView[];
}

interface ReturnItemView {
  orderItemId: string;
  quantity: number;
  quantityApproved: number;
  unitPriceMinor: number;
}

interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/**
 * The shop's return queue (Phase 8, D14).
 *
 * <p><b>Approve and refund are two separate buttons, and the difference matters.</b> Approving
 * records a decision — "yes, we will refund these two" — and takes no money. Refunding debits the
 * wallet. A screen with one button would credit a customer's refund the moment it was clicked, and
 * the payment would then fail with the wallet already debited.
 *
 * <p><b>The panel is editable per line.</b> A shop regularly approves two of four items, and a
 * single approve/decline for the whole request cannot express that — so it would force the shop to
 * either over-refund or decline something it should have partly honoured.
 *
 * <p><b>A refund the wallet cannot cover says so and stays visible.</b> {@code WalletService} refuses
 * a debit that would go negative, and the refusal is left to propagate: the return stays APPROVED
 * with nothing refunded, which is a state somebody can finish rather than a silent failure.
 */
export function ShopReturns({ auth }: { auth: AuthAdapter }) {
  const queryClient = useQueryClient();

  const [page, setPage] = useState(0);
  const [status, setStatus] = useState('');
  const [open, setOpen] = useState<string | null>(null);
  const [note, setNote] = useState('');

  const params = new URLSearchParams({ page: String(page), size: '25' });
  if (status) params.set('status', status);

  const { data, isLoading, error } = useQuery({
    queryKey: ['shop', 'returns', page, status],
    queryFn: async () => {
      const response = await authorizedFetch(auth, `/api/v1/shops/returns?${params.toString()}`);
      if (!response.ok) throw new Error(await readProblem(response));
      return (await response.json()) as Page<ReturnView>;
    },
  });

  const approve = useMutation({
    mutationFn: async (payload: { id: string; quantities: Record<string, number>; note: string }) => {
      const response = await postJson(
        auth,
        `/api/v1/shops/returns/${payload.id}/approve`,
        { quantities: payload.quantities, note: payload.note || null },
        'PATCH',
      );
      if (!response.ok) throw new Error(await readProblem(response));
      return payload.id;
    },
    onSuccess: () => {
      setOpen(null);
      setNote('');
      void queryClient.invalidateQueries({ queryKey: ['shop', 'returns'] });
    },
  });

  const reject = useMutation({
    mutationFn: async (payload: { id: string; note: string }) => {
      const response = await postJson(
        auth,
        `/api/v1/shops/returns/${payload.id}/reject`,
        { note: payload.note },
        'PATCH',
      );
      if (!response.ok) throw new Error(await readProblem(response));
      return payload.id;
    },
    onSuccess: () => {
      setOpen(null);
      setNote('');
      void queryClient.invalidateQueries({ queryKey: ['shop', 'returns'] });
    },
  });

  const refund = useMutation({
    mutationFn: async (id: string) => {
      const response = await postJson(auth, `/api/v1/shops/returns/${id}/refund`, {});
      if (!response.ok) throw new Error(await readProblem(response));
      return id;
    },
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['shop', 'returns'] }),
  });

  const columns: DataTableColumn<ReturnView>[] = [
    {
      key: 'order',
      header: 'Order',
      render: (row) => (
        <span className="font-mono text-xs text-muted-foreground">
          {row.orderId.slice(0, 8)}
        </span>
      ),
    },
    {
      key: 'when',
      header: 'Asked',
      render: (row) => (
        <span className="whitespace-nowrap text-xs text-muted-foreground">
          {formatDateTime(row.requestedAt)}
        </span>
      ),
    },
    {
      key: 'reason',
      header: 'Why',
      render: (row) => <span className="text-sm">{row.reason}</span>,
    },
    {
      key: 'amount',
      header: 'Amount',
      render: (row) => (
        <div className="text-right text-sm">
          <p className="font-medium">{money(row.approvedMinor || row.requestedMinor)}</p>
          <p className="text-xs text-muted-foreground">
            {row.approvedMinor > 0 && row.approvedMinor < row.requestedMinor
              ? `asked ${money(row.requestedMinor)}`
              : `${row.items.length} line(s)`}
          </p>
        </div>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => <StatusBadge status={row.status} />,
    },
    {
      key: 'actions',
      header: 'Actions',
      headerClassName: 'text-right',
      className: 'text-right',
      render: (row) => (
        <div className="flex justify-end gap-1">
          {row.status === 'REQUESTED' ? (
            <Button size="sm" variant="outline" onClick={() => setOpen(row.id)}>
              Decide
            </Button>
          ) : row.status === 'APPROVED' ? (
            <>
              <Button
                size="sm"
                disabled={refund.isPending}
                onClick={() => refund.mutate(row.id)}
              >
                <RotateCcw className="mr-1.5 size-3.5" aria-hidden />
                Refund
              </Button>
            </>
          ) : row.status === 'REFUNDED' && row.commissionReversedMinor > 0 ? (
            <span className="text-xs text-muted-foreground">
              {money(row.commissionReversedMinor)} commission returned
            </span>
          ) : null}
        </div>
      ),
    },
  ];

  const openRow = data?.content.find((row) => row.id === open);

  return (
    <div className="space-y-4">
      <header>
        <h1 className="text-2xl font-semibold">Returns</h1>
        <p className="text-muted-foreground">
          Approving records a decision. Refunding debits your wallet and credits commission back in
          proportion to what came back.
        </p>
      </header>

      {(approve.error || reject.error || refund.error) && (
        <p
          role="alert"
          className="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm"
        >
          {(approve.error ?? reject.error ?? refund.error)?.message}
        </p>
      )}

      {refund.error && /balance|afford/i.test(refund.error.message) ? (
        <p className="rounded-md border bg-warning/10 p-3 text-sm text-foreground">
          The refund did not run: your balance will not cover it. The return is still approved and
          still in this list — top up and press Refund again once you can.
        </p>
      ) : null}

      <DataTable<ReturnView>
        data-testid="shop-returns"
        columns={columns}
        rows={data?.content ?? []}
        rowKey={(row) => row.id}
        meta={
          data
            ? {
                page: data.page,
                size: data.size,
                totalElements: data.totalElements,
                totalPages: data.totalPages,
              }
            : undefined
        }
        onPageChange={setPage}
        empty={
          isLoading ? (
            <Skeleton className="h-24 w-full" />
          ) : (
            <span className="inline-flex items-center gap-2">
              <PackageX className="size-4" aria-hidden />
              No returns waiting. Open requests appear here first.
            </span>
          )
        }
        toolbar={
          <div className="flex items-center gap-2 border-b p-3">
            <select
              value={status}
              onChange={(event) => {
                setStatus(event.target.value);
                setPage(0);
              }}
              aria-label="Filter by status"
              className="h-9 rounded-md border bg-background px-2 text-sm"
            >
              <option value="">All returns</option>
              <option value="REQUESTED">Waiting on you</option>
              <option value="APPROVED">Approved, not refunded</option>
              <option value="REFUNDED">Refunded</option>
              <option value="REJECTED">Declined</option>
            </select>
          </div>
        }
      />

      {error ? (
        <Card>
          <CardContent className="p-6 text-sm text-destructive">{error.message}</CardContent>
        </Card>
      ) : null}

      {open && openRow ? (
        <DecidePanel
          row={openRow}
          note={note}
          onNote={setNote}
          onApprove={(quantities) => approve.mutate({ id: openRow.id, quantities, note })}
          onReject={() => reject.mutate({ id: openRow.id, note })}
          onClose={() => {
            setOpen(null);
            setNote('');
          }}
          pending={approve.isPending || reject.isPending}
        />
      ) : null}
    </div>
  );
}

/**
 * The decision panel.
 *
 * <p><b>Per-line quantities, defaulted to everything.</b> A shop that approves nothing has a way to
 * say so — by lowering each field to zero — and a shop that approves everything should not have to
 * type four numbers to say so.
 */
function DecidePanel({
  row,
  note,
  onNote,
  onApprove,
  onReject,
  onClose,
  pending,
}: {
  row: ReturnView;
  note: string;
  onNote: (value: string) => void;
  onApprove: (quantities: Record<string, number>) => void;
  onReject: () => void;
  onClose: () => void;
  pending: boolean;
}) {
  const [approved, setApproved] = useState<Record<string, number>>(
    Object.fromEntries(row.items.map((item) => [item.orderItemId, item.quantity])),
  );

  const approvedTotal = row.items.reduce(
    (sum, item) => sum + (approved[item.orderItemId] ?? 0) * item.unitPriceMinor,
    0,
  );

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Decide this return</CardTitle>
        <CardDescription>
          The customer said: &ldquo;{row.reason}&rdquo; Refunding{' '}
          <strong>{money(approvedTotal)}</strong> will credit commission back in proportion.
        </CardDescription>
      </CardHeader>

      <CardContent className="space-y-3">
        {row.items.map((item) => (
          <div key={item.orderItemId} className="flex items-center gap-3">
            <span className="flex-1 text-sm">
              {item.quantity} × {money(item.unitPriceMinor)}
            </span>
            <label className="text-xs">
              <span className="font-medium">Approve</span>
              <Input
                type="number"
                min={0}
                max={item.quantity}
                value={approved[item.orderItemId] ?? 0}
                onChange={(event) =>
                  setApproved((current) => ({
                    ...current,
                    [item.orderItemId]: Math.min(
                      item.quantity,
                      Math.max(0, Number(event.target.value) || 0),
                    ),
                  }))
                }
                aria-label={`Units approved of ${item.quantity}`}
                className="mt-1 h-8 w-20"
              />
            </label>
          </div>
        ))}

        <label className="block text-sm">
          <span className="font-medium">
            Note {row.status === 'REQUESTED' ? '(required to decline)' : '(optional)'}
          </span>
          <Input
            value={note}
            onChange={(event) => onNote(event.target.value)}
            maxLength={500}
            className="mt-1"
          />
        </label>

        <div className="flex gap-2">
          <Button
            disabled={pending || approvedTotal === 0}
            onClick={() => onApprove(approved)}
          >
            <Check className="mr-1.5 size-4" aria-hidden />
            Approve {money(approvedTotal)}
          </Button>
          <Button
            variant="outline"
            disabled={pending || note.trim().length === 0}
            onClick={onReject}
          >
            <X className="mr-1.5 size-4" aria-hidden />
            Decline
          </Button>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
        </div>

        <p className="text-xs text-muted-foreground">
          Approving does not move money. You press Refund afterwards, which is what takes it out of
          your wallet.
        </p>
      </CardContent>
    </Card>
  );
}

function money(minor: number): string {
  return `Rs. ${(minor / 100).toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
}

function StatusBadge({ status }: { status: string }) {
  const className =
    status === 'REFUNDED'
      ? 'bg-emerald-100 text-emerald-800'
      : status === 'REJECTED'
        ? 'bg-muted text-muted-foreground'
        : status === 'APPROVED'
          ? 'bg-amber-100 text-foreground'
          : 'bg-secondary text-blue-800';

  return (
    <Badge variant="secondary" className={cn(className)}>
      {status.toLowerCase()}
    </Badge>
  );
}