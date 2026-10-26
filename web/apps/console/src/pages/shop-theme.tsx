import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';

import { theme as themeApi } from '@store-app/api-client';
import type { Dashboard, MarketplaceEntry, ThemeView } from '@store-app/api-client';
import { authorizedFetch, readEnv } from '@store-app/auth';
import type { AuthAdapter } from '@store-app/auth';
import { parseTheme } from '@store-app/theme-engine';
import type { StorefrontData, ThemeConfig } from '@store-app/theme-engine';
import { Badge, Button, cn } from '@store-app/ui';

import { ScaledPreview } from '../components/theme/scaled-preview';

/**
 * The themes gallery — Appearance' landing screen.
 *
 * <p><b>Why this screen exists.</b> Appearance used to be one 997-line page that opened directly
 * into the builder: sections, live preview, inspector, colour tokens, the template marketplace and
 * the asset editor, all visible at once, all equally urgent. A shop owner arriving to "change how
 * my shop looks" had to work out which of nine panels answered that question. Dukaan's equivalent
 * opens on a wall of themes and nothing else, and the editing lives one click deeper. This is that
 * split.
 *
 * <p><b>What is deliberately not copied.</b> Dukaan's "Apply" makes a theme live the moment it is
 * clicked. Here a theme is installed as a <em>draft</em> and goes live only when published — the
 * rule the whole editor is built around, because a customer mid-scroll must never watch a
 * storefront rearrange itself. The button says "Use theme", not "Apply", so the difference is
 * legible before the click rather than discovered after it.
 *
 * <p><b>The previews are real.</b> Every card renders the actual `ThemeRenderer` with this shop's
 * actual products, scaled down. No template in this database has a `preview_media_id`, so the
 * screenshot-based approach the reference uses would have shown a placeholder on every card.
 */
