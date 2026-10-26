import { useEffect, useMemo, useState } from 'react';
import {
  ArrowLeft,
  ExternalLink,
  ListTree,
  Megaphone,
  Palette,
  PanelTop,
  Settings2,
} from 'lucide-react';
import { useNavigate, useSearchParams } from 'react-router-dom';

import { theme as themeApi } from '@store-app/api-client';
import type { Dashboard, ThemeDetail, ThemeView } from '@store-app/api-client';
import { authorizedFetch, readEnv } from '@store-app/auth';
import type { AuthAdapter } from '@store-app/auth';
import {
  createSection,
  paletteGroups,
  parseTheme,
  sectionDefinition,
  ThemeRenderer,
} from '@store-app/theme-engine';
import type { SectionType, StorefrontData, ThemeSection } from '@store-app/theme-engine';
import { useThemeEditor } from '@store-app/state';
import {
  Badge,
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  Input,
  Label,
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectTrigger,
  SelectValue,
  cn,
} from '@store-app/ui';

import { SectionInspector, TokenField } from '../components/theme/editor-fields';
import { ScaledPreview } from '../components/theme/scaled-preview';

/**
 * The theme customiser — Dukaan's Appearance → Themes → Customize, in this project's palette.
 *
 * <p><b>Why it is shaped like a form and not like the old screen.</b> The previous editor put the
 * section list, the live preview, the colour tokens, the section inspector and the template
 * marketplace on one screen at the same weight. Everything was one click away and nothing was
 * findable: an owner who wanted to change the banner headline had to work out that "banner" meant
 * "the hero section", find it in a list of ten, and notice that the right-hand panel had changed.
 * Dukaan solves this with a left rail of five plain-language tabs. This is that rail.
 *
 * <p><b>What is kept from the old screen, deliberately.</b> The preview is still the real
 * `ThemeRenderer` fed by the real storefront endpoint — not a mock-up — and it now sits beside the
 * form rather than under it. Publishing is still one button that saves and publishes in a single
 * request, so the storefront never blinks through an unpublish.
 *
 * <p><b>What is not here.</b> Dukaan's rail also has a "Code editor" that exposes the theme's
 * markup to the owner. This engine does not accept custom markup or CSS — sections are typed
 * components — so a code editor would either be a lie or a second rendering pipeline. The tab is
 * omitted rather than faked.
 */
