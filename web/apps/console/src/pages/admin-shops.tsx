import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Search, Star, StarOff, Store } from 'lucide-react';

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

import { formatDateTime, readProblem } from '../lib/format';

/**
 * One shop in the admin's list.
 *
 * <p>`featured` is on the row rather than bought: an editorial decision is not an entitlement, and
 * merging the two would let subscription money buy a place on the front page.
 */
interface ShopView {
  id: string;
  name: string;
  slug: string;
  shopType: string;
  shopTypeLabel: string;
  status: string;
  featured: boolean;
  ownerEmail: string | null;
  createdAt: string;
  verifiedAt: string | null;
  commissionPlan: string;
}

interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

const STATUSES = ['DRAFT', 'PENDING_REVIEW', 'VERIFIED', 'REJECTED', 'SUSPENDED'];

/**
 * Shop management (Phase 7).
 *
 * <p><b>Filtered and paged in the query, not in the browser.</b> Phase 1 shipped this list as
 * "every shop, loaded at once", which was fine with three shops and wrong with three thousand. An
 * admin's shop list is the screen they open first and the one that grows fastest, and a list that
 * loads the whole table to display twenty stops working at exactly the wrong moment.
 *
 * <p><b>Featuring is offered only for a verified shop.</b> The server refuses it either way; the
 * button is hidden rather than left to fail, because a button that always errors is worse than one
 * that is not offered.
 */
export function AdminShops({ auth }: { auth: AuthAdapter }) {
  const queryClient = useQueryClient();

  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [type, setType] = useState('');
  const [status, setStatus] = useState('');
  const [featured, setFeatured] = useState('');

  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (query) params.set('q', query);
  if (type) params.set('type', type);
  if (status) params.set('status', status);
  if (featured) params.set('featured', featured);

  const { data, isLoading, error } = useQuery({
    queryKey: ['admin', 'shops', page, size, query, type, status, featured],
    queryFn: async () => {
      const response = await authorizedFetch(auth, `/api/v1/admin/shops?${params.toString()}`);
      if (!response.ok) throw new Error(await readProblem(response));
      return (await response.json()) as Page<ShopView>;
    },
  });

  const feature = useMutation({
    mutationFn: async (shop: ShopView) => {
      const response = await postJson(
        auth,
        `/api/v1/admin/shops/${shop.id}/feature`,
        {},
        shop.featured ? 'DELETE' : 'POST',
      );
      if (!response.ok) throw new Error(await readProblem(response));
      return shop;
    },
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['admin', 'shops'] }),
  });

  const columns: DataTableColumn<ShopView>[] = [
    {
      key: 'name',
      header: 'Shop',
      render: (shop) => (
        <div className="min-w-0">
          <p className="flex items-center gap-1.5 truncate font-medium">
            {shop.name}
            {shop.featured ? (
              <Star className="size-3.5 fill-amber-400 text-amber-500" aria-label="Featured" />
            ) : null}
          </p>
          <p className="truncate font-mono text-xs text-muted-foreground">{shop.slug}</p>
        </div>
      ),
    },
    {
      key: 'type',
      header: 'Type',
      render: (shop) => <span className="text-sm">{shop.shopTypeLabel}</span>,
    },
    {
      key: 'status',
      header: 'Status',
      render: (shop) => <StatusBadge status={shop.status} />,
    },
    {
      key: 'commission',
      header: 'Commission',
      render: (shop) => (
        <span className="text-xs text-muted-foreground">
          {shop.commissionPlan === 'SUBSCRIBED' ? 'Waived (subscribed)' : `${shop.commissionPlan} — 5%`}
        </span>
      ),
    },
    {
      key: 'created',
      header: 'Created',
      render: (shop) => (
        <span className="text-xs text-muted-foreground">{formatDateTime(shop.createdAt)}</span>
      ),
    },
    {
      key: 'actions',
      header: 'Gallery',
      headerClassName: 'text-right',
      className: 'text-right',
      render: (shop) =>
        shop.status === 'VERIFIED' ? (
          <Button
            size="sm"
            variant={shop.featured ? 'outline' : 'ghost'}
            disabled={feature.isPending}
            onClick={() => feature.mutate(shop)}
            aria-label={shop.featured ? `Remove ${shop.name} from the gallery` : `Feature ${shop.name}`}
          >
            {shop.featured ? (
              <>
                <StarOff className="mr-1.5 size-3.5" aria-hidden />
                Unfeature
              </>
            ) : (
              <>
                <Star className="mr-1.5 size-3.5" aria-hidden />
                Feature
              </>
            )}
          </Button>
        ) : (
          // Said rather than shown-and-failing: the server refuses to feature an unverified shop,
          // and the reason it is not verified is one the owner has to fix.
          <span className="text-xs text-muted-foreground">
            {shop.status === 'VERIFIED' ? '' : `Unavailable while ${shop.status.toLowerCase()}`}
          </span>
        ),
    },
  ];

  return (
    <div className="space-y-4">
      <header>
        <h1 className="text-2xl font-semibold">Shops</h1>
        <p className="text-muted-foreground">
          Every shop on the platform. Featuring puts a verified shop at the top of the gallery — it
          is an editorial decision, not something a shop can buy.
        </p>
      </header>

      {feature.error ? (
        <p
          role="alert"
          className="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm"
        >
          {feature.error.message}
        </p>
      ) : null}

      <DataTable<ShopView>
        data-testid="admin-shops"
        columns={columns}
        rows={data?.content ?? []}
        rowKey={(shop) => shop.id}
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
          ) : query || type || status || featured ? (
            'No shops match these filters.'
          ) : (
            <span className="inline-flex items-center gap-2">
              <Store className="size-4" aria-hidden />
              No shops yet.
            </span>
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
                placeholder="Search name or slug"
                aria-label="Search shops"
                className="w-64 pl-8"
              />
            </form>

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
                  {option.replace('_', ' ').toLowerCase()}
                </option>
              ))}
            </select>

            <select
              value={featured}
              onChange={(event) => {
                setFeatured(event.target.value);
                setPage(0);
              }}
              aria-label="Filter by gallery"
              className="h-9 rounded-md border bg-background px-2 text-sm"
            >
              <option value="">Featured and unfeatured</option>
              <option value="true">Featured only</option>
              <option value="false">Unfeatured only</option>
            </select>

            {query || type || status || featured ? (
              <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                  setSearch('');
                  setQuery('');
                  setType('');
                  setStatus('');
                  setFeatured('');
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
    </div>
  );
}

/** Status as a colour, with the word always present — colour alone is not information. */
function StatusBadge({ status }: { status: string }) {
  const variant =
    status === 'VERIFIED'
      ? 'bg-emerald-100 text-emerald-800'
      : status === 'SUSPENDED'
        ? 'bg-red-100 text-red-800'
        : status === 'REJECTED'
          ? 'bg-orange-100 text-orange-800'
          : status === 'PENDING_REVIEW'
            ? 'bg-amber-100 text-foreground'
            : 'bg-muted text-muted-foreground';

  return (
    <Badge className={variant} variant="secondary">
      {status.replace('_', ' ').toLowerCase()}
    </Badge>
  );
}