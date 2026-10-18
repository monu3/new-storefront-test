import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Search, X } from 'lucide-react';

import { authorizedFetch } from '@store-app/auth';
import type { AuthAdapter } from '@store-app/auth';
import {
  Badge,
  Button,
  Card,
  CardContent,
  DataTable,
  Input,
  Skeleton,
  type DataTableColumn,
} from '@store-app/ui';
import { cn } from '@store-app/ui';

import { formatDateTime, readProblem } from '../lib/format';

/**
 * One audit row.
 *
 * <p>`actorEmail` is null for a scheduled job, and that is a different claim from "an admin did
 * this" — so it is rendered differently rather than shown as a blank person.
 */
interface AuditEntry {
  id: string;
  action: string;
  group: string;
  actorUserId: string | null;
  actorEmail: string | null;
  actorRoles: string | null;
  entityType: string;
  entityId: string;
  summary: string;
  reason: string | null;
  ipAddress: string | null;
  createdAt: string;
  before: Record<string, unknown> | null;
  after: Record<string, unknown> | null;
  changedFields: string[];
}

interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

interface Summary {
  days: number;
  byAction: Record<string, number>;
  byGroup: Record<string, number>;
  actors: string[];
}

// The real Group values, not invented ones. This list was written from a guess at what the
// categories "should" be, and every value in it was wrong -- so the filter silently returned
// nothing while looking like a working dropdown. The server's 400 on an unknown group is the
// reason this was caught rather than shipped.
const GROUPS = [
  'SHOPS',
  'PEOPLE',
  'MONEY',
  'PLANS',
  'COMMERCE',
  'PLATFORM',
  'AUTOMATION',
];

/**
 * The audit log (Phase 7).
 *
 * <p><b>Append-only, and the screen is built around that.</b> There is no edit and no delete
 * anywhere in this platform — not hidden from the UI, absent from the repository. So the UI offers
 * nothing here either: a log that could be edited would be a log nobody could rely on, and the
 * value of one is entirely that it cannot be.
 *
 * <p><b>The diff is the point of the screen.</b> "Wallet top-up approved" tells you that something
 * happened; the before-and-after tells you what changed, which is the only way to answer "was that
 * the right amount". Changed fields come from the server rather than being recomputed here, so the
 * screen and the stored record cannot disagree about what moved.
 *
 * <p><b>An actor that is not a person says so.</b> A scheduled sweep writes rows too, and rendering
 * those as a blank person would read as a missing user rather than a machine.
 */