export function ShopThemeCustomize({ auth }: { auth: AuthAdapter }) {
  const editor = useThemeEditor();
  const navigate = useNavigate();
  const [params] = useSearchParams();

  const [themes, setThemes] = useState<ThemeView[]>([]);
  const [activeId, setActiveId] = useState<string | null>(params.get('theme'));
  const [issues, setIssues] = useState<string[]>([]);
  const [status, setStatus] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [tab, setTab] = useState<TabKey>('header');
  const [previewData, setPreviewData] = useState<StorefrontData | null>(null);
  const [shopSlug, setShopSlug] = useState<string | null>(null);

  const draft = editor.draft;

  /**
   * Validation problems for the current draft.
   *
   * <p>Computed here rather than reported by {@link ThemeRenderer}, which is a pure component and
   * cannot call a setter while rendering. The parse is the same exported `parseTheme` the renderer
   * calls, so the editor's list and the storefront's inline notice cannot disagree about what is
   * wrong.
   */
  useEffect(() => {
    if (!draft) return;
    setIssues(parseTheme(draft).issues);
  }, [draft]);

  /**
   * The shop's real storefront payload, for the preview.
   *
   * <p>It is fetched from the same public endpoint the live storefront page calls, so the editor
   * and the published page resolve the same sections from the same data. Sharing the renderer is
   * only half of "the preview matches the storefront"; the other half is sharing its input.
   *
   * <p>The slug comes from `/shop/dashboard` rather than a route parameter, because shop-scoped
   * endpoints resolve the shop from the token and never from the URL (D35). The storefront endpoint
   * is keyed by slug only because it is public — a customer with no token still has to reach it.
   */
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const dash = await authorizedFetch(auth, '/api/v1/shop/dashboard');
        if (!dash.ok) throw new Error(`dashboard request failed: HTTP ${dash.status}`);
        const { shopSlug: slug } = (await dash.json()) as Dashboard;

        const res = await authorizedFetch(auth, `/api/v1/p/shops/${slug}/storefront`);
        if (!res.ok) throw new Error(`storefront request failed: HTTP ${res.status}`);
        const body = (await res.json()) as StorefrontData;
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
        const list = await themeApi.list();
        setThemes(list.themes);
        // A theme id in the URL wins: it is how the gallery hands over after installing a template.
        // Otherwise open the live theme, because that is the one the shop is thinking about.
        const live = list.published ?? list.themes[0] ?? null;
        setActiveId((current) => current ?? live?.id ?? null);
      } catch (error) {
        setStatus(describeError(error));
      }
    })();
  }, [auth]);

  useEffect(() => {
    if (!activeId) return;
    void (async () => {
      try {
        const detail = await themeApi.get(activeId);
        editor.load(parseTheme(detail.config).theme);
        setIssues(detail.warnings ?? []);
      } catch (error) {
        setStatus(describeError(error));
      }
    })();
    // `editor` is a zustand store; its identity never changes, and including it would be noise.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeId]);

  const activeTheme = themes.find((theme) => theme.id === activeId) ?? null;

  const selected = draft?.sections.find((s) => s.id === editor.selectedSectionId) ?? null;
  const headerSection = draft?.sections.find((s) => s.type === 'site-header') ?? null;
  const bannerSections = useMemo(
    () =>
      (draft?.sections ?? []).filter(
        (s) => s.type === 'announcement-bar' || s.type === 'hero',
      ),
    [draft],
  );

  function addSection(type: SectionType) {
    const section = createSection(type);
    editor.addSection(section);
    editor.select(section.id);
  }

  /**
   * One button: save and publish together.
   *
   * <p>This used to be "Unpublish first, then edit, then Publish", because the server refused to
   * save a live theme. That was the worst possible shape for an owner — three steps to change a
   * colour — and it was also worse for the customer it claimed to protect: in the gap between
   * unpublish and publish the shop has no live theme, so its storefront falls back to the platform
   * default and visibly becomes someone else's template.
   *
   * <p>`save-and-publish` does both in one transaction, so the storefront is never in a gap.
   */
  async function publish() {
    if (!draft || !activeId) return;
    setBusy(true);
    setStatus(null);
    try {
      const detail: ThemeDetail = await themeApi.saveAndPublish(activeId, JSON.stringify(draft));
      editor.markSaved();
      setIssues(detail.warnings ?? []);
      setStatus('Live. Your storefront is updated.');
      const list = await themeApi.list();
      setThemes(list.themes);
    } catch (error) {
      setStatus(describeError(error));
    } finally {
      setBusy(false);
    }
  }

  function leave() {
    if (editor.dirty && !window.confirm('You have unpublished changes. Leave without publishing?')) {
      return;
    }
    navigate('/shop/theme');
  }

  function openStorefront() {
    const base = readEnv('VITE_STOREFRONT_URL') ?? 'http://localhost:3000';
    window.open(`${base}/s/${shopSlug ?? ''}`, '_blank');
  }

  if (!draft) {
    return (
      <div className="space-y-6">
        <button
          type="button"
          onClick={() => navigate('/shop/theme')}
          className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft className="size-4" aria-hidden /> Themes
        </button>
        <Card>
          <CardContent className="p-6 text-sm text-muted-foreground">
            {status ?? 'Loading your theme…'}
          </CardContent>
        </Card>
      </div>
    );
  }

  return (
    <div className="space-y-5">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex items-start gap-3">
          <button
            type="button"
            onClick={leave}
            className="mt-0.5 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
          >
            <ArrowLeft className="size-4" aria-hidden /> Themes
          </button>
          <div>
            <h1 className="text-xl font-semibold">Customize theme</h1>
            {/* A <div>, not a <p>: Badge renders a block element, and a block inside a paragraph
                is invalid HTML that React reports as a hydration error. */}
            <div className="flex items-center gap-2 text-xs text-muted-foreground">
              <span>{activeTheme?.name ?? 'Your theme'}</span>
              {editor.dirty ? (
                <Badge variant="outline" className="gap-1.5">
                  <span aria-hidden className="inline-block size-1.5 rounded-full bg-warning" />
                  Unpublished changes
                </Badge>
              ) : activeTheme?.published ? (
                <Badge variant="secondary">Live</Badge>
              ) : (
                <Badge variant="outline">Draft</Badge>
              )}
            </div>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <Button variant="outline" onClick={editor.undo} disabled={!editor.past.length}>
            Undo
          </Button>
          <Button variant="outline" onClick={editor.redo} disabled={!editor.future.length}>
            Redo
          </Button>
          <Button variant="outline" onClick={openStorefront} disabled={!shopSlug}>
            <ExternalLink className="mr-1.5 size-4" aria-hidden />
            Preview
          </Button>
          {/* One button, and it says what it does. "Save draft" and "Publish" side by side invited
              the question "which one?", which is not a question a shop owner should have to reason
              about to change a colour. */}
          <Button onClick={() => void publish()} disabled={busy || !editor.dirty}>
            {busy ? 'Publishing…' : 'Publish changes'}
          </Button>
        </div>
      </header>

      {status ? (
        <p role="status" className="whitespace-pre-line rounded-lg border border-border bg-secondary px-3 py-2 text-sm text-secondary-foreground">
          {status}
        </p>
      ) : null}

      {issues.length > 0 ? (
        <ul className="rounded-lg border border-warning/40 bg-warning/10 px-3 py-2 text-sm">
          {issues.map((issue) => (
            <li key={issue}>{issue}</li>
          ))}
        </ul>
      ) : null}

      <div className="grid gap-5 lg:grid-cols-[188px_minmax(0,1fr)_352px]">
        {/* ------------------------------------------------------------- rail */}
        <Card className="h-fit lg:sticky lg:top-4">
          <CardContent className="p-2">
            <p className="px-3 pb-1 pt-2 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
              Theme
            </p>
            <p className="truncate px-3 pb-2 text-sm font-semibold">{activeTheme?.name ?? '—'}</p>
            <nav
              aria-label="Customiser sections"
              className="flex gap-1 overflow-x-auto lg:flex-col lg:overflow-visible"
            >
              {TABS.map((item) => {
                const Icon = item.icon;
                const current = tab === item.key;
                return (
                  <button
                    key={item.key}
                    type="button"
                    aria-current={current ? 'page' : undefined}
                    onClick={() => setTab(item.key)}
                    className={cn(
                      'flex shrink-0 items-center gap-2 rounded-md px-3 py-2 text-left text-sm transition-colors',
                      current
                        ? 'bg-primary text-primary-foreground'
                        : 'text-muted-foreground hover:bg-secondary hover:text-foreground',
                    )}
                  >
                    <Icon className="size-4 shrink-0" aria-hidden />
                    <span className="truncate">{item.label}</span>
                    {item.key === 'sections' ? (
                      <span
                        className={cn(
                          'ml-auto text-xs',
                          current ? 'text-primary-foreground/70' : 'text-muted-foreground',
                        )}
                      >
                        {draft.sections.length}
                      </span>
                    ) : null}
                  </button>
                );
              })}
            </nav>
          </CardContent>
        </Card>

        {/* ---------------------------------------------------------- preview */}
        <Card className="h-fit overflow-hidden lg:sticky lg:top-4">
          <CardHeader className="flex flex-row items-center justify-between gap-2 space-y-0 py-3">
            <CardTitle className="text-sm">Live preview</CardTitle>
            <span className="text-[11px] text-muted-foreground">Scaled desktop view</span>
          </CardHeader>
          <CardContent className="bg-secondary/40 p-3">
            {/* The same component the storefront renders. Not a mock-up of it. */}
            {previewData ? (
              // Scaled, not constrained: the page is laid out at a real desktop width, because a
              // narrow box here would still be laid out by media queries against the *browser's*
              // width and would show a layout no customer ever sees.
              <ScaledPreview
                config={draft}
                data={previewData}
                mode="full"
                className="max-h-[70vh] overflow-y-auto rounded-xl border border-border bg-white"
              />
            ) : (
              // A preview with no data is a preview of nothing: every commerce section renders its
              // empty state and the owner judges a layout against zero products. Saying so plainly
              // beats showing a plausible-looking page no shop will ever have.
              <div className="flex h-64 items-center justify-center px-4 text-center text-sm text-muted-foreground">
                <p>
                  Loading your shop&rsquo;s products and categories for the preview…
                  <br />
                  <span className="text-xs">
                    If this stays empty, your shop has no published products yet.
                  </span>
                </p>
              </div>
            )}
          </CardContent>
        </Card>

        {/* ------------------------------------------------------------ panel */}
        <div className="space-y-4">
          {tab === 'header' ? (
            headerSection ? (
              <SectionInspector
                section={headerSection}
                onChange={(props) => editor.updateSection(headerSection.id, { props })}
                onToggleHidden={(hidden) => editor.updateSection(headerSection.id, { hidden })}
              />
            ) : (
              <EmptyPanel
                title="This theme has no header"
                body="A header holds your shop name, logo, search box and menu links."
                actionLabel="Add a header"
                onAction={() => addSection('site-header')}
              />
            )
          ) : null}

          {tab === 'banners' ? (
            <>
              {bannerSections.length === 0 ? (
                <EmptyPanel
                  title="No banners in this theme"
                  body="A banner is the strip at the very top of your shop, and the large block under your header."
                  actionLabel="Add a hero banner"
                  onAction={() => addSection('hero')}
                />
              ) : null}
              {bannerSections.map((section) => (
                <SectionInspector
                  key={section.id}
                  section={section}
                  onChange={(props) => editor.updateSection(section.id, { props })}
                  onToggleHidden={(hidden) => editor.updateSection(section.id, { hidden })}
                />
              ))}
              <div className="flex flex-wrap gap-2">
                <Button variant="outline" size="sm" onClick={() => addSection('announcement-bar')}>
                  Add announcement bar
                </Button>
                <Button variant="outline" size="sm" onClick={() => addSection('hero')}>
                  Add hero banner
                </Button>
              </div>
            </>
          ) : null}

          {tab === 'sections' ? (
            <>
              <Card>
                <CardHeader className="py-3">
                  <CardTitle className="text-sm">Your sections, in order</CardTitle>
                </CardHeader>
                <CardContent className="space-y-2">
                  {draft.sections.length === 0 ? (
                    <p className="text-sm text-muted-foreground">
                      No sections yet. Add one below — the storefront shows them top to bottom.
                    </p>
                  ) : null}

                  {draft.sections.map((section, index) => (
                    <div
                      key={section.id}
                      className={cn(
                        'flex items-center gap-2 rounded-md border px-2 py-1.5 text-sm',
                        editor.selectedSectionId === section.id
                          ? 'border-primary bg-primary/5'
                          : 'border-border',
                      )}
                    >
                      <button
                        type="button"
                        className="min-w-0 flex-1 truncate text-left"
                        onClick={() => editor.select(section.id)}
                      >
                        {sectionDefinitionLabel(section)}
                        {section.hidden ? ' (hidden)' : ''}
                      </button>
                      <span className="text-xs text-muted-foreground">{index + 1}</span>
                      <button
                        type="button"
                        aria-label="Move up"
                        disabled={index === 0}
                        className="disabled:opacity-30"
                        onClick={() => editor.moveSection(section.id, index - 1)}
                      >
                        ↑
                      </button>
                      <button
                        type="button"
                        aria-label="Move down"
                        disabled={index === draft.sections.length - 1}
                        className="disabled:opacity-30"
                        onClick={() => editor.moveSection(section.id, index + 1)}
                      >
                        ↓
                      </button>
                      <button
                        type="button"
                        aria-label="Remove"
                        onClick={() => editor.removeSection(section.id)}
                      >
                        ✕
                      </button>
                    </div>
                  ))}

                  <div className="border-t border-border pt-3">
                    <Label className="text-xs uppercase tracking-wide text-muted-foreground">
                      Add a section
                    </Label>
                    {/* Radix Select, so the palette is grouped and keyboard-navigable rather than a
                        native <select>. The trigger shows the placeholder until something is
                        chosen, because there is no meaningful "current value" for an action list. */}
                    <Select onValueChange={(value) => addSection(value as SectionType)}>
                      <SelectTrigger className="mt-1">
                        <SelectValue placeholder="Add a section…" />
                      </SelectTrigger>
                      <SelectContent>
                        {paletteGroups().map((group) => (
                          <SelectGroup key={group.group}>
                            <SelectLabel>{group.group}</SelectLabel>
                            {group.sections.map((section) => (
                              <SelectItem key={section.type} value={section.type}>
                                {section.label}
                              </SelectItem>
                            ))}
                          </SelectGroup>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                </CardContent>
              </Card>

              {selected ? (
                <SectionInspector
                  section={selected}
                  onChange={(props) => editor.updateSection(selected.id, { props })}
                  onToggleHidden={(hidden) => editor.updateSection(selected.id, { hidden })}
                />
              ) : (
                <Card>
                  <CardHeader className="py-3">
                    <CardTitle className="text-sm">Section settings</CardTitle>
                  </CardHeader>
                  <CardContent className="text-sm text-muted-foreground">
                    Choose a section above to edit its text and settings.
                  </CardContent>
                </Card>
              )}
            </>
          ) : null}

          {tab === 'appearance' ? (
            <Card>
              <CardHeader className="py-3">
                <CardTitle className="text-sm">Fonts &amp; colours</CardTitle>
              </CardHeader>
              <CardContent className="space-y-4">
                <p className="text-xs text-muted-foreground">
                  These colours are your shop&rsquo;s identity. Text colour is worked out for you, so
                  buttons stay readable whatever you choose.
                </p>
                <TokenField
                  label="Primary"
                  hint="Buttons, links and prices"
                  value={draft.tokens.primary ?? '#7a5240'}
                  onChange={(primary) => editor.updateTokens({ primary })}
                />
                <TokenField
                  label="Background"
                  hint="The page behind everything"
                  value={draft.tokens.background ?? '#ffffff'}
                  onChange={(background) => editor.updateTokens({ background })}
                />
                <TokenField
                  label="Accent"
                  hint="Badges, highlights and sale tags"
                  value={draft.tokens.accent ?? '#c8a27a'}
                  onChange={(accent) => editor.updateTokens({ accent })}
                />
                <div>
                  <Label htmlFor="radius">Corner radius</Label>
                  <Input
                    id="radius"
                    className="mt-1"
                    value={draft.tokens.radius ?? '0.5rem'}
                    onChange={(e) => editor.updateTokens({ radius: e.target.value })}
                  />
                </div>
                <div>
                  <Label htmlFor="font">Body font</Label>
                  <Input
                    id="font"
                    className="mt-1"
                    value={draft.tokens.fontBody ?? 'Inter'}
                    onChange={(e) => editor.updateTokens({ fontBody: e.target.value })}
                  />
                  <p className="mt-1 text-xs text-muted-foreground">
                    A safe system font is used automatically if this one is not installed on the
                    visitor&rsquo;s device.
                  </p>
                </div>
              </CardContent>
            </Card>
          ) : null}

          {tab === 'advanced' ? (
            <>
              <Card>
                <CardHeader className="py-3">
                  <CardTitle className="text-sm">Checks</CardTitle>
                </CardHeader>
                <CardContent className="space-y-2 text-sm">
                  {issues.length === 0 ? (
                    <p className="text-muted-foreground">No problems found in this theme.</p>
                  ) : (
                    <ul className="list-disc space-y-1 pl-4 text-muted-foreground">
                      {issues.map((issue) => (
                        <li key={issue}>{issue}</li>
                      ))}
                    </ul>
                  )}
                  <p className="text-xs text-muted-foreground">
                    Publish is refused while a theme has errors, so a broken layout can never reach
                    a customer.
                  </p>
                </CardContent>
              </Card>

              <Card>
                <CardHeader className="py-3">
                  <CardTitle className="text-sm">This theme</CardTitle>
                </CardHeader>
                <CardContent className="space-y-1 text-sm text-muted-foreground">
                  <p>
                    Name: <span className="text-foreground">{activeTheme?.name ?? '—'}</span>
                  </p>
                  <p>
                    Sections: <span className="text-foreground">{draft.sections.length}</span>
                  </p>
                  <p>
                    Format version:{' '}
                    <span className="text-foreground">{draft.schemaVersion ?? 1}</span>
                  </p>
                  <p>
                    Status:{' '}
                    <span className="text-foreground">
                      {activeTheme?.published ? 'Published' : 'Draft'}
                    </span>
                  </p>
                </CardContent>
              </Card>
            </>
          ) : null}
        </div>
      </div>
    </div>
  );
}

type TabKey = 'header' | 'banners' | 'sections' | 'appearance' | 'advanced';

const TABS: ReadonlyArray<{
  key: TabKey;
  label: string;
  icon: typeof PanelTop;
}> = [
  { key: 'header', label: 'Header', icon: PanelTop },
  { key: 'banners', label: 'Banners', icon: Megaphone },
  { key: 'sections', label: 'Sections', icon: ListTree },
  { key: 'appearance', label: 'Fonts & colours', icon: Palette },
  { key: 'advanced', label: 'Advanced', icon: Settings2 },
];

function sectionDefinitionLabel(section: ThemeSection): string {
  return sectionDefinition(section.type).label;
}

/**
 * A panel that explains why it is empty and offers the one action that fixes it.
 *
 * <p>An empty state that only says "nothing here" makes the owner go looking; this one names the
 * missing thing and adds it.
 */
function EmptyPanel({
  title,
  body,
  actionLabel,
  onAction,
}: {
  title: string;
  body: string;
  actionLabel: string;
  onAction: () => void;
}) {
  return (
    <Card>
      <CardHeader className="py-3">
        <CardTitle className="text-sm">{title}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3 text-sm text-muted-foreground">
        <p>{body}</p>
        <Button variant="outline" size="sm" onClick={onAction}>
          {actionLabel}
        </Button>
      </CardContent>
    </Card>
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
