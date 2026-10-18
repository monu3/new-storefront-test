import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ChevronRight, Lock, Play, Terminal } from 'lucide-react';

import { authorizedFetch, postJson } from '@store-app/auth';
import type { AuthAdapter } from '@store-app/auth';
import {
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Input,
  Skeleton,
  cn,
} from '@store-app/ui';

import { readProblem } from '../lib/format';

interface Parameters {
  name: string;
  in: string;
  type: string;
  required: boolean;
  example: string | null;
}

interface Endpoint {
  method: string;
  path: string;
  summary: string;
  operationId: string;
  runnable: boolean;
  parameters: Parameters[];
}

interface TagGroup {
  name: string;
  operations: number;
  items: Endpoint[];
}

interface Contract {
  tags: TagGroup[];
  runnableCount: number;
  tagNames: string[];
}

interface ProbeResult {
  path: string;
  query: string | null;
  status: number;
  body: unknown;
  error: string | null;
  ok: boolean;
}

/**
 * The data explorer (Phase 7).
 *
 * <p><b>It reads the live routing table, so it cannot go stale.</b> A hand-written endpoint list
 * drifts the moment an endpoint is added and then lies in both directions at once: offering
 * something that is gone, and omitting something that is not. This screen is generated from what
 * Spring actually dispatches on.
 *
 * <p><b>Only admin reads are runnable, and the reason is shown for everything else.</b> An explorer
 * built from the full contract without a restriction would be a second, quieter way to act on every
 * shop's and every customer's data — and &ldquo;I found it in the explorer&rdquo; is not an answer to
 * &ldquo;who changed this&rdquo;. Every write on this platform is audited; this is where a read
 * belongs.
 *
 * <p><b>A refusal explains itself.</b> A greyed-out button with no reason invites the operator to
 * find another way to call the thing, which is the outcome this screen exists to make unnecessary.
 */
