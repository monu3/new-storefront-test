import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Mail, Search } from 'lucide-react';

import { authorizedFetch } from '@store-app/auth';
import type { AuthAdapter } from '@store-app/auth';
import {
  Badge,
  Button,
  Card,
  CardContent,
  Input,
  Skeleton,
  cn,
} from '@store-app/ui';

import { formatDateTime, readProblem } from '../lib/format';

/**
 * One message the platform tried to deliver.
 *
 * <p>`attempts` and `lastError` are on the row so a send that failed twice cannot look identical
 * to one that failed once — which is the whole reason this screen exists.
 */
interface Entry {
  id: string;
  audience: string;
  recipientUserId: string | null;
  recipientShopId: string | null;
  channel: string;
  type: string;
  title: string;
  body: string;
  status: string;
  attempts: number;
  lastError: string | null;
  sentAt: string | null;
  createdAt: string;
}

interface Summary {
  total: number;
  sent: number;
  failed: number;
  pending: number;
  retried: number;
  emails: number;
}

interface History {
  days: number;
  entries: Entry[];
  totalElements: number;
  totalPages: number;
  size: number;
  offset: number;
  summary: Summary;
}

const PAGE_SIZE = 25;

/**
 * Support tooling: what the platform tried to tell somebody, and whether it got there (Phase 7).
 *
 * <p><b>The email log is this screen filtered to {@code channel = EMAIL}, not a second table.</b> A
 * separate record of the same send would disagree with the first — most visibly on a retry, where
 * this table has one row with two attempts and a separate log would have two rows.
 *
 * <p><b>The tiles count the whole window, not the current filter.</b> They answer "of everything in
 * the last N days, how much failed", which is the question someone opens this screen with. A
 * summary that moved as the filters changed would describe the current selection instead, and the
 * two read as the same number.
 *
 * <p><b>"Sent" means handed to a sender, not delivered.</b> This platform has no way to know a
 * message arrived or was opened, and a tile implying otherwise would be the wrong thing to tell
 * someone who is about to promise a customer a refund.
 */
