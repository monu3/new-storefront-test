'use client';

import { useId } from 'react';
import { ChevronLeft, ChevronRight } from 'lucide-react';

import { cn } from './lib/utils';

/**
 * The shared table shell with pagination (Phase 4).
 *
 * <p><b>Pagination is part of this component, not something each page adds.</b> Every list in this
 * product grows without bound — orders, products, audience, customers — and a table that renders
 * every row is a table that eventually stops rendering. Putting it here means a page cannot forget
 * it, which is exactly how the reference ended up with unpaged lists.
 *
 * <p><b>The page is a controlled prop, not internal state.</b> The URL carries it, so a page an
 * owner bookmarked or shared opens on the same rows they were looking at, and the browser's back
 * button steps through pages they actually visited.
 *
 * <p><b>The range is shown, not just the arrows.</b> "Page 3" with no context does not tell an
 * owner whether to go back or forward; "Showing 41–60 of 218" does.
 */
export interface PageMeta {
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface DataTableColumn<T> {
  key: string;
  header: React.ReactNode;
  render: (row: T) => React.ReactNode;
  /** Applied to every cell in the column, for alignment and width. */
  className?: string;
  headerClassName?: string;
}

export interface DataTableProps<T> {
  columns: DataTableColumn<T>[];
  rows: T[];
  /** Row key. Falls back to the index when a row has no stable id. */
  rowKey?: (row: T, index: number) => string;
  meta?: PageMeta;
  onPageChange?: (page: number) => void;
  onPageSizeChange?: (size: number) => void;
  /** Page-size choices. */
  pageSizes?: number[];
  /** Shown when there are no rows. */
  empty?: React.ReactNode;
  /** Rendered above the table — search, filters, bulk actions. */
  toolbar?: React.ReactNode;
  /** Rendered under the body, replacing the default pagination. */
  footer?: React.ReactNode;
  className?: string;
  isRowSelected?: (row: T) => boolean;
  onRowClick?: (row: T) => void;
  'data-testid'?: string;
}

export function DataTable<T>({
  columns,
  rows,
  rowKey,
  meta,
  onPageChange,
  onPageSizeChange,
  pageSizes = [10, 20, 50, 100],
  empty,
  toolbar,
  footer,
  className,
  isRowSelected,
  onRowClick,
  'data-testid': testId,
}: DataTableProps<T>) {
  const id = useId();

  return (
    <div className={cn('rounded-xl border bg-card', className)} data-testid={testId}>
      {toolbar}
      <div className="overflow-x-auto">
        <table className="w-full border-collapse text-sm">
          <thead>
            <tr className="border-b bg-muted/50">
              {columns.map((column) => (
                <th
                  key={column.key}
                  scope="col"
                  className={cn(
                    'px-4 py-3 text-left font-medium text-muted-foreground',
                    column.headerClassName ?? column.className,
                  )}
                >
                  {column.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr>
                <td
                  colSpan={columns.length}
                  className="px-4 py-12 text-center text-sm text-muted-foreground"
                >
                  {empty ?? 'Nothing here yet.'}
                </td>
              </tr>
            ) : (
              rows.map((row, index) => (
                <tr
                  key={rowKey?.(row, index) ?? String(index)}
                  data-selected={isRowSelected?.(row) || undefined}
                  onClick={onRowClick ? () => onRowClick(row) : undefined}
                  className={cn(
                    'border-b transition-colors last:border-0',
                    isRowSelected?.(row) && 'bg-primary/5',
                    onRowClick && 'cursor-pointer hover:bg-muted/40',
                  )}
                >
                  {columns.map((column) => (
                    <td key={column.key} className={cn('px-4 py-3 align-middle', column.className)}>
                      {column.render(row)}
                    </td>
                  ))}
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
      <div className="flex flex-wrap items-center justify-between gap-3 border-t px-4 py-3">
        <p className="text-xs text-muted-foreground" data-testid={`${testId}-summary`}>
          {rangeLabel(meta, rows.length)}
        </p>
        {footer ?? (
          <Pagination
            meta={meta}
            onPageChange={onPageChange}
            onPageSizeChange={onPageSizeChange}
            pageSizes={pageSizes}
            id={id}
          />
        )}
      </div>
    </div>
  );
}

/**
 * The page controls.
 *
 * <p>First/previous/next/last plus a direct page box, because an owner with forty pages of orders
 * does not want to click next thirty times to reach the one they were told about.
 *
 * <p>Disabled at the ends rather than a silent no-op, so it is obvious there is nothing further.
 */
export function Pagination({
  meta,
  onPageChange,
  onPageSizeChange,
  pageSizes = [10, 20, 50, 100],
  id,
}: {
  meta?: PageMeta;
  onPageChange?: (page: number) => void;
  onPageSizeChange?: (size: number) => void;
  pageSizes?: number[];
  id?: string;
}) {
  if (!meta || !onPageChange) return null;
  const { page, totalPages, totalElements, size } = meta;
  const atStart = page <= 0;
  const atEnd = page >= Math.max(totalPages - 1, 0);

  return (
    <div className="flex items-center gap-3" data-testid="pagination">
      {onPageSizeChange && pageSizes.length > 1 ? (
        <label className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <span className="sr-only">Rows per page</span>
          <select
            aria-label="Rows per page"
            id={id ? `${id}-size` : undefined}
            value={size}
            onChange={(e) => onPageSizeChange(Number(e.target.value))}
            className="h-8 rounded-md border bg-background px-2 text-xs"
          >
            {pageSizes.map((n) => (
              <option key={n} value={n}>
                {n} / page
              </option>
            ))}
          </select>
        </label>
      ) : null}

      <div className="flex items-center gap-1">
        <PageButton onClick={() => onPageChange(0)} disabled={atStart} label="First page">
          &laquo;
        </PageButton>
        <PageButton onClick={() => onPageChange(page - 1)} disabled={atStart} label="Previous page">
          <ChevronLeft className="size-4" aria-hidden />
        </PageButton>

        <label className="flex items-center gap-1 text-xs text-muted-foreground">
          <span className="sr-only">Current page</span>
          <input
            type="number"
            aria-label="Current page"
            min={1}
            max={Math.max(totalPages, 1)}
            value={page + 1}
            onChange={(e) => {
              const next = Number(e.target.value) - 1;
              if (Number.isFinite(next)) {
                onPageChange(Math.min(Math.max(next, 0), Math.max(totalPages - 1, 0)));
              }
            }}
            className="h-8 w-14 rounded-md border bg-background px-2 text-center text-xs"
          />
          <span>of {Math.max(totalPages, 1)}</span>
        </label>

        <PageButton onClick={() => onPageChange(page + 1)} disabled={atEnd} label="Next page">
          <ChevronRight className="size-4" aria-hidden />
        </PageButton>
        <PageButton onClick={() => onPageChange(totalPages - 1)} disabled={atEnd} label="Last page">
          &raquo;
        </PageButton>
      </div>

      {totalElements > 0 ? (
        <span className="text-xs text-muted-foreground">{totalElements.toLocaleString()} total</span>
      ) : null}
    </div>
  );
}

function PageButton({
  children,
  onClick,
  disabled,
  label,
}: {
  children: React.ReactNode;
  onClick: () => void;
  disabled: boolean;
  label: string;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      onClick={onClick}
      disabled={disabled}
      className={cn(
        'grid size-8 place-items-center rounded-md border text-sm transition-colors',
        disabled ? 'cursor-not-allowed text-muted-foreground/40' : 'hover:bg-muted',
      )}
    >
      {children}
    </button>
  );
}

/** "Showing 41–60 of 218", or a plain statement when there is nothing to show. */
function rangeLabel(meta: PageMeta | undefined, rowCount: number): string {
  if (!meta || meta.totalElements === 0) {
    return rowCount > 0 ? `Showing ${rowCount}` : 'No results';
  }
  const from = meta.page * meta.size + 1;
  const to = Math.min(from + rowCount - 1, meta.totalElements);
  return `Showing ${from.toLocaleString()}–${to.toLocaleString()} of ${meta.totalElements.toLocaleString()}`;
}