export function ShopTheme({ auth }: { auth: AuthAdapter }) {
  const navigate = useNavigate();

  const [themes, setThemes] = useState<ThemeView[]>([]);
  const [configs, setConfigs] = useState<Record<string, ThemeConfig>>({});
  const [marketplace, setMarketplace] = useState<MarketplaceEntry[]>([]);
  const [previewData, setPreviewData] = useState<StorefrontData | null>(null);
  const [shopSlug, setShopSlug] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  /**
   * The shelf the owner is looking at.
   *
   * <p>Client-side because the whole catalogue arrives in one request: fourteen cards are not
   * worth a round trip per chip, and filtering here keeps the count on each chip honest.
   */
  const [shelf, setShelf] = useState<string>('All');

  /**
   * The shop's real storefront payload, for the previews.
   *
   * <p>Deliberately the same source the storefront itself uses, not fabricated demo products:
   * choosing a theme against invented products shows a shop that does not exist, and the owner
   * finds out what their real catalogue looks like after they have already picked.
   */
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const dash = await authorizedFetch(auth, '/api/v1/shop/dashboard');
        if (!dash.ok) throw new Error(`dashboard request failed: HTTP ${dash.status}`);
        const { shopSlug: slug } = (await dash.json()) as Dashboard;

        // The same public payload the live storefront page uses — not the dashboard DTO. A theme
        // is chosen against the shop's real catalogue, resolved by the same code path.
        const response = await authorizedFetch(auth, `/api/v1/p/shops/${slug}/storefront`);
        if (!response.ok) throw new Error(`storefront request failed: HTTP ${response.status}`);
        const body = (await response.json()) as StorefrontData;
        if (cancelled) return;
        setShopSlug(slug);
        setPreviewData({
          shopSlug: body.shopSlug,
          shopName: body.shopName,
          description: body.description ?? null,
          logoUrl: body.logoUrl ?? null,
          coverImageUrl: body.coverImageUrl ?? null,
          followerCount: body.followerCount,
          shopType: body.shopType,
          categories: body.categories ?? [],
          featuredProducts: body.featuredProducts ?? [],
          latestProducts: body.latestProducts ?? [],
          assets: body.assets ?? [],
        });
      } catch (error) {
        if (cancelled) return;
        setPreviewData(null);
        setStatus(`Preview data unavailable: ${describeError(error)}`);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [auth]);

  useEffect(() => {
    void (async () => {
      try {
        const [list, offers] = await Promise.all([themeApi.list(), themeApi.marketplace()]);
        // Live first, then most recently touched. The API's order is insertion order, which puts
        // the one theme an owner actually has live somewhere in the middle of a list of drafts.
        setThemes(
          [...list.themes].sort((a, b) =>
            a.published !== b.published
              ? a.published
                ? -1
                : 1
              : String(b.updatedAt).localeCompare(String(a.updatedAt)),
          ),
        );
        setMarketplace(offers);

        // The list endpoint carries no config, and a preview needs one. Two requests here is
        // cheaper than a second implementation of the theme renderer.
        const details = await Promise.all(
          list.themes.map((theme) => themeApi.get(theme.id).catch(() => null)),
        );
        const parsed: Record<string, ThemeConfig> = {};
        for (const detail of details) {
          if (detail) parsed[detail.theme.id] = parseTheme(detail.config).theme;
        }
        setConfigs(parsed);
      } catch (error) {
        setStatus(`Could not load your themes: ${describeError(error)}`);
      } finally {
        setLoading(false);
      }
    })();
  }, [auth]);

  const activeTheme = themes.find((theme) => theme.published) ?? themes[0] ?? null;

  // In catalogue order, not alphabetical: the API already sorts by sortOrder, and that is the
  // order an owner browsing "what have you got?" expects to see.
  const shelves = Array.from(
    new Set(marketplace.map((template) => template.category?.trim()).filter((c): c is string => !!c)),
  );
  const shownTemplates =
    shelf === 'All'
      ? marketplace
      : marketplace.filter((template) => (template.category ?? '').trim() === shelf);

  async function useTemplate(template: MarketplaceEntry) {
    setBusyId(template.id);
    setStatus(null);
    try {
      const installed = await themeApi.install(template.id);
      // Straight into the editor: a draft nobody is looking at is a theme the owner thinks they
      // applied but did not. The editor states that it is unpublished before anything else.
      navigate(`/shop/theme/customize?theme=${installed.id}`);
    } catch (error) {
      setStatus(`Could not use ${template.name}: ${describeError(error)}`);
    } finally {
      setBusyId(null);
    }
  }

  function edit(theme: ThemeView) {
    navigate(`/shop/theme/customize?theme=${theme.id}`);
  }

  const storefrontUrl = `${readEnv('VITE_STOREFRONT_URL') ?? 'http://localhost:3000'}/s/${shopSlug ?? ''}`;

  if (loading) {
    return (
      <div className="space-y-6">
        <h1 className="text-2xl font-semibold">Themes</h1>
        <p className="text-sm text-muted-foreground">Loading your themes…</p>
      </div>
    );
  }

  return (
    <div className="space-y-8">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">Themes</h1>
          <p className="mt-1 max-w-2xl text-sm text-muted-foreground">
            Pick the look of your shop. A theme you choose here becomes a draft — customers keep
            seeing the current one until you publish.
          </p>
        </div>
        {shopSlug ? (
          <Button variant="outline" onClick={() => window.open(storefrontUrl, '_blank')}>
            Preview store
          </Button>
        ) : null}
      </header>

      {/* Dukaan opens with a banner; ours states the same thing in the shop owner's terms. */}
      <section className="relative overflow-hidden rounded-[1.75rem] bg-primary px-8 py-10 text-primary-foreground">
        <div className="relative z-10 max-w-xl">
          <h2 className="text-3xl font-semibold tracking-tight">Elevate your shop&rsquo;s look</h2>
          <p className="mt-3 text-sm text-primary-foreground/80">
            Start from a theme, then make it yours — colours, type, sections and banners. Nothing
            goes live until you say so.
          </p>
        </div>
        {/* Decoration, not content: hidden from assistive tech and the print stylesheet. */}
        <div aria-hidden className="pointer-events-none absolute -right-10 -top-16 size-64 rounded-full border border-primary-foreground/15" />
        <div aria-hidden className="pointer-events-none absolute -right-24 top-10 size-72 rounded-full border border-primary-foreground/10" />
        <div aria-hidden className="pointer-events-none absolute right-24 top-20 size-24 rounded-[2rem] bg-primary-foreground/10" />
      </section>

      {status ? (
        <p role="status" className="whitespace-pre-line rounded-lg border border-border bg-secondary px-4 py-3 text-sm text-secondary-foreground">
          {status}
        </p>
      ) : null}

      <section aria-labelledby="your-themes">
        <div className="mb-4 flex items-baseline justify-between gap-4">
          <h2 id="your-themes" className="text-lg font-semibold">
            Your themes
          </h2>
          <p className="text-xs text-muted-foreground">
            {themes.length === 1 ? '1 theme installed' : `${themes.length} themes installed`}
          </p>
        </div>

        {themes.length === 0 ? (
          <div className="rounded-2xl border border-dashed border-border px-6 py-10 text-center">
            <p className="text-sm font-medium">No theme installed yet</p>
            <p className="mx-auto mt-1 max-w-md text-sm text-muted-foreground">
              Your storefront is showing the platform default. Choose a theme below to start.
            </p>
          </div>
        ) : (
          <div className="grid gap-5 sm:grid-cols-2 xl:grid-cols-3">
            {themes.map((theme) => (
              <ThemeCard
                key={theme.id}
                name={theme.name}
                note={theme.published ? 'Live on your storefront' : 'Draft — not published'}
                live={theme.published}
                config={configs[theme.id]}
                data={previewData}
                action={
                  <Button
                    size="sm"
                    variant={theme.published ? 'secondary' : 'outline'}
                    onClick={() => edit(theme)}
                  >
                    {theme.published ? 'Customize' : 'Edit draft'}
                  </Button>
                }
              />
            ))}
          </div>
        )}
      </section>

      <section aria-labelledby="templates">
        <div className="mb-4 flex flex-wrap items-baseline justify-between gap-4">
          <div>
            <h2 id="templates" className="text-lg font-semibold">
              Ready-made templates
            </h2>
            <p className="mt-1 text-sm text-muted-foreground">
              Built for a trade, not a template with your name dropped in. Pick one, then change
              anything you like.
            </p>
          </div>
          <p className="text-xs text-muted-foreground">
            {marketplace.length === 0
              ? 'No templates available for this shop'
              : shelf === 'All'
                ? `${marketplace.length} available`
                : `${shownTemplates.length} of ${marketplace.length}`}
          </p>
        </div>

        {shelves.length > 0 ? (
          <div role="group" aria-label="Filter templates by category" className="mb-5 flex flex-wrap gap-2">
            {['All', ...shelves].map((option) => (
              <button
                key={option}
                type="button"
                aria-pressed={shelf === option}
                onClick={() => setShelf(option)}
                className={cn(
                  'rounded-full border px-3.5 py-1.5 text-xs font-medium transition-colors',
                  shelf === option
                    ? 'border-primary bg-primary text-primary-foreground'
                    : 'border-border bg-card text-muted-foreground hover:border-primary/40 hover:text-foreground',
                )}
              >
                {option}
              </button>
            ))}
          </div>
        ) : null}

        {marketplace.length === 0 ? (
          <div className="rounded-2xl border border-dashed border-border px-6 py-10 text-center">
            <p className="text-sm font-medium">Nothing to choose from yet</p>
            <p className="mx-auto mt-1 max-w-md text-sm text-muted-foreground">
              Every template is filtered by your shop type and plan. There is no match for this
              shop right now.
            </p>
          </div>
        ) : shownTemplates.length === 0 ? (
          <div className="rounded-2xl border border-dashed border-border px-6 py-10 text-center">
            <p className="text-sm font-medium">Nothing in this shelf</p>
            <p className="mx-auto mt-1 max-w-md text-sm text-muted-foreground">
              Choose another category, or see everything.
            </p>
          </div>
        ) : (
          <div className="grid gap-5 sm:grid-cols-2 xl:grid-cols-3">
            {shownTemplates.map((template) => (
              <ThemeCard
                key={template.id}
                name={template.name}
                category={template.category ?? undefined}
                note={
                  template.description?.trim() ? template.description : `${template.sectionCount} sections`
                }
                badge={template.priceTier === 'FREE' ? 'Free' : 'Included'}
                config={template.config}
                data={previewData}
                action={
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={busyId === template.id}
                    onClick={() => void useTemplate(template)}
                  >
                    {busyId === template.id ? 'Installing…' : 'Use theme'}
                  </Button>
                }
              />
            ))}
          </div>
        )}
      </section>

      {/* The active theme is the one thing a shop owner most often wants to describe, and it is
          also the only fact on this page that cannot be read off a card. */}
      {activeTheme ? (
        <p className="text-xs text-muted-foreground">
          Currently showing{' '}
          <span className="font-medium text-foreground">{activeTheme.name}</span>
          {activeTheme.publishedAt
            ? ` · published ${new Date(activeTheme.publishedAt).toLocaleDateString()}`
            : ''}
        </p>
      ) : null}
    </div>
  );
}