export function AdminAudit({ auth }: { auth: AuthAdapter }) {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [group, setGroup] = useState('');
  const [days, setDays] = useState('30');
  const [actor, setActor] = useState('');
  const [open, setOpen] = useState<AuditEntry | null>(null);

  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (query) params.set('q', query);
  if (group) params.set('group', group);
  if (days) params.set('days', days);
  if (actor.trim()) params.set('actorId', actor.trim());

  const { data, isLoading, error } = useQuery({
    queryKey: ['admin', 'audit', page, size, query, group, days, actor],
    queryFn: async () => {
      const response = await authorizedFetch(auth, `/api/v1/admin/audit?${params.toString()}`);
      if (!response.ok) throw new Error(await readProblem(response));
      return (await response.json()) as Page<AuditEntry>;
    },
  });

  const { data: summary } = useQuery({
    queryKey: ['admin', 'audit', 'summary', days],
    queryFn: async () => {
      const response = await authorizedFetch(auth, `/api/v1/admin/audit/summary?days=${days}`);
      if (!response.ok) throw new Error(await readProblem(response));
      return (await response.json()) as Summary;
    },
  });

  const columns: DataTableColumn<AuditEntry>[] = [
    {
      key: 'when',
      header: 'When',
      render: (entry) => (
        <span className="whitespace-nowrap text-xs text-muted-foreground">
          {formatDateTime(entry.createdAt, true)}
        </span>
      ),
    },
    {
      key: 'actor',
      header: 'Who',
      render: (entry) =>
        entry.actorEmail ? (
          <div className="min-w-0">
            <p className="truncate text-sm font-medium">{entry.actorEmail}</p>
            <p className="truncate font-mono text-[10px] text-muted-foreground">
              {entry.actorRoles ?? ''}
            </p>
          </div>
        ) : (
          <span className="text-xs text-muted-foreground">Scheduled job</span>
        ),
    },
    {
      key: 'action',
      header: 'Action',
      render: (entry) => (
        <Badge variant="secondary" className="font-mono text-[10px]">
          {entry.action}
        </Badge>
      ),
    },
    {
      key: 'what',
      header: 'What changed',
      render: (entry) => (
        <div className="min-w-0">
          <p className="truncate text-sm">{entry.summary}</p>
          <p className="truncate font-mono text-[10px] text-muted-foreground">
            {entry.entityType} · {entry.entityId.slice(0, 8)}
            {entry.changedFields.length > 0 ? ` · ${entry.changedFields.length} field(s)` : ''}
          </p>
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <header>
        <h1 className="text-2xl font-semibold">Audit log</h1>
        <p className="text-muted-foreground">
          Every consequential action, append-only. Nothing here can be edited or removed — that is
          what makes it worth reading.
        </p>
      </header>

      {summary ? (
        <div className="flex flex-wrap gap-2">
          {Object.entries(summary.byGroup)
            .sort((a, b) => b[1] - a[1])
            .map(([key, count]) => (
              <button
                key={key}
                type="button"
                onClick={() => {
                  setGroup(group === key ? '' : key);
                  setPage(0);
                }}
                aria-pressed={group === key}
                className={cn(
                  'rounded-full border px-3 py-1 text-xs transition-colors',
                  group === key ? 'border-primary bg-primary/10' : 'hover:bg-muted',
                )}
              >
                {key.toLowerCase()}: <strong>{count}</strong>
              </button>
            ))}
        </div>
      ) : null}

      <DataTable<AuditEntry>
        data-testid="admin-audit"
        columns={columns}
        rows={data?.content ?? []}
        rowKey={(entry) => entry.id}
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
        onPageSizeChange={(next) => {
          setSize(next);
          setPage(0);
        }}
        onRowClick={(entry) => setOpen(entry)}
        empty={
          isLoading ? (
            <Skeleton className="h-24 w-full" />
          ) : query || group || actor ? (
            'No entries match these filters.'
          ) : (
            'Nothing has been logged yet.'
          )
        }
        toolbar={
          <div className="flex flex-wrap items-center gap-2 border-b p-3">
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
                placeholder="Search the summary"
                aria-label="Search the audit log"
                className="w-64 pl-8"
              />
            </form>

            <select
              value={group}
              onChange={(event) => {
                setGroup(event.target.value);
                setPage(0);
              }}
              aria-label="Filter by group"
              className="h-9 rounded-md border bg-background px-2 text-sm"
            >
              <option value="">Any group</option>
              {GROUPS.map((option) => (
                <option key={option} value={option}>
                  {option.toLowerCase()}
                </option>
              ))}
            </select>

            <select
              value={days}
              onChange={(event) => {
                setDays(event.target.value);
                setPage(0);
              }}
              aria-label="Window"
              className="h-9 rounded-md border bg-background px-2 text-sm"
            >
              <option value="1">Last day</option>
              <option value="7">Last 7 days</option>
              <option value="30">Last 30 days</option>
              <option value="90">Last 90 days</option>
            </select>

            <Input
              value={actor}
              onChange={(event) => {
                setActor(event.target.value);
                setPage(0);
              }}
              placeholder="Actor id (UUID)"
              aria-label="Filter by actor id"
              className="w-56"
            />

            {query || group || actor ? (
              <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                  setSearch('');
                  setQuery('');
                  setGroup('');
                  setActor('');
                  setPage(0);
                }}
              >
                Clear
              </Button>
            ) : null}
          </div>
        }
      />

      {error ? (
        <Card>
          <CardContent className="p-6 text-sm text-destructive">{error.message}</CardContent>
        </Card>
      ) : null}

      {open ? (
        <Card>
          <CardContent className="space-y-4 p-4">
            <div className="flex items-start justify-between gap-3">
              <div>
                <p className="font-medium">{open.summary}</p>
                <p className="text-xs text-muted-foreground">
                  {formatDateTime(open.createdAt, true)} ·{' '}
                  {open.actorEmail ?? 'Scheduled job'}
                  {open.ipAddress ? ` · ${open.ipAddress}` : ''}
                </p>
              </div>
              <Button variant="ghost" size="sm" onClick={() => setOpen(null)} aria-label="Close">
                <X className="size-4" aria-hidden />
              </Button>
            </div>

            {open.reason ? (
              <p className="rounded-md bg-muted/50 p-3 text-sm">
                <strong>Reason given:</strong> {open.reason}
              </p>
            ) : null}

            {open.changedFields.length > 0 ? (
              <DiffTable entry={open} />
            ) : (
              // Said rather than left blank: an entry with no diff is not a broken screen, it is
              // an action that recorded no field-level change.
              <p className="text-sm text-muted-foreground">
                No field-level change was recorded for this action.
              </p>
            )}
          </CardContent>
        </Card>
      ) : null}
    </div>
  );
}

/**
 * Before and after, field by field.
 *
 * <p><b>Unchanged fields are not shown.</b> `changedFields` comes from the server, and a diff that
 * listed every field of every row would be a table dump with two extra columns — the whole value
 * of this view is that it shows what moved.
 *
 * <p><b>A value that was absent is shown as "not set", not as blank.</b> A blank cell and a null
 * are different states, and in an audit log the difference between "this was cleared" and "this
 * was never filled in" is often the whole question.
 */
function DiffTable({ entry }: { entry: AuditEntry }) {
  return (
    <div className="overflow-hidden rounded-lg border">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b bg-muted/50">
            <th scope="col" className="px-3 py-2 text-left font-medium text-muted-foreground">
              Field
            </th>
            <th scope="col" className="px-3 py-2 text-left font-medium text-muted-foreground">
              Before
            </th>
            <th scope="col" className="px-3 py-2 text-left font-medium text-muted-foreground">
              After
            </th>
          </tr>
        </thead>
        <tbody>
          {entry.changedFields.map((field) => (
            <tr key={field} className="border-b last:border-0">
              <td className="px-3 py-2 font-mono text-xs">{field}</td>
              <td className="px-3 py-2 text-muted-foreground">
                <Value value={entry.before?.[field]} />
              </td>
              <td className="px-3 py-2">
                <Value value={entry.after?.[field]} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** One cell. "not set" is distinct from empty, and the distinction is often the answer. */
function Value({ value }: { value: unknown }) {
  if (value === undefined) {
    return <span className="italic text-muted-foreground">not recorded</span>;
  }
  if (value === null) {
    return <span className="italic text-muted-foreground">not set</span>;
  }
  if (typeof value === 'boolean') {
    return <span>{value ? 'yes' : 'no'}</span>;
  }
  if (typeof value === 'object') {
    return <code className="text-xs">{JSON.stringify(value)}</code>;
  }
  return <span>{String(value)}</span>;
}