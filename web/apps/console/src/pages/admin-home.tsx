import { useShopTypes } from '@store-app/enums';
import { shopTypeIcon } from '@store-app/icons';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@store-app/ui';

/**
 * Admin landing page.
 *
 * The shop-type list is live from the API rather than hardcoded, which is the point of the
 * `@store-app/enums` package: the Java enum stays the single source of truth, and adding a
 * vertical needs no frontend change.
 */
export function AdminHome() {
  const { data: shopTypes, isLoading, error } = useShopTypes();

  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-2xl font-semibold">Admin</h1>
        <p className="text-muted-foreground">
          Verify shops, manage plans, and watch revenue.
        </p>
      </header>

      <Card>
        <CardHeader>
          <CardTitle>Shop types</CardTitle>
          <CardDescription>
            {shopTypes?.length ?? 0} verticals are available. These come from the backend enum,
            so a new vertical needs no frontend change.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {isLoading && <p className="text-sm text-muted-foreground">Loading…</p>}
          {error && (
            <p className="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm">
              Could not load shop types. Is the API running on port 8080?
            </p>
          )}
          {shopTypes && (
            <ul className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
              {shopTypes.map((type) => {
                const Icon = shopTypeIcon(type.icon);
                return (
                  <li
                    key={type.code}
                    className="flex items-center gap-3 rounded-md border p-3"
                  >
                    <Icon className="h-5 w-5 shrink-0 text-muted-foreground" aria-hidden />
                    <div className="min-w-0">
                      <p className="truncate text-sm font-medium">{type.label}</p>
                      <p className="text-xs text-muted-foreground">
                        {type.categories.length} categories
                      </p>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Coming next</CardTitle>
        </CardHeader>
        <CardContent className="space-y-1 text-sm text-muted-foreground">
          <p>Shop verification queue, user management, plans and entitlements.</p>
          <p>Wallet approvals, revenue dashboard, theme template publishing.</p>
          <p>Reports, audit log, and the OpenAPI data explorer (Phase 7).</p>
        </CardContent>
      </Card>
    </div>
  );
}