export function AdminSupport({ auth }: { auth: AuthAdapter }) {
  const [days, setDays] = useState(30);
  const [channel, setChannel] = useState('');
  const [status, setStatus] = useState('');
  const [audience, setAudience] = useState('');
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState<Entry | null>(null);

  const params = new URLSearchParams({
    days: String(days),
    limit: String(PAGE_SIZE),
    offset: String(page * PAGE_SIZE),
  });
  if (channel) params.set('channel', channel);
  if (status) params.set('status', status);
  if (audience) params.set('audience', audience);
  if (query) params.set('q', query);

  const { data, isLoading, error } = useQuery({
    queryKey: ['admin', 'support', days, channel, status, audience, query, page],
    queryFn: async () => {
      const response = await authorizedFetch(
        auth,
        `/api/v1/admin/notifications?${params.toString()}`,
      );
      if (!response.ok) throw new Error(await readProblem(response));
      return (await response.json()) as History;
    },
  });

  const totalPages = data?.totalPages ?? 1;

  return (
    <div className="space-y-4">
      <header>
        <h1 className="text-2xl font-semibold">Support</h1>
        <p className="text-muted-foreground">
          Everything the platform tried to send, and what happened to it. Filter the channel to{' '}
          <strong>email</strong> for the email log.
        </p>
      </header>

      {data ? (
        <div className="grid gap-3 sm:grid-cols-3 lg:grid-cols-6">
          <Tile label="Total" value={data.summary.total} />
          <Tile label="Sent" value={data.summary.sent} />
          <Tile
            label="Failed"
            value={data.summary.failed}
            tone={data.summary.failed > 0 ? 'warn' : 'plain'}
            hint="Handed to a sender that then failed, or never sent at all."
          />
          <Tile label="Pending" value={data.summary.pending} />
          <Tile
            label="Retried"
            value={data.summary.retried}
            hint="More than one attempt — these are the ones worth looking at twice."
          />
          <Tile label="Emails" value={data.summary.emails} />
        </div>
      ) : null}

      <div className="flex flex-wrap items-center gap-2">
        <form
          className="relative"
          onSubmit={(event) => {
            event.preventDefault();
            setPage(0);
            setQuery(search.trim());
          }}
        >
          <Search
            className="pointer-events-none absolute left-2.5 top-2.5 size-4 text-muted-foreground"
            aria-hidden
          />
          <Input
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Search title or body"
            aria-label="Search notifications"
            className="w-64 pl-8"
          />
        </form>

        <select
          value={channel}
          onChange={(event) => {
            setChannel(event.target.value);
            setPage(0);
          }}
          aria-label="Filter by channel"
          className="h-9 rounded-md border bg-background px-2 text-sm"
        >
          <option value="">Any channel</option>
          <option value="EMAIL">Email only (the email log)</option>
          <option value="PUSH">Push</option>
          <option value="IN_APP">In-app</option>
        </select>

        <select
          value={status}
          onChange={(event) => {
            setStatus(event.target.value);
            setPage(0);
          }}
          aria-label="Filter by delivery status"
          className="h-9 rounded-md border bg-background px-2 text-sm"
        >
          <option value="">Any status</option>
          <option value="FAILED">Failed</option>
          <option value="PENDING">Pending</option>
          <option value="SENT">Sent</option>
        </select>

        <select
          value={audience}
          onChange={(event) => {
            setAudience(event.target.value);
            setPage(0);
          }}
          aria-label="Filter by audience"
          className="h-9 rounded-md border bg-background px-2 text-sm"
        >
          <option value="">Any audience</option>
          <option value="CUSTOMER">Customers</option>
          <option value="SHOP">Shops</option>
          <option value="ADMIN">Admins</option>
        </select>

        <select
          value={days}
          onChange={(event) => {
            setDays(Number(event.target.value));
            setPage(0);
          }}
          aria-label="Window"
          className="h-9 rounded-md border bg-background px-2 text-sm"
        >
          <option value={7}>Last 7 days</option>
          <option value={30}>Last 30 days</option>
          <option value={90}>Last 90 days</option>
          <option value={365}>Last year</option>
        </select>

        {query || channel || status || audience ? (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              setSearch('');
              setQuery('');
              setChannel('');
              setStatus('');
              setAudience('');
              setPage(0);
            }}
          >
            Clear
          </Button>
        ) : null}
      </div>

      <Card>
        <CardContent className="p-0">
          {isLoading ? (
            <div className="space-y-2 p-4">
              {Array.from({ length: 5 }).map((_, i) => (
                <Skeleton key={i} className="h-12 w-full" />
              ))}
            </div>
          ) : error ? (
            <p role="alert" className="p-6 text-sm text-destructive">
              {error.message}
            </p>
          ) : (data?.entries.length ?? 0) === 0 ? (
            <p className="p-10 text-center text-sm text-muted-foreground">
              {query || channel || status || audience
                ? 'No messages match these filters.'
                : 'Nothing has been sent in this window.'}
            </p>
          ) : (
            <ul>
              {data!.entries.map((entry) => (
                <li key={entry.id} className="border-b last:border-0">
                  <button
                    type="button"
                    onClick={() => setOpen(entry)}
                    className="flex w-full items-start gap-3 p-3 text-left hover:bg-muted/40"
                  >
                    <span className="mt-0.5">
                      {entry.channel === 'EMAIL' ? (
                        <Mail className="size-4 text-muted-foreground" aria-hidden />
                      ) : (
                        <Badge variant="secondary" className="text-[10px]">
                          {entry.channel}
                        </Badge>
                      )}
                    </span>

                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-sm font-medium">{entry.title}</span>
                      <span className="block truncate text-xs text-muted-foreground">
                        {entry.type} · {entry.audience.toLowerCase()} ·{' '}
                        {formatDateTime(entry.createdAt, true)}
                      </span>
                    </span>

                    <span className="flex shrink-0 items-center gap-1.5">
                      {entry.attempts > 1 ? (
                        <Badge variant="secondary" className="bg-amber-100 text-amber-800 text-[10px]">
                          {entry.attempts} attempts
                        </Badge>
                      ) : null}
                      <StatusPill status={entry.status} />
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      {data ? (
        <div className="flex items-center justify-between gap-3">
          <p className="text-xs text-muted-foreground">
            {data.totalElements === 0
              ? 'No messages'
              : `Showing ${data.offset + 1}–${Math.min(
                  data.offset + data.entries.length,
                  data.totalElements,
                )} of ${data.totalElements.toLocaleString('en-US')}`}
          </p>
          <div className="flex gap-1">
            <Button
              variant="outline"
              size="sm"
              disabled={page === 0}
              onClick={() => setPage((current) => Math.max(0, current - 1))}
            >
              Previous
            </Button>
            <span className="px-2 py-1 text-xs text-muted-foreground">
              Page {page + 1} of {totalPages}
            </span>
            <Button
              variant="outline"
              size="sm"
              disabled={page + 1 >= totalPages}
              onClick={() => setPage((current) => current + 1)}
            >
              Next
            </Button>
          </div>
        </div>
      ) : null}

      {open ? (
        <Card>
          <CardContent className="space-y-3 p-4">
            <div className="flex items-start justify-between gap-3">
              <div>
                <p className="font-medium">{open.title}</p>
                <p className="text-xs text-muted-foreground">
                  {formatDateTime(open.createdAt, true)} · {open.attempts} attempt
                  {open.attempts === 1 ? '' : 's'}
                </p>
              </div>
              <Button variant="ghost" size="sm" onClick={() => setOpen(null)}>
                Close
              </Button>
            </div>

            <pre className="whitespace-pre-wrap rounded-lg bg-muted/50 p-3 text-sm">
              {open.body}
            </pre>

            {open.lastError ? (
              <div className="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm">
                <p className="font-medium">Last error</p>
                <p className="font-mono text-xs">{open.lastError}</p>
              </div>
            ) : null}

            <p className="text-xs text-muted-foreground">
              To:{' '}
              {open.recipientUserId
                ? `user ${open.recipientUserId.slice(0, 8)}`
                : open.recipientShopId
                  ? `shop ${open.recipientShopId.slice(0, 8)}`
                  : 'nobody'}
            </p>
          </CardContent>
        </Card>
      ) : null}
    </div>
  );
}

function StatusPill({ status }: { status: string }) {
  const className =
    status === 'SENT'
      ? 'bg-emerald-100 text-emerald-800'
      : status === 'FAILED'
        ? 'bg-red-100 text-red-800'
        : 'bg-amber-100 text-amber-800';

  return (
    <Badge variant="secondary" className={className}>
      {status.toLowerCase()}
    </Badge>
  );
}

function Tile({
  label,
  value,
  hint,
  tone = 'plain',
}: {
  label: string;
  value: number;
  hint?: string;
  tone?: 'plain' | 'warn';
}) {
  return (
    <Card className={cn(tone === 'warn' && 'border-amber-300 bg-amber-50/40')}>
      <CardContent className="p-3">
        <p className="text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
          {label}
        </p>
        <p className="text-xl font-semibold">{value.toLocaleString('en-US')}</p>
        {hint ? <p className="mt-0.5 text-[10px] text-muted-foreground">{hint}</p> : null}
      </CardContent>
    </Card>
  );
}