import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { BadgeCheck, Ban, Clock, Landmark, Receipt } from 'lucide-react';

import { authorizedFetch, postJson } from '@store-app/auth';
import type { AuthAdapter } from '@store-app/auth';
import { DataTable, type DataTableColumn } from '@store-app/ui';
import {
  Badge,
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Label,
  Textarea,
} from '@store-app/ui';
import { cn } from '@store-app/ui';

import { formatDateTime, readProblem } from '../lib/format';

/**
 * The admin's top-up queue (Phase 6).
 *
 * <p><b>This screen exists because D7's flow is not completable without it.</b> The shop's screen can
 * request a plan and submit proof of payment, but nothing happens until an admin approves — and with
 * no screen for that, the subscription half of Phase 6 would have been a purchase button that
 * silently does nothing forever.
 *
 * <p><b>It picks the right endpoint per row, and that is not cosmetic.</b> There are two approvals:
 *
 * <ul>
 *   <li>a plain top-up — credits the shop's wallet (Phase 4);</li>
 *   <li>a subscription purchase — credits, charges the plan price, activates the plan and grants its
 *       entitlements, in one transaction.</li>
 * </ul>
 *
 * Approving a subscription through the *plain* wallet endpoint credits the money and never starts
 * the plan. The shop's bank statement shows the transfer, its balance went up, and it has no
 * subscription — a failure that looks like a successful payment from both sides. So a row that is a
 * subscription purchase is detected here and routed to the endpoint that finishes the job, and it is
 * labelled so an admin can see the difference before clicking.
 *
 * <p><b>The reference is the second column, deliberately.</b> Reconciling a bank statement against
 * this queue is the whole task, and an admin looking for "who sent this" should not have to open a
 * row.
 */
interface QueueItem {
  id: string;
  shopId: string;
  shopName: string;
  amount: number;
  amountDisplay: string;
  method: 'ESAWA' | 'BANK_TRANSFER';
  /** The transfer reference. Match this against the bank statement. */
  reference: string | null;
  shopNote: string | null;
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
  reviewNote: string | null;
  createdAt: string;
  /**
   * True when this top-up is paying for a subscription.
   *
   * <p>Null means the server did not say, which for an older row means it is not one — treated as a
   * plain top-up rather than refused, because refusing to approve ordinary wallet top-ups would
   * break the Phase 4 flow.
   */
  isSubscription: boolean | null;
  /** The plan being bought, when it is a subscription. */
  planCode: string | null;
  /** True when the shop is already subscribed and this is an upgrade. */
  isUpgrade: boolean | null;
}

interface QueuePage {
  content: QueueItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  pendingCount: number;
}

const STATUS_TABS: Array<{ value: QueueItem['status'] | null; label: string }> = [
  { value: 'PENDING', label: 'Waiting' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: null, label: 'All' },
];