export function AdminExplorer({ auth }: { auth: AuthAdapter }) {
  const [openTag, setOpenTag] = useState<string | null>(null);
  const [params, setParams] = useState<Record<string, string>>({});
  const [result, setResult] = useState<ProbeResult | null>(null);
  const [running, setRunning] = useState(false);
  const [probeError, setProbeError] = useState<string | null>(null);

  const { data, isLoading, error } = useQuery({
    queryKey: ['admin', 'explorer', 'contract'],
    queryFn: async () => {
      const response = await authorizedFetch(auth, '/api/v1/admin/explorer/contract');
      if (!response.ok) throw new Error(await readProblem(response));
      return (await response.json()) as Contract;
    },
  });

  async function run(endpoint: Endpoint) {
    setRunning(true);
    setProbeError(null);
    try {
      const response = await postJson(auth, '/api/v1/admin/explorer/probe', {
        path: endpoint.path,
        params,
      });
      if (!response.ok) throw new Error(await readProblem(response));
      setResult((await response.json()) as ProbeResult);
    } catch (problem) {
      setProbeError(problem instanceof Error ? problem.message : String(problem));
    } finally {
      setRunning(false);
    }
  }

  if (isLoading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-8 w-64" />
        <Skeleton className="h-96 rounded-xl" />
      </div>
    );
  }

  if (error || !data) {
    return (
      <Card>
        <CardContent className="p-10 text-center">
          <p className="font-medium">Could not load the API contract</p>
          <p className="mt-1 text-sm text-muted-foreground">{error?.message}</p>
        </CardContent>
      </Card>
    );
  }

  return (
    <div className="space-y-4">
      <header>
        <h1 className="text-2xl font-semibold">Data explorer</h1>
        <p className="text-muted-foreground">
          {data.runnableCount} safe read{data.runnableCount === 1 ? '' : 's'} of{' '}
          {data.tags.reduce((sum, tag) => sum + tag.operations, 0)} endpoints. Read from the live
          routing table, so it cannot fall out of date with the API.
        </p>
      </header>

      {data.tags.length === 0 ? (
        <Card>
          <CardContent className="p-10 text-center">
            <Terminal className="mx-auto mb-3 size-8 text-muted-foreground" aria-hidden />
            <p className="font-medium">No endpoints were found</p>
            <p className="mt-1 text-sm text-muted-foreground">
              The application is running but its routing table is empty, which is not a state this
              platform has. Check that the controllers were registered.
            </p>
          </CardContent>
        </Card>
      ) : (
        <div className="space-y-2">
          {data.tags.map((tag) => {
            const expanded = openTag === tag.name;
            const runnable = tag.items.filter((item) => item.runnable).length;

            return (
              <Card key={tag.name}>
                <button
                  type="button"
                  onClick={() => setOpenTag(expanded ? null : tag.name)}
                  aria-expanded={expanded}
                  className="flex w-full items-center justify-between gap-3 p-4 text-left"
                >
                  <div className="flex items-center gap-2">
                    <ChevronRight
                      className={cn('size-4 transition-transform', expanded && 'rotate-90')}
                      aria-hidden
                    />
                    <span className="font-medium">{tag.name}</span>
                    <Badge variant="secondary">{tag.operations}</Badge>
                  </div>
                  <span className="text-xs text-muted-foreground">
                    {runnable} runnable here
                  </span>
                </button>

                {expanded ? (
                  <CardContent className="space-y-2 border-t pt-3">
                    {tag.items.map((endpoint) => (
                      <div key={`${endpoint.method} ${endpoint.path}`} className="rounded-lg border">
                        <div className="flex flex-wrap items-center gap-2 p-3">
                          <Badge
                            variant="secondary"
                            className={cn(
                              'font-mono text-[10px]',
                              endpoint.method !== 'GET' && 'bg-orange-100 text-orange-800',
                            )}
                          >
                            {endpoint.method}
                          </Badge>
                          <code className="text-xs">{endpoint.path}</code>
                          {!endpoint.runnable ? (
                            <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
                              <Lock className="size-3" aria-hidden />
                              not runnable here
                            </span>
                          ) : null}
                        </div>

                        <p className="px-3 pb-2 text-sm text-muted-foreground">{endpoint.summary}</p>

                        {endpoint.runnable ? (
                          <div className="flex flex-wrap items-end gap-2 border-t p-3">
                            {endpoint.parameters.length > 0 ? (
                              endpoint.parameters.map((parameter) => (
                                <label key={parameter.name} className="text-xs">
                                  <span className="font-medium">
                                    {parameter.name}
                                    {parameter.required ? ' *' : ''}
                                  </span>
                                  <Input
                                    value={params[parameter.name] ?? ''}
                                    onChange={(event) =>
                                      setParams((current) => ({
                                        ...current,
                                        [parameter.name]: event.target.value,
                                      }))
                                    }
                                    placeholder={parameter.type}
                                    aria-label={parameter.name}
                                    className="mt-1 h-8 w-36 text-xs"
                                  />
                                </label>
                              ))
                            ) : (
                              <p className="text-xs text-muted-foreground">No parameters.</p>
                            )}
                            <Button
                              size="sm"
                              onClick={() => void run(endpoint)}
                              disabled={running}
                            >
                              <Play className="mr-1.5 size-3.5" aria-hidden />
                              Run
                            </Button>
                          </div>
                        ) : null}
                      </div>
                    ))}
                  </CardContent>
                ) : null}
              </Card>
            );
          })}
        </div>
      )}

      {probeError ? (
        <p
          role="alert"
          className="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm"
        >
          {probeError}
        </p>
      ) : null}

      {result ? (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Result</CardTitle>
            <CardDescription>
              {result.path}
              {result.query ? `?${result.query}` : ''} — HTTP {result.status}, run as you.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {result.ok ? (
              <pre className="max-h-96 overflow-auto rounded-lg bg-muted p-3 text-xs">
                {JSON.stringify(result.body, null, 2)}
              </pre>
            ) : (
              // An error body is the answer too, so it is shown rather than replaced with a
              // generic failure message.
              <pre className="max-h-96 overflow-auto rounded-lg bg-destructive/5 p-3 text-xs text-destructive">
                {result.error ?? JSON.stringify(result.body, null, 2)}
              </pre>
            )}
          </CardContent>
        </Card>
      ) : null}
    </div>
  );
}