/**
 * One theme in the grid.
 *
 * <p>Dukaan's card is a screenshot in a browser frame with a name/action strip underneath, and the
 * live theme's strip is inverted. Both details are kept because both carry meaning: the frame says
 * "this is a website", the inverted strip says "this one is already yours".
 */
function ThemeCard({
  name,
  category,
  note,
  badge,
  live = false,
  config,
  data,
  action,
}: {
  name: string;
  /** Shelf the template belongs to. Omitted for a theme the shop already owns. */
  category?: string;
  note: string;
  badge?: string;
  live?: boolean;
  config?: ThemeConfig | string;
  data: StorefrontData | null;
  action: ReactNode;
}) {
  return (
    <div
      className={cn(
        'overflow-hidden rounded-2xl border bg-card shadow-sm transition-shadow hover:shadow-md',
        live ? 'border-primary/40' : 'border-border',
      )}
    >
      <div aria-hidden className="flex items-center gap-1.5 border-b border-border bg-secondary/70 px-3 py-2">
        <span className="size-1.5 rounded-full bg-border" />
        <span className="size-1.5 rounded-full bg-border" />
        <span className="size-1.5 rounded-full bg-border" />
        <span className="ml-2 h-3.5 flex-1 rounded-full bg-background/80" />
      </div>

      {config && data ? (
        <ScaledPreview config={config} data={data} />
      ) : (
        <div className="flex aspect-[16/10] items-center justify-center bg-secondary/40 px-6 text-center">
          <p className="text-xs text-muted-foreground">
            {config ? 'Waiting for your products to render a preview…' : 'No preview available'}
          </p>
        </div>
      )}

      <div
        className={cn(
          'flex items-center justify-between gap-3 border-t px-4 py-3',
          live ? 'border-transparent bg-foreground text-background' : 'border-border',
        )}
      >
        <div className="min-w-0">
          {category ? (
            <p
              className={cn(
                'text-[10px] font-medium uppercase tracking-[0.09em]',
                live ? 'text-background/60' : 'text-muted-foreground',
              )}
            >
              {category}
            </p>
          ) : null}
          <div className="flex items-center gap-2">
            <p className="truncate text-sm font-semibold">{name}</p>
            {badge ? (
              <Badge variant="secondary" className="shrink-0 text-[10px]">
                {badge}
              </Badge>
            ) : null}
          </div>
          <p className={cn('truncate text-xs', live ? 'text-background/70' : 'text-muted-foreground')}>
            {note}
          </p>
        </div>
        <div className="shrink-0">{action}</div>
      </div>
    </div>
  );
}

/**
 * Turns a thrown error into something an owner can act on.
 *
 * <p>The backend returns every theme problem in `issues`, so all of them are shown rather than
 * the first — five corrections should not take five round trips.
 */
function describeError(error: unknown): string {
  const e = error as { status?: number; body?: Record<string, unknown>; message?: string };
  const issues = e?.body?.issues;
  if (Array.isArray(issues) && issues.length > 0) {
    return `The theme could not be saved:\n${issues.map((i) => `• ${i}`).join('\n')}`;
  }
  if (typeof e?.body?.detail === 'string') {
    return e.body.detail;
  }
  return e?.message ?? 'Something went wrong.';
}
