import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { EllipsisVertical, Eye, Pencil, Plus, Search, Share2, SlidersHorizontal } from 'lucide-react';

import { ShareSheet } from '../components/share-sheet';

import { authorizedFetch } from '@store-app/auth';
import type { AuthAdapter } from '@store-app/auth';
import { Button, Checkbox, DataTable, DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger, Input, Switch } from '@store-app/ui';
import { cn } from '@store-app/ui';

/**
 * The product list (Phase 2 catalogue, Phase 4 shell).
 *
 * <p>Replicated from the reference: search, Add new product, Sort by / Filter, and the product
 * table with a row checkbox, price with its struck-through compare-at, stock, a live status
 * toggle and per-row actions. <b>Pagination is added</b> — the reference lists every product, and
 * this project has already seeded over a thousand of them.
 */

interface ProductRow {
  id: string;
  name: string;
  slug: string;
  category: string;
  categoryLabel: string;
  status: string;
  price: number;
  compareAt?: number | null;
  hasDiscount: boolean;
  stock: number;
  variantCount: number;
  featured: boolean;
}

interface ProductPage {
  content: ProductRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

const STATUSES = [
  { key: null, label: 'All' },
  { key: 'ACTIVE', label: 'Live' },
  { key: 'DRAFT', label: 'Draft' },
  { key: 'ARCHIVED', label: 'Archived' },
] as const;

export function ShopProducts({ auth }: { auth: AuthAdapter }) {
  const [params, setParams] = useSearchParams();
  const [search, setSearch] = useState(params.get('q') ?? '');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  /** The product whose share sheet is open, or null. */
  const [sharing, setSharing] = useState<string | null>(null);

  const status = params.get('status');
  const page = Number(params.get('page') ?? 0);
  const size = Number(params.get('size') ?? 20);
  const q = params.get('q') ?? '';
  const sort = params.get('sort') ?? 'newest';

  const setParam = (patch: Record<string, string | number | null>) => {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(patch)) {
      if (value === null || value === '') next.delete(key);
      else next.set(key, String(value));
    }
    setParams(next, { replace: true });
  };

  const { data, isLoading } = useQuery({
    queryKey: ['products', status, page, size, q, sort],
    queryFn: async () => {
      const query = new URLSearchParams({ page: String(page), size: String(size) });
      if (status) query.set('status', status);
      if (q) query.set('q', q);
      if (sort) query.set('sort', sort);
      const response = await authorizedFetch(auth, `/api/v1/shop/products?${query}`);
      if (!response.ok) throw new Error(`Products request failed: ${response.status}`);
      return (await response.json()) as ProductPage;
    },
    placeholderData: (previous) => previous,
  });

  const rows = data?.content ?? [];
  const allSelected = rows.length > 0 && rows.every((row) => selected.has(row.id));

  const columns = useMemo(
    () => [
      {
        key: 'select',
        header: (
          <Checkbox
            aria-label="Select all on this page"
            checked={allSelected}
            // Only the visible page can be selected. A "select all" that silently covered rows the
            // owner cannot see is how bulk actions end up hitting things nobody chose.
            onCheckedChange={(checked) =>
              setSelected(checked ? new Set(rows.map((r) => r.id)) : new Set())
            }
          />
        ),
        className: 'w-10',
        render: (row: ProductRow) => (
          <Checkbox
            aria-label={`Select ${row.name}`}
            checked={selected.has(row.id)}
            onCheckedChange={(checked) => {
              const next = new Set(selected);
              if (checked) next.add(row.id);
              else next.delete(row.id);
              setSelected(next);
            }}
          />
        ),
      },
      {
        key: 'product',
        header: 'Product',
        render: (row: ProductRow) => (
          <div className="flex items-center gap-3">
            <div className="grid size-11 shrink-0 place-items-center overflow-hidden rounded-md bg-muted text-xs text-muted-foreground">
              {row.name.slice(0, 1).toUpperCase()}
            </div>
            <div className="min-w-0">
              <p className="truncate font-medium">{row.name}</p>
              <p className="truncate text-xs text-muted-foreground">{row.slug}</p>
            </div>
          </div>
        ),
      },
      {
        key: 'price',
        header: 'Price',
        className: 'whitespace-nowrap',
        render: (row: ProductRow) => (
          <div>
            <p className="font-medium">{formatPrice(row.price)}</p>
            {row.hasDiscount && row.compareAt ? (
              <p className="text-xs text-muted-foreground line-through">{formatPrice(row.compareAt)}</p>
            ) : null}
          </div>
        ),
      },
      {
        key: 'stock',
        header: 'Stock',
        className: 'whitespace-nowrap font-medium text-emerald-700',
        render: (row: ProductRow) => row.stock.toLocaleString(),
      },
      {
        key: 'status',
        header: 'Status',
        className: 'whitespace-nowrap',
        render: (row: ProductRow) => (
          <span className="inline-flex items-center gap-2">
            <Switch
              aria-label={`${row.status === 'ACTIVE' ? 'Deactivate' : 'Activate'} ${row.name}`}
              checked={row.status === 'ACTIVE'}
              // Read-only here. Toggling needs the publish rules (a product needs a priced
              // variant), so it belongs on the product's own screen where those can be explained,
              // not as an unvalidated toggle in a table.
              disabled
            />
            <span className={cn(row.status === 'ACTIVE' ? 'text-emerald-600' : 'text-muted-foreground')}>
              {row.status === 'ACTIVE' ? 'Active' : row.status === 'DRAFT' ? 'Draft' : 'Archived'}
            </span>
          </span>
        ),
      },
      {
        key: 'action',
        header: <span className="sr-only">Actions</span>,
        className: 'text-right whitespace-nowrap',
        render: (row: ProductRow) => (
          <span className="inline-flex items-center gap-1">
            <a
              href={`/shop/products/${row.id}`}
              aria-label={`View ${row.name}`}
              className="rounded p-1.5 text-muted-foreground hover:bg-muted hover:text-foreground"
            >
              <Eye className="size-4" aria-hidden />
            </a>
            <a
              href={`/shop/products/${row.id}`}
              aria-label={`Edit ${row.name}`}
              className="rounded p-1.5 text-muted-foreground hover:bg-muted hover:text-foreground"
            >
              <Pencil className="size-4" aria-hidden />
            </a>
            {/* Opens the real share sheet. This used to be an anchor to
                `/s/your-shop/<slug>` -- a literal placeholder string, so it linked nowhere useful
                and looked like it worked. */}
            <button
              type="button"
              onClick={() => setSharing(row.id)}
              aria-label={`Share ${row.name}`}
              className="rounded p-1.5 text-muted-foreground hover:bg-muted hover:text-foreground"
            >
              <Share2 className="size-4" aria-hidden />
            </button>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button
                  type="button"
                  aria-label={`More actions for ${row.name}`}
                  className="rounded p-1.5 text-muted-foreground hover:bg-muted hover:text-foreground"
                >
                  <EllipsisVertical className="size-4" aria-hidden />
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <DropdownMenuItem onSelect={() => setParam({ status: 'DRAFT' })}>
                  Duplicate
                </DropdownMenuItem>
                <DropdownMenuItem onSelect={() => setParam({ status: 'ARCHIVED' })}>
                  Archive
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </span>
        ),
      },
    ],
    [allSelected, rows, selected, setParam],
  );

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <form
          className="relative min-w-[280px] flex-1"
          onSubmit={(e) => {
            e.preventDefault();
            setParam({ q: search.trim() || null, page: 0 });
          }}
        >
          <Search
            className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
            aria-hidden
          />
          <Input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search products by name"
            aria-label="Search products"
            className="pl-9"
          />
        </form>

        <a
          href="/shop/products/new"
          className="inline-flex h-9 items-center gap-2 rounded-md bg-neutral-900 px-4 text-sm font-medium text-white transition-colors hover:bg-neutral-800"
        >
          <Plus className="size-4" aria-hidden />
          Add new product
        </a>

        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="outline">
              <SlidersHorizontal className="size-4" aria-hidden />
              Sort by
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            {(
              [
                ['newest', 'Newest'],
                ['name', 'Name'],
                ['price', 'Price'],
                ['stock', 'Stock'],
              ] as Array<[string, string]>
            ).map(([value, label]) => (
              <DropdownMenuItem key={value} onSelect={() => setParam({ sort: value, page: 0 })}>
                {label}
                {sort === value ? <span className="ml-auto text-xs text-muted-foreground">current</span> : null}
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>

        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="outline">
              <SlidersHorizontal className="size-4" aria-hidden />
              Filter
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            {STATUSES.map((option) => (
              <DropdownMenuItem
                key={option.label}
                onSelect={() => setParam({ status: option.key, page: 0 })}
              >
                {option.label}
                {((option.key ?? null) === (status as never)) ? (
                  <span className="ml-auto text-xs text-muted-foreground">current</span>
                ) : null}
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>
      </div>

      {sharing ? (
        <ShareSheet
          auth={auth}
          productId={sharing}
          productName={
            rows?.find((row: { id: string; name: string }) => row.id === sharing)?.name ?? 'product'
          }
          onClose={() => setSharing(null)}
        />
      ) : null}

      {selected.size > 0 ? (
        <div className="flex items-center gap-3 rounded-lg border bg-primary/5 px-4 py-2 text-sm">
          <span className="font-medium">{selected.size} selected</span>
          <span className="text-muted-foreground">
            Bulk actions apply to this page only.
          </span>
          <button
            type="button"
            className="ml-auto text-muted-foreground hover:text-foreground"
            onClick={() => setSelected(new Set())}
          >
            Clear
          </button>
        </div>
      ) : null}

      <DataTable
        data-testid="products-table"
        columns={columns}
        rows={rows}
        rowKey={(row) => row.id}
        isRowSelected={(row) => selected.has(row.id)}
        meta={data ? {
          page: data.page,
          size: data.size,
          totalElements: data.totalElements,
          totalPages: data.totalPages,
        } : undefined}
        onPageChange={(next) => setParam({ page: next })}
        onPageSizeChange={(next) => setParam({ size: next, page: 0 })}
        empty={
          q ? `No products match "${q}".` : 'No products yet. Add your first one to start selling.'
        }
      />
      {isLoading ? <p className="text-xs text-muted-foreground">Loading…</p> : null}
    </div>
  );
}

function formatPrice(minor: number): string {
  return `NRP ${(minor / 100).toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
}
