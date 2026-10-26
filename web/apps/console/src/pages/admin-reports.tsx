import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Download, Play } from 'lucide-react';

import { authorizedFetch } from '@store-app/auth';
import type { AuthAdapter } from '@store-app/auth';
import {
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Skeleton,
} from '@store-app/ui';

import { readProblem } from '../lib/format';

/**
 * The reports Phase 7 offers.
 *
 * <p>`capped` is shown in the description rather than hidden, because a report that silently
 * stops at 1,000 rows produces a file that looks complete and is not — and the person who finds
 * out is an accountant reconciling it.
 */
interface ReportDefinition {
  id: string;
  label: string;
  description: string;
  /** Present only where the server caps the row count; shown in the UI, not hidden. */
  capped?: string;
}

const REPORTS: ReportDefinition[] = [
  {
    id: 'sales',
    label: 'Sales',
    description: 'Every order in the window: what was bought, at what discount, tax and total.',
    capped: 'Capped at the row limit you choose.',
  },
  {
    id: 'products',
    label: 'Products',
    description: 'What sold, by units and revenue. A leaderboard, so it is capped.',
    capped: 'Capped at the row limit you choose.',
  },
  {
    id: 'shops',
    label: 'Shops',
    description: 'Every non-draft shop with its GMV. Shops that sold nothing are still listed.',
  },
  {
    id: 'types',
    label: 'By shop type',
    description: 'The revenue split by vertical, with commission as a share of GMV.',
  },
  {
    id: 'taxes',
    label: 'Tax',
    description: "Tax collected, read from the orders' own frozen figures rather than today's rate.",
  },
  {
    id: 'payouts',
    label: 'Payouts',
    description: 'Per shop: what it owes in commission, what it paid in, and what remains.',
  },
  {
    id: 'ledger',
    label: 'Wallet ledger',
    description: 'The rows behind the balances. The money that actually moved.',
    capped: 'Capped at the row limit you choose.',
  },
];

/**
 * Reports and CSV export (Phase 7).
 *
 * <p><b>The CSV is rendered from the same rows the screen shows.</b> A separate streaming export is
 * a second implementation of the same numbers, and two implementations that produce different
 * figures is how a report and its export come to disagree — with nobody noticing until an
 * accountant reconciles them.
 *
 * <p><b>The download carries the caller's own token.</b> An export that needed a second
 * authentication would be exported differently from the report beside it, and the difference would
 * show up in the numbers.
 */
