import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Search, ShieldAlert, UserCheck } from 'lucide-react';

import { authorizedFetch, postJson } from '@store-app/auth';
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

import { readProblem, relativeTime } from '../lib/format';

/**
 * One account, from either realm.
 *
 * <p>`realm` is here so an admin can see at a glance which Keycloak realm this person is in,
 * rather than inferring it from an email address.
 */
interface UserView {
  id: string;
  realm: 'OPS' | 'CUSTOMERS';
  email: string;
  fullName: string | null;
  status: 'ACTIVE' | 'SUSPENDED' | 'DELETED';
  roles: string | null;
  isAdmin: boolean;
  createdAt: string;
  lastLoginAt: string | null;
}

interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

const REALMS = [
  { value: '', label: 'Both realms' },
  { value: 'OPS', label: 'Operators (ops)' },
  { value: 'CUSTOMERS', label: 'Customers' },
];

const STATUSES = ['ACTIVE', 'SUSPENDED', 'DELETED'];

/**
 * User management (Phase 7).
 *
 * <p><b>Both realms, one list.</b> They are separate Keycloak realms with separate clients and
 * separate audiences, and every other surface in this platform shows exactly one of them. An
 * admin's actual questions cross that line constantly — so a screen showing one realm makes them
 * search the other by hand and guess.
 *
 * <p><b>Suspending an admin is not offered, and the server refuses it too.</b> Otherwise any single
 * compromised admin could lock every operator out of the platform, and there is no recovery path
 * from inside it. Hiding the button is a courtesy; the rule is in the service.
 *
 * <p><b>A suspension needs a reason.</b> A locked account with no recorded reason is one nobody can
 * act on later — the account stays locked and reinstating becomes the only move available to
 * anyone. So the reason is asked for up front rather than discovered afterwards.
 */
