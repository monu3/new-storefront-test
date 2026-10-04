import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Download, Search, Users } from 'lucide-react';

import { authorizedFetch, postJson } from '@store-app/auth';
import type { AuthAdapter } from '@store-app/auth';
import type {
  AudienceCustomer,
  AudienceSort,
  AudienceSummary,
  OffsetPage,
} from '@store-app/api-client';
import {
  Badge,
  Button,
  Card,
  CardContent,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Label,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Skeleton,
  Textarea,
} from '@store-app/ui';
import { DataTable, type DataTableColumn } from '@store-app/ui';

import { formatDateTime, paisaToRupees, readProblem, relativeTime } from '../lib/format';

/**
 * The audience (Phase 4).
 *
 * <p>Who buys from this shop, what they have spent here, and what the owner has noted about them.
 *
 * <p><b>The header figures are the shop's lifetime totals, not this page's rows.</b> The server sums
 * across every customer in SQL. Adding the visible twenty rows in the browser and calling it the
 * shop's revenue would understate it, and would understate it by exactly the customers an owner
 * most wants to see.
 *
 * <p><b>Notes are shop-private and are not exported.</b> The CSV endpoint deliberately omits them:
 * an export is the artefact most likely to be emailed or pasted into a spreadsheet, and an owner's
 * private annotation about a real person has no business travelling that way. The UI says so rather
 * than leaving an owner to wonder why their notes are missing from the file.
 */

const SORTS: Array<{ value: AudienceSort; label: string }> = [
  { value: 'SPENT', label: 'Most spent' },
  { value: 'RECENT', label: 'Most recent' },
  { value: 'FIRST_SEEN', label: 'Newest customer' },
];

export function ShopAudience({ auth }: { auth: AuthAdapter }) {
  const queryClient = useQueryClient();
  const [sort, setSort] = useState<AudienceSort>('SPENT');
  const [page, setPage] = useState(0);
  const [filter, setFilter] = useState('');
  const [annotating, setAnnotating] = useState<AudienceCustomer | null>(null);
  const [exportNote, setExportNote] = useState<string | null>(null);

  const summary = useQuery({
    queryKey: ['audience-summary'],
    queryFn: async () => {
      const response = await authorizedFetch(auth, '/api/v1/shop/audience/summary');
      if (!response.ok) throw new Error(`Summary request failed: ${response.status}`);
      return (await response.json()) as AudienceSummary;
    },
  });

  const list = useQuery({
    queryKey: ['audience', sort, page],
    queryFn: async () => {
      const response = await authorizedFetch(
        auth,
        `/api/v1/shop/audience?sort=${sort}&page=${page}&size=20`,
      );
      if (!response.ok) throw new Error(`Audience request failed: ${response.status}`);
      return (await response.json()) as OffsetPage<AudienceCustomer>;
    },
  });

  const annotate = useMutation({
    mutationFn: async (input: { id: string; notes: string }) => {
      const response = await postJson(
        auth,
        `/api/v1/shop/audience/${input.id}/notes`,
        { notes: input.notes },
        'PATCH',
      );
      if (!response.ok) throw new Error(await readProblem(response));
      return response.json();
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['audience'] });
      setAnnotating(null);
    },
  });

  /**
   * Filters the page in the browser.
   *
   * <p>Honest about what it is: it narrows the twenty rows on screen, not the shop's customer list.
   * Server-side search is not built, and saying "20 of 1,240" next to a filtered count is clearer
   * than a filter that appears to work on everything and quietly does not.
   */
  const rows = (list.data?.content ?? []).filter((c) => {
    if (!filter.trim()) return true;
    const needle = filter.trim().toLowerCase();
    return (
      c.displayName.toLowerCase().includes(needle) ||
      c.email.toLowerCase().includes(needle) ||
      (c.phone ?? '').toLowerCase().includes(needle)
    );
  });

  const columns: DataTableColumn<AudienceCustomer>[] = [
    {
      key: 'customer',
      header: 'Customer',
      render: (c) => (
        <div className="min-w-0">
          <p className="truncate font-medium">{c.displayName}</p>
          <p className="truncate text-xs text-muted-foreground">{c.email}</p>
        </div>
      ),
    },
    {
      key: 'phone',
      header: 'Phone',
      render: (c) => <span className="text-sm">{c.phone || '—'}</span>,
    },
    {
      key: 'orders',
      header: 'Orders',
      className: 'text-right',
      render: (c) => <span className="text-sm">{c.orders}</span>,
    },
    {
      key: 'spent',
      header: 'Total spent',
      className: 'text-right',
      // The server's formatted string, not one the browser re-derived. Two formatters produce two
      // renderings of the same total, and the one that disagrees is always the invoice.
      render: (c) => <span className="font-medium">{c.totalSpentDisplay}</span>,
    },
    {
      key: 'average',
      header: 'Average',
      className: 'text-right',
      render: (c) => (
        <span className="text-sm text-muted-foreground">
          {`NRP ${paisaToRupees(c.averageOrderMinor)}`}
        </span>
      ),
    },
    {
      key: 'lastOrder',
      header: 'Last order',
      render: (c) => {
        const relative = relativeTime(c.lastOrderAt);
        return (
          <div className="text-xs">
            <p>{relative ?? 'Never ordered'}</p>
            {c.lastOrderAt ? (
              <p className="text-muted-foreground">{formatDateTime(c.lastOrderAt, true)}</p>
            ) : null}
          </div>
        );
      },
    },
    {
      key: 'notes',
      header: 'Private notes',
      render: (c) =>
        c.notes ? (
          <p className="max-w-48 truncate text-xs" title={c.notes}>
            {c.notes}
          </p>
        ) : (
          <span className="text-xs text-muted-foreground">—</span>
        ),
    },
    {
      key: 'actions',
      header: '',
      className: 'text-right',
      render: (c) => (
        <Button size="sm" variant="ghost" onClick={() => setAnnotating(c)}>
          {c.notes ? 'Edit note' : 'Add note'}
        </Button>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold">Audience</h2>
          <p className="text-sm text-muted-foreground">
            Customers of this shop only. Notes you add here are private to you.
          </p>
        </div>
        <Button variant="outline" onClick={() => void exportCsv(auth, sort, setExportNote)}>
          <Download className="mr-1.5 size-4" aria-hidden />
          Export CSV
        </Button>
      </div>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="Customers" value={summary.data?.customers} />
        <Stat label="Lifetime sales" value={summary.data && `NRP ${paisaToRupees(summary.data.spentMinor)}`} />
        <Stat
          label="Average order"
          value={summary.data && `NRP ${paisaToRupees(summary.data.averageOrderMinor)}`}
        />
        <Stat
          label="Repeat buyers"
          value={summary.data && `${summary.data.repeatRate}%`}
          hint={summary.data ? `${summary.data.buyers} have bought at least once` : undefined}
        />
      </div>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(c) => c.id}
        meta={list.data}
        onPageChange={setPage}
        toolbar={
          <div className="flex flex-wrap items-center gap-2">
            <div className="relative">
              <Search
                className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
                aria-hidden
              />
              <Input
                value={filter}
                onChange={(e) => {
                  setFilter(e.target.value);
                  setPage(0);
                }}
                placeholder="Filter this page…"
                aria-label="Filter the customers on this page"
                className="w-56 pl-8"
              />
            </div>
            <Select
              value={sort}
              onValueChange={(v) => {
                setSort(v as AudienceSort);
                setPage(0);
              }}
            >
              <SelectTrigger className="w-48" aria-label="Sort customers">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {SORTS.map((s) => (
                  <SelectItem key={s.value} value={s.value}>
                    {s.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {filter.trim() ? (
              <Badge variant="secondary" className="ml-auto">
                {rows.length} of {list.data?.content.length ?? 0} on this page
              </Badge>
            ) : null}
          </div>
        }
        empty={
          <div className="p-8 text-center">
            <Users className="mx-auto size-8 text-muted-foreground" aria-hidden />
            <p className="mt-3 font-medium">
              {filter.trim() ? 'Nobody on this page matches' : 'No customers yet'}
            </p>
            <p className="mt-1 text-sm text-muted-foreground">
              {filter.trim()
                ? 'Clear the filter to see this page again. Search covers the current page only.'
                : 'Customers appear here once they place an order, including guests who never made an account.'}
            </p>
          </div>
        }
        data-testid="audience-table"
      />

      <div className="flex flex-wrap items-center gap-2">
        <p className="text-xs text-muted-foreground">
          The CSV export contains names, contact details and spend. It does not include your private
          notes.
        </p>
        {exportNote ? (
          <Badge variant="secondary" className="text-amber-800">
            {exportNote}
          </Badge>
        ) : null}
      </div>

      {annotating ? (
        <NoteDialog
          customer={annotating}
          saving={annotate.isPending}
          error={annotate.error instanceof Error ? annotate.error.message : null}
          onSave={(notes) => annotate.mutate({ id: annotating.id, notes })}
          onClose={() => setAnnotating(null)}
        />
      ) : null}
    </div>
  );
}

function Stat({ label, value, hint }: { label: string; value?: string | number; hint?: string }) {
  return (
    <Card>
      <CardContent className="p-4">
        <p className="text-xs text-muted-foreground">{label}</p>
        {value === undefined ? (
          <Skeleton className="mt-1.5 h-6 w-24" />
        ) : (
          <p className="mt-0.5 text-xl font-semibold">{value}</p>
        )}
        {hint ? <p className="mt-0.5 text-xs text-muted-foreground">{hint}</p> : null}
      </CardContent>
    </Card>
  );
}

function NoteDialog({
  customer,
  saving,
  error,
  onSave,
  onClose,
}: {
  customer: AudienceCustomer;
  saving: boolean;
  error: string | null;
  onSave: (notes: string) => void;
  onClose: () => void;
}) {
  const [notes, setNotes] = useState(customer.notes ?? '');

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Private note</DialogTitle>
          <DialogDescription>
            About {customer.displayName}. Only you can see this — it is never shown to the customer
            and never included in a CSV export.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-3">
          <Label htmlFor="note-body">Note</Label>
          <Textarea
            id="note-body"
            rows={5}
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            maxLength={2000}
            placeholder="e.g. Prefers delivery after 6pm. Always pays cash on delivery."
          />
          <p className="text-xs text-muted-foreground">{notes.length}/2000 characters</p>
          {customer.notesUpdatedAt ? (
            <p className="text-xs text-muted-foreground">
              Last edited {formatDateTime(customer.notesUpdatedAt, true)}
            </p>
          ) : null}
          {error ? (
            <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">
              {error}
            </p>
          ) : null}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={saving}>
            Cancel
          </Button>
          <Button
            onClick={() => onSave(notes)}
            disabled={saving}
            style={{ background: '#22c55e', color: '#fff' }}
            className="border-0"
          >
            {saving ? 'Saving…' : 'Save note'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ---------------------------------------------------------------------- export

/**
 * Downloads the audience CSV.
 *
 * <p>Fetches it with the Bearer token and saves the result through an object URL, rather than
 * navigating to the URL. A plain `window.open` cannot carry an `Authorization` header, so the
 * obvious version is a guaranteed 401 — the browser fetches the URL with no token and the console
 * gets the JSON error page where the CSV should be.
 *
 * <p><b>A truncated export is announced.</b> The server caps the file and sends
 * `X-Export-Truncated` with the true total. A silently shortened customer list is worse than no
 * export at all: the owner would reconcile their records against a file that quietly holds only
 * the first 10,000 of 40,000 customers.
 */
async function exportCsv(
  auth: AuthAdapter,
  sort: AudienceSort,
  onNote: (note: string | null) => void,
): Promise<void> {
  onNote(null);

  const response = await authorizedFetch(auth, `/api/v1/shop/audience/export.csv?sort=${sort}`);
  if (!response.ok) {
    onNote(`Export failed: ${await readProblem(response)}`);
    return;
  }

  const truncated = response.headers.get('X-Export-Truncated') === 'true';
  const total = response.headers.get('X-Export-Total');
  const blob = await response.blob();

  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = 'audience.csv';
  document.body.appendChild(link);
  link.click();
  link.remove();
  // Revoked immediately: a blob URL pins its bytes in memory until it is released, and this file
  // is the largest response the console ever makes.
  URL.revokeObjectURL(url);

  onNote(
    truncated
      ? `Truncated: the file has the first 10,000 of ${total ?? 'more'} customers.`
      : null,
  );
}