export function AdminWallet({ auth }: { auth: AuthAdapter }) {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<QueueItem['status'] | null>('PENDING');
  const [page, setPage] = useState(0);
  const [rejecting, setRejecting] = useState<QueueItem | null>(null);
  const [decision, setDecision] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const queue = useQuery({
    queryKey: ['admin', 'topups', status, page],
    queryFn: async () => {
      const query = new URLSearchParams({ page: String(page), size: '25' });
      if (status) query.set('status', status);
      const response = await authorizedFetch(auth, `/api/v1/admin/wallet/topups?${query}`);
      if (!response.ok) throw new Error(await readProblem(response));
      return (await response.json()) as QueuePage;
    },
  });

  const decide = useMutation({
    mutationFn: async ({ item, verdict, note }: { item: QueueItem; verdict: 'approve' | 'reject'; note?: string }) => {
      // The two endpoints differ in what they do, and choosing wrong is the whole risk here.
      const path = item.isSubscription
        ? `/api/v1/admin/subscription-payments/${item.id}/${verdict}`
        : `/api/v1/admin/wallet/topups/${item.id}/${verdict}`;

      const response = await postJson(auth, path, note ? { note } : {});
      if (!response.ok) throw new Error(await readProblem(response));
      return response.json();
    },
    onSuccess: (_, variables) => {
      setDecision(
        variables.verdict === 'approve'
          ? variables.item.isSubscription
            ? `Approved. ${variables.item.shopName} is now subscribed and pays no commission.`
            : `Approved. ${variables.item.shopName}'s wallet has been credited.`
          : 'Rejected. The shop can see the reason.',
      );
      setError(null);
      setRejecting(null);
      void queryClient.invalidateQueries({ queryKey: ['admin', 'topups'] });
      void queryClient.invalidateQueries({ queryKey: ['admin', 'revenue'] });
    },
    onError: (e: Error) => {
      setError(e.message);
      setRejecting(null);
    },
  });

  const columns: DataTableColumn<QueueItem>[] = [
    {
      key: 'shop',
      header: 'Shop',
      render: (item) => (
        <div className="min-w-0">
          <p className="truncate font-medium">{item.shopName}</p>
          <p className="text-xs text-muted-foreground">{formatDateTime(item.createdAt)}</p>
        </div>
      ),
    },
    {
      key: 'reference',
      header: 'Transfer reference',
      // Second, and monospaced, because this is the column the admin is scanning for against a bank
      // statement. Burying it under a menu would make every reconciliation a click.
      render: (item) =>
        item.reference ? (
          <span className="font-mono text-sm">{item.reference}</span>
        ) : (
          <span className="text-sm text-muted-foreground">No reference given</span>
        ),
    },
    {
      key: 'amount',
      header: 'Amount',
      render: (item) => (
        <div>
          <p className="font-medium tabular-nums">{item.amountDisplay}</p>
          {item.isSubscription && item.planCode ? (
            <p className="text-xs text-muted-foreground">for {item.planCode}</p>
          ) : null}
        </div>
      ),
    },
    {
      key: 'method',
      header: 'Paid by',
      render: (item) => (
        <span className="inline-flex items-center gap-1.5 text-sm">
          {item.method === 'ESAWA' ? (
            <Badge variant="outline">eSewa</Badge>
          ) : (
            <>
              <Landmark className="size-3.5" aria-hidden />
              Bank
            </>
          )}
        </span>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (item) => (
        <div className="space-y-1">
          <StatusBadge status={item.status} />
          {item.isSubscription ? (
            <p className="text-xs text-muted-foreground">
              Subscription{item.isUpgrade ? ' · upgrade' : ''}
            </p>
          ) : null}
        </div>
      ),
    },
    {
      key: 'actions',
      header: '',
      headerClassName: 'text-right',
      className: 'text-right',
      render: (item) =>
        item.status === 'PENDING' ? (
          <div className="flex justify-end gap-1">
            <Button
              size="sm"
              variant="ghost"
              disabled={decide.isPending}
              onClick={() => decide.mutate({ item, verdict: 'approve' })}
            >
              <BadgeCheck className="mr-1 size-3.5" aria-hidden />
              Approve
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setRejecting(item)}>
              <Ban className="mr-1 size-3.5" aria-hidden />
              Reject
            </Button>
          </div>
        ) : item.reviewNote ? (
          <span className="text-xs text-muted-foreground">{item.reviewNote}</span>
        ) : null,
    },
  ];

  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-2xl font-semibold">Wallet approvals</h1>
        <p className="text-muted-foreground">
          Money a shop claims to have sent. Match the reference against your bank statement before
          approving — an approval credits a balance nobody paid for.
        </p>
      </header>

      {decision ? (
        <p role="status" className="rounded-md border bg-muted/40 p-3 text-sm">
          {decision}
        </p>
      ) : null}
      {error ? (
        <p role="alert" className="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm">
          {error}
        </p>
      ) : null}

      {queue.data && queue.data.pendingCount > 0 ? (
        <p className="flex items-center gap-1.5 text-sm">
          <Clock className="size-4 text-amber-600" aria-hidden />
          {queue.data.pendingCount} waiting for a decision.
        </p>
      ) : null}

      <DataTable
        columns={columns}
        rows={queue.data?.content ?? []}
        rowKey={(item) => item.id}
        meta={queue.data ? { ...queue.data } : undefined}
        onPageChange={setPage}
        toolbar={
          <div className="flex gap-1">
            {STATUS_TABS.map((tab) => (
              <Button
                key={tab.label}
                size="sm"
                variant={status === tab.value ? 'default' : 'ghost'}
                onClick={() => {
                  setStatus(tab.value);
                  setPage(0);
                }}
              >
                {tab.label}
              </Button>
            ))}
          </div>
        }
        empty={
          <p className="text-sm text-muted-foreground">
            Nothing here. Shops submit a request after they transfer money, and it waits here until
            an admin decides.
          </p>
        }
      />

      {rejecting ? (
        <RejectDialog
          item={rejecting}
          busy={decide.isPending}
          onClose={() => setRejecting(null)}
          onReject={(note) => decide.mutate({ item: rejecting, verdict: 'reject', note })}
        />
      ) : null}
    </div>
  );
}

/**
 * Rejection, with a required reason.
 *
 * <p>Required because the shop is told the reason and can fix it and try again. A rejection with no
 * explanation tells the owner their bank transfer vanished.
 */
function RejectDialog({
  item,
  busy,
  onClose,
  onReject,
}: {
  item: QueueItem;
  busy: boolean;
  onClose: () => void;
  onReject: (note: string) => void;
}) {
  const [note, setNote] = useState('');

  return (
    <Dialog open onOpenChange={(open) => !open && !busy && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Reject this request</DialogTitle>
          <DialogDescription>
            {item.shopName} will be told the reason. Nothing is charged and nothing is activated.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-2">
          <Label htmlFor="reject-note">Reason (required)</Label>
          <Textarea
            id="reject-note"
            rows={3}
            value={note}
            onChange={(e) => setNote(e.target.value)}
            placeholder="e.g. no matching transfer with that reference on our statement"
          />
          <p className="flex items-start gap-1.5 text-xs text-muted-foreground">
            <Receipt className="mt-0.5 size-3.5 shrink-0" aria-hidden />
            Reference on the request:{' '}
            <span className="font-mono">{item.reference ?? 'none given'}</span>
          </p>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button
            variant="destructive"
            disabled={busy || note.trim().length === 0}
            onClick={() => onReject(note.trim())}
          >
            {busy ? 'Rejecting…' : 'Reject request'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function StatusBadge({ status }: { status: QueueItem['status'] }) {
  const tone: Record<QueueItem['status'], string> = {
    PENDING: 'bg-warning/10 text-warning',
    APPROVED: 'bg-success/10 text-success',
    REJECTED: 'bg-muted text-muted-foreground',
  };
  return (
    <Badge className={cn(tone[status])}>
      {status === 'PENDING' ? 'Waiting' : status === 'APPROVED' ? 'Approved' : 'Rejected'}
    </Badge>
  );
}