export function AdminReports({ auth }: { auth: AuthAdapter }) {
  const [report, setReport] = useState<string>('types');
  const [days, setDays] = useState(30);
  const [limit, setLimit] = useState(1000);
  const [running, setRunning] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  const selected = REPORTS.find((option) => option.id === report);

  const params = new URLSearchParams({
    days: String(days),
    limit: String(limit),
    format: 'json',
  });

  // `enabled: false`, so changing a filter does not fire a heavy aggregate before it is asked for.
  // The Preview button is what runs it -- and the CSV download does not depend on this query at
  // all, so an export never waits on a preview.
  const { data, isLoading, error: previewError, refetch } = useQuery({
    queryKey: ['admin', 'report', report, days, limit],
    queryFn: async () => {
      const response = await authorizedFetch(
        auth,
        `/api/v1/admin/reports/${report}?${params.toString()}`,
      );
      if (!response.ok) throw new Error(await readProblem(response));
      return (await response.json()) as Record<string, unknown>[];
    },
    enabled: false,
  });

  /**
   * Downloads the CSV.
   *
   * <p>Fetches with the bearer token and builds the object URL by hand rather than using a plain
   * `<a download>`: an anchor cannot carry an `Authorization` header, so the browser would get a
   * 401 and download a file containing the words "Unauthorized".
   */
  async function downloadCsv() {
    setRunning(true);
    setDownloadError(null);
    try {
      const params = new URLSearchParams({
        days: String(days),
        limit: String(limit),
        format: 'csv',
      });
      const response = await authorizedFetch(
        auth,
        `/api/v1/admin/reports/${report}?${params.toString()}`,
      );
      if (!response.ok) throw new Error(await readProblem(response));

      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = `${report}-${new Date().toISOString().slice(0, 10)}.csv`;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      // Revoked immediately: a blob URL left alive holds its contents in memory for the life of
      // the tab, and a finance export is not something to keep lying around.
      URL.revokeObjectURL(url);
    } catch (problem) {
      setDownloadError(problem instanceof Error ? problem.message : String(problem));
    } finally {
      setRunning(false);
    }
  }

  return (
    <div className="space-y-4">
      <header>
        <h1 className="text-2xl font-semibold">Reports</h1>
        <p className="text-muted-foreground">
          Every report has a CSV export rendered from the same rows shown here, so the two cannot
          disagree.
        </p>
      </header>

      <div className="flex flex-wrap items-center gap-2">
        <select
          value={report}
          onChange={(event) => setReport(event.target.value)}
          aria-label="Report"
          className="h-9 rounded-md border bg-background px-2 text-sm"
        >
          {REPORTS.map((option) => (
            <option key={option.id} value={option.id}>
              {option.label}
            </option>
          ))}
        </select>

        <select
          value={days}
          onChange={(event) => setDays(Number(event.target.value))}
          aria-label="Window in days"
          className="h-9 rounded-md border bg-background px-2 text-sm"
        >
          <option value={7}>Last 7 days</option>
          <option value={30}>Last 30 days</option>
          <option value={90}>Last 90 days</option>
          <option value={365}>Last year</option>
        </select>

        <select
          value={limit}
          onChange={(event) => setLimit(Number(event.target.value))}
          aria-label="Row limit"
          className="h-9 rounded-md border bg-background px-2 text-sm"
        >
          <option value={100}>100 rows</option>
          <option value={1000}>1,000 rows</option>
          <option value={10000}>10,000 rows</option>
        </select>

        <Button
          variant="outline"
          onClick={() => void downloadCsv()}
          disabled={running}
          data-testid="report-download"
        >
          <Download className={running ? 'mr-1.5 size-4 animate-pulse' : 'mr-1.5 size-4'} aria-hidden />
          Download CSV
        </Button>
      </div>

      {selected?.capped ? (
        <p className="rounded-md border bg-warning/10 p-3 text-sm text-foreground">
          {selected.label} is capped at {limit.toLocaleString('en-US')} rows.
          {data && data.length >= limit
            ? ' You have hit the cap — the export below is truncated, not complete. Narrow the window or raise the limit if you need every row.'
            : ' Raise the limit or narrow the window if you need more.'}
        </p>
      ) : null}

      {downloadError ? (
        <p
          role="alert"
          className="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm"
        >
          {downloadError}
        </p>
      ) : null}

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{selected?.label}</CardTitle>
          <CardDescription>
            {selected?.description}
            {selected?.capped ? ` ${selected.capped}` : ''}
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          <Button variant="outline" onClick={() => void refetch()} disabled={isLoading}>
            <Play className={isLoading ? 'mr-1.5 size-4 animate-pulse' : 'mr-1.5 size-4'} aria-hidden />
            Preview
          </Button>

          {previewError ? (
            <p
              role="alert"
              className="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm"
            >
              {previewError.message}
            </p>
          ) : null}

          {isLoading ? (
            <Skeleton className="h-48 w-full" />
          ) : !data ? (
            <p className="py-6 text-center text-sm text-muted-foreground">
              Nothing previewed yet. Preview to look at it here, or download the CSV and open it in
              a spreadsheet.
            </p>
          ) : data.length === 0 ? (
            // Honest empty state. Sample rows to fill the table would be indistinguishable from a
            // real result, and this report is often opened precisely to confirm nothing happened.
            <p className="py-8 text-center text-sm text-muted-foreground">
              No rows in the last {days} days. That is the answer, not a failure.
            </p>
          ) : (
            <div className="max-h-[32rem] overflow-auto rounded-lg border">
              <table className="w-full text-sm">
                <thead className="sticky top-0 bg-muted">
                  <tr>
                    {Object.keys(data[0] ?? {}).map((key) => (
                      <th
                        key={key}
                        scope="col"
                        className="whitespace-nowrap px-3 py-2 text-left font-medium"
                      >
                        {key}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {data.map((row, index) => (
                    <tr key={index} className="border-t">
                      {Object.entries(row).map(([key, value]) => (
                        <td key={key} className="whitespace-nowrap px-3 py-2">
                          {value === null || value === undefined ? (
                            <span className="italic text-muted-foreground">not set</span>
                          ) : typeof value === 'object' ? (
                            JSON.stringify(value)
                          ) : (
                            String(value)
                          )}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}