export function AdminUsers({ auth }: { auth: AuthAdapter }) {
  const queryClient = useQueryClient();

  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [realm, setRealm] = useState('');
  const [status, setStatus] = useState('');

  const [suspending, setSuspending] = useState<UserView | null>(null);
  const [reason, setReason] = useState('');

  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (query) params.set('q', query);
  if (realm) params.set('realm', realm);
  if (status) params.set('status', status);

  const { data, isLoading, error } = useQuery({
    queryKey: ['admin', 'users', page, size, query, realm, status],
    queryFn: async () => {
      const response = await authorizedFetch(auth, `/api/v1/admin/users?${params.toString()}`);
      if (!response.ok) throw new Error(await readProblem(response));
      return (await response.json()) as Page<UserView>;
    },
  });

  const suspend = useMutation({
    mutationFn: async (payload: { user: UserView; reason: string }) => {
      const response = await postJson(
        auth,
        `/api/v1/admin/users/${payload.user.id}/suspend`,
        { reason: payload.reason },
      );
      if (!response.ok) throw new Error(await readProblem(response));
      return payload.user;
    },
    onSuccess: () => {
      setSuspending(null);
      setReason('');
      void queryClient.invalidateQueries({ queryKey: ['admin', 'users'] });
    },
  });

  const reinstate = useMutation({
    mutationFn: async (user: UserView) => {
      const response = await postJson(auth, `/api/v1/admin/users/${user.id}/reinstate`, {});
      if (!response.ok) throw new Error(await readProblem(response));
      return user;
    },
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['admin', 'users'] }),
  });

  const columns: DataTableColumn<UserView>[] = [
    {
      key: 'person',
      header: 'Person',
      render: (user) => (
        <div className="min-w-0">
          {/* A div, not a p: `Badge` renders a div, and a div inside a p is invalid HTML that
              React reports as a hydration error. The element type is load-bearing here -- changing
              it back to a p reintroduces a console error on every row of this table. */}
          <div className="flex items-center gap-1.5 truncate font-medium">
            <span className="truncate">{user.fullName || user.email}</span>
            {user.isAdmin ? (
              <Badge variant="secondary" className="bg-violet-100 text-violet-800">
                Admin
              </Badge>
            ) : null}
          </div>
          <p className="truncate text-xs text-muted-foreground">{user.email}</p>
        </div>
      ),
    },
    {
      key: 'realm',
      header: 'Realm',
      render: (user) => (
        <span className="text-xs text-muted-foreground">
          {user.realm === 'OPS' ? 'Operators (ops)' : 'Customers'}
        </span>
      ),
    },
    {
      key: 'roles',
      header: 'Roles',
      render: (user) => (
        <span className="font-mono text-xs text-muted-foreground">
          {user.roles ?? '— not signed in yet'}
        </span>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (user) => (
        <Badge
          variant="secondary"
          className={
            user.status === 'ACTIVE'
              ? 'bg-emerald-100 text-emerald-800'
              : user.status === 'SUSPENDED'
                ? 'bg-red-100 text-red-800'
                : 'bg-muted text-muted-foreground'
          }
        >
          {user.status.toLowerCase()}
        </Badge>
      ),
    },
    {
      key: 'lastLogin',
      header: 'Last seen',
      render: (user) => (
        <span className="text-xs text-muted-foreground">
          {user.lastLoginAt ? relativeTime(user.lastLoginAt) : 'never'}
        </span>
      ),
    },
    {
      key: 'actions',
      header: 'Actions',
      headerClassName: 'text-right',
      className: 'text-right',
      render: (user) => {
        // Offered only where it can succeed. The server enforces this too; a button that always
        // fails is worse than one that is not shown.
        if (user.isAdmin) {
          return (
            <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
              <ShieldAlert className="size-3.5" aria-hidden />
              Admins cannot be suspended
            </span>
          );
        }
        return user.status === 'SUSPENDED' ? (
          <Button
            size="sm"
            variant="outline"
            disabled={reinstate.isPending}
            onClick={() => reinstate.mutate(user)}
          >
            <UserCheck className="mr-1.5 size-3.5" aria-hidden />
            Reinstate
          </Button>
        ) : (
          <Button
            size="sm"
            variant="ghost"
            disabled={user.status === 'DELETED'}
            onClick={() => {
              setSuspending(user);
              setReason('');
            }}
          >
            Suspend
          </Button>
        );
      },
    },
  ];

  return (
    <div className="space-y-4">
      <header>
        <h1 className="text-2xl font-semibold">People</h1>
        <p className="text-muted-foreground">
          Every account in both Keycloak realms. Roles come from the token at last sign-in, so an
          account that has never signed in has none.
        </p>
      </header>

      {(suspend.error || reinstate.error) && (
        <p
          role="alert"
          className="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm"
        >
          {(suspend.error ?? reinstate.error)?.message}
        </p>
      )}

      <DataTable<UserView>
        data-testid="admin-users"
        columns={columns}
        rows={data?.content ?? []}
        rowKey={(user) => user.id}
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
        empty={
          isLoading ? (
            <Skeleton className="h-24 w-full" />
          ) : query || realm || status ? (
            'No accounts match these filters.'
          ) : (
            'No accounts yet.'
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
                placeholder="Search email or name"
                aria-label="Search accounts"
                className="w-64 pl-8"
              />
            </form>

            <select
              value={realm}
              onChange={(event) => {
                setRealm(event.target.value);
                setPage(0);
              }}
              aria-label="Filter by realm"
              className="h-9 rounded-md border bg-background px-2 text-sm"
            >
              {REALMS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>

            <select
              value={status}
              onChange={(event) => {
                setStatus(event.target.value);
                setPage(0);
              }}
              aria-label="Filter by status"
              className="h-9 rounded-md border bg-background px-2 text-sm"
            >
              <option value="">Any status</option>
              {STATUSES.map((option) => (
                <option key={option} value={option}>
                  {option.toLowerCase()}
                </option>
              ))}
            </select>

            {query || realm || status ? (
              <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                  setSearch('');
                  setQuery('');
                  setRealm('');
                  setStatus('');
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

      {suspending ? (
        <Card>
          <CardContent className="space-y-3 p-4">
            <p className="text-sm font-medium">
              Suspend {suspending.fullName || suspending.email}
            </p>
            <p className="text-sm text-muted-foreground">
              They will not be able to sign in or act on this platform. Nothing in Keycloak is
              deleted — this is a platform decision, and it is reversible.
            </p>
            <label className="block text-sm">
              <span className="font-medium">Reason (recorded in the audit log)</span>
              <Input
                value={reason}
                onChange={(event) => setReason(event.target.value)}
                placeholder="Why are they being suspended?"
                className="mt-1"
                maxLength={500}
              />
            </label>
            <div className="flex gap-2">
              <Button
                disabled={reason.trim().length === 0 || suspend.isPending}
                onClick={() => suspend.mutate({ user: suspending, reason: reason.trim() })}
              >
                Suspend
              </Button>
              <Button variant="ghost" onClick={() => setSuspending(null)}>
                Cancel
              </Button>
            </div>
            {reason.trim().length === 0 ? (
              // Said here rather than left to a 400: the rule is simple and the reason for it
              // matters to whoever is being asked to type one.
              <p className="text-xs text-muted-foreground">
                A reason is required. Without it, the next operator has a locked account and no way
                to tell whether lifting the lock is right.
              </p>
            ) : null}
          </CardContent>
        </Card>
      ) : null}
    </div>
  );
}