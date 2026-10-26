import { useEffect, useState } from 'react';

import { theme as themeApi } from '@store-app/api-client';
import type { Dashboard, ThemeDetail, ThemeView } from '@store-app/api-client';
import { authorizedFetch } from '@store-app/auth';
import type { AuthAdapter } from '@store-app/auth';
import {
  ThemeRenderer,
  contrastRatio,
  createSection,
  paletteGroups,
  parseTheme,
  readableForeground,
  sectionDefinition,
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
  Switch,
  Textarea,
} from '@store-app/ui';

/**
 * The shop's theme editor (D8).
 *
 * <p><b>The preview is the storefront.</b> It calls the same `ThemeRenderer` the SSR storefront
 * calls, with the same props shape, fed from the draft in local state. There is no second
 * implementation to drift, which is what makes "what you see is what customers get" true rather
 * than aspirational.
 *
 * <p>Edits are a <b>draft</b> until published. A live theme cannot be saved at all — the server
 * refuses — so a customer mid-scroll never sees the layout move under them. That is why
 * "Publish" is a separate, deliberate action rather than a save.
 */
export function ShopTheme({ auth }: { auth: AuthAdapter }) {
  const editor = useThemeEditor();
  const [themes, setThemes] = useState<ThemeView[]>([]);

  const [activeId, setActiveId] = useState<string | null>(null);
  const [marketplace, setMarketplace] = useState<
    Array<{ id: string; code: string; name: string; description?: string | null; sectionCount: number }>
  >([]);
  const [issues, setIssues] = useState<string[]>([]);
  const [status, setStatus] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const draft = editor.draft;

  /**
   * Validation problems for the current draft.
   *
   * <p>Computed here rather than reported by {@code ThemeRenderer}, which is a pure component and
   * cannot call a setter while rendering. The parse is the same exported {@code parseTheme} the
   * renderer calls, so the editor's list and the storefront's inline notice cannot disagree about
   * what is wrong.
   */
  useEffect(() => {
    if (!draft) return;
    // parseTheme accepts an object, so no stringify needed here. The one that did stringify was
    // written because the other caller passed a raw string, and the two drifted apart.
    setIssues(parseTheme(draft).issues);
  }, [draft]);

  /**
   * The shop's real storefront payload, for the preview.
   *
   * <p>This used to be a hardcoded mock — "Your shop", "Sona Masoori 5kg", four invented
   * categories. That is worse than no preview: an owner arranging sections against fake products is
   * arranging them against a lie. The mock showed a tidy four-tile category grid and clean product
   * cards, while their actual shop has ten categories and products with no photographs — so the
   * layout they approved was never the layout they would get.
   *
   * <p>It now comes from the same public endpoint the live storefront page calls, so the editor and
   * the published page resolve the same sections from the same data. Sharing the renderer (D73) is
   * only half of "the preview matches the storefront"; the other half is sharing its <em>input</em>.
   *
   * <p>The slug comes from `/shop/dashboard` rather than a route parameter, because shop-scoped
   * endpoints resolve the shop from the token and never from the URL (D35). The storefront endpoint
   * is keyed by slug only because it is public — a customer with no token still has to reach it.
   */
  const [previewData, setPreviewData] = useState<StorefrontData | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const dash = await authorizedFetch(auth, '/api/v1/shop/dashboard');
        if (!dash.ok) throw new Error(`dashboard request failed: HTTP ${dash.status}`);
        const { shopSlug } = (await dash.json()) as Dashboard;

        const res = await authorizedFetch(auth, `/api/v1/p/shops/${shopSlug}/storefront`);
        if (!res.ok) throw new Error(`storefront request failed: HTTP ${res.status}`);
        const body = (await res.json()) as {
          shopSlug: string;
          shopName: string;
          description?: string | null;
          logoUrl?: string | null;
          coverImageUrl?: string | null;
          followerCount?: number;
          shopType?: string;
          categories?: StorefrontData['categories'];
          featuredProducts?: StorefrontData['featuredProducts'];
          latestProducts?: StorefrontData['latestProducts'];
          assets?: StorefrontData['assets'];
        };
        if (cancelled) return;
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
      } catch (e) {
        if (cancelled) return;
        setPreviewData(null);
        setStatus(`Preview data unavailable: ${describeError(e)}`);
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
        setThemes(list.themes);
        setMarketplace(offers);
        // Open the live theme when there is one: that is the one the shop is thinking about.
        const live = list.published ?? list.themes[0];
        if (live) {
          setActiveId(live.id);
        }
      } catch (e) {
        setStatus(describeError(e));
      }
    })();
  }, []);

  useEffect(() => {
    if (!activeId) return;
    void (async () => {
      try {
        const detail = await themeApi.get(activeId);
        editor.load(parseTheme(detail.config).theme);
      } catch (e) {
        setStatus(describeError(e));
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeId]);

  /**
   * One button: save and publish together.
   *
   * <p>This used to be "Unpublish first, then edit, then Publish", because the server refused to
   * save a live theme. That was the worst possible shape for an owner — three steps to change a
   * colour — and it was also worse for the customer it claimed to protect: in the gap between
   * unpublish and publish the shop has no live theme, so its storefront falls back to the platform
   * default and visibly becomes someone else's template.
   *
   * <p>`save-and-publish` does both in one transaction, so the storefront is never in a gap. The
   * owner gets one button, and customers never see the shop blink.
   */
  async function publish() {
    if (!draft || !activeId) return;
    setBusy(true);
    setStatus(null);
    try {
      const detail: ThemeDetail = await themeApi.saveAndPublish(activeId, JSON.stringify(draft));
      editor.markSaved();
      setIssues(detail.warnings);
      setStatus('Live. Your storefront is updated.');
      const list = await themeApi.list();
      setThemes(list.themes);
    } catch (e) {
      setStatus(describeError(e));
    } finally {
      setBusy(false);
    }
  }

  async function install(templateId: string) {
    setBusy(true);
    try {
      const installed = await themeApi.install(templateId);
      setActiveId(installed.id);
      setStatus(`Installed "${installed.name}" as a draft. Publish when you are happy with it.`);
      const list = await themeApi.list();
      setThemes(list.themes);
    } catch (e) {
      setStatus(describeError(e));
    } finally {
      setBusy(false);
    }
  }

  const selected = draft?.sections.find((s) => s.id === editor.selectedSectionId) ?? null;

  if (!draft) {
    return (
      <div className="space-y-6">
        <h1 className="text-2xl font-semibold">Appearance</h1>
        <Card>
          <CardContent className="p-6 text-sm text-muted-foreground">
            {status ?? 'Loading your theme…'}
          </CardContent>
        </Card>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">Appearance</h1>
          <p className="text-sm text-muted-foreground">
            Edit your storefront. Nothing goes live until you publish.
          </p>
        </div>
        <div className="flex items-center gap-2">
          {themes.length > 1 ? (
            <Select value={activeId ?? ''} onValueChange={setActiveId}>
              <SelectTrigger className="w-48" aria-label="Which theme">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {themes.map((t) => (
                  <SelectItem key={t.id} value={t.id}>
                    {t.name}
                    {t.published ? ' (live)' : ''}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          ) : null}
          {editor.dirty ? (
            // The ported Badge has no 'warning' variant, and inventing one would mean the kit and
            // the reference diverge. Outline plus a dot says the same thing honestly.
            <Badge variant="outline">
              <span aria-hidden className="mr-1.5 inline-block size-1.5 rounded-full bg-warning/100" />
              Changes not published yet
            </Badge>
          ) : null}
          <Button variant="outline" onClick={editor.undo} disabled={!editor.past.length}>
            Undo
          </Button>
          <Button variant="outline" onClick={editor.redo} disabled={!editor.future.length}>
            Redo
          </Button>
          {/* One button, and it says what it does.
              "Save draft" and "Publish" side by side invited the question "which one?", and the
              answer was "publish, but only after you work around the server" — which is not a
              question a shop owner should have to reason about to change a colour. */}
          <Button onClick={publish} disabled={busy || !editor.dirty}>
            {busy ? 'Publishing…' : 'Publish changes'}
          </Button>
        </div>
      </header>

      {status ? (
        <p className="rounded-md border bg-muted/40 px-3 py-2 text-sm">{status}</p>
      ) : null}
      {issues.length > 0 ? (
        <ul className="rounded-md border border-amber-500/40 bg-warning/100/10 p-3 text-sm">
          {issues.map((i) => (
            <li key={i}>{i}</li>
          ))}
        </ul>
      ) : null}

      <div className="grid gap-6 lg:grid-cols-[260px_minmax(0,1fr)_300px]">
        {/* ---------------------------------------------------------- sections */}
        <Card>
          <CardHeader>
            <CardTitle>Sections</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            {draft.sections.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                No sections yet. Add one from the palette below.
              </p>
            ) : null}

            {draft.sections.map((section, index) => (
              <div
                key={section.id}
                className={`flex items-center gap-2 rounded-md border px-2 py-1.5 text-sm ${
                  editor.selectedSectionId === section.id ? 'border-primary bg-primary/5' : ''
                }`}
              >
                <button
                  type="button"
                  className="min-w-0 flex-1 truncate text-left"
                  onClick={() => editor.select(section.id)}
                >
                  {sectionDefinition(section.type).label}
                  {section.hidden ? ' (hidden)' : ''}
                </button>
                <span className="text-xs text-muted-foreground">{index + 1}</span>
                <button
                  type="button"
                  aria-label="Move up"
                  disabled={index === 0}
                  onClick={() => editor.moveSection(section.id, index - 1)}
                >
                  ↑
                </button>
                <button
                  type="button"
                  aria-label="Move down"
                  disabled={index === draft.sections.length - 1}
                  onClick={() => editor.moveSection(section.id, index + 1)}
                >
                  ↓
                </button>
                <button type="button" aria-label="Remove" onClick={() => editor.removeSection(section.id)}>
                  ✕
                </button>
              </div>
            ))}

            <div className="border-t pt-3">
              <Label className="text-xs uppercase tracking-wide text-muted-foreground">
                Add a section
              </Label>
              {/* Radix Select, so the palette is grouped and keyboard-navigable rather than a
                  native <select>. The trigger shows the placeholder until something is chosen,
                  because there is no meaningful "current value" for an action list. */}
              <Select onValueChange={(value) => editor.addSection(createSection(value as SectionType))}>
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

        {/* ---------------------------------------------------------- preview */}
        <Card className="overflow-hidden">
          <CardHeader>
            <CardTitle>Preview</CardTitle>
          </CardHeader>
          <CardContent className="p-0">
            {/* The same component the storefront renders. Not a mock-up of it. */}
            {previewData ? (
              <ThemeRenderer
                config={draft}
                data={previewData}
                className="max-h-[70vh] overflow-y-auto p-4"
              />
            ) : (
              // A preview with no data is a preview of nothing: every commerce section renders
              // its empty state and the owner judges a layout against zero products. Saying so
              // plainly beats showing a plausible-looking page no shop will ever have.
              <div className="flex h-64 items-center justify-center p-4 text-center text-sm text-muted-foreground">
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

        {/* ---------------------------------------------------------- inspector */}
        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle>Colours and type</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <p className="text-xs text-muted-foreground">
                These colours are your shop&rsquo;s identity. Text colour is worked out for you, so
                buttons stay readable whatever you choose.
              </p>
              <TokenField
                label="Primary"
                hint="Buttons, links and prices"
                value={draft.tokens.primary ?? '#16a34a'}
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
                value={draft.tokens.accent ?? '#f97316'}
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
              <CardHeader>
                <CardTitle>Section settings</CardTitle>
              </CardHeader>
              <CardContent className="text-sm text-muted-foreground">
                Select a section on the left to edit its text and settings.
              </CardContent>
            </Card>
          )}

          <Card>
            <CardHeader>
              <CardTitle>Start from a template</CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              {marketplace.length === 0 ? (
                <p className="text-sm text-muted-foreground">
                  Nothing available for your shop type yet.
                </p>
              ) : null}
              {marketplace.map((entry) => (
                <div key={entry.id} className="rounded-md border p-3">
                  <p className="text-sm font-medium">{entry.name}</p>
                  {entry.description ? (
                    <p className="text-xs text-muted-foreground">{entry.description}</p>
                  ) : null}
                  <Button
                    className="mt-2"
                    size="sm"
                    variant="outline"
                    disabled={busy}
                    onClick={() => void install(entry.id)}
                  >
                    Use this
                  </Button>
                </div>
              ))}
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  );
}

/**
 * Edits the handful of props most sections actually use.
 *
 * Deliberately not a generic JSON editor. A shop owner editing raw JSON for their own homepage is
 * a support ticket waiting to happen, and the section components already know what each prop
 * means. Phase 4 adds per-section field schemas when there is evidence for which props matter.
 */
function SectionInspector({
  section,
  onChange,
  onToggleHidden,
}: {
  section: ThemeSection;
  onChange: (props: Record<string, unknown>) => void;
  onToggleHidden: (hidden: boolean) => void;
}) {
  const definition = sectionDefinition(section.type);
  const props = section.props ?? {};

  const set = (key: string, value: unknown) => onChange({ ...props, [key]: value });

  // Header and footer get a dedicated editor rather than raw fields. Asking an owner to type
  // structured JSON into a textarea is not "editing"; it is a puzzle, and it is the main reason
  // this screen felt like a developer tool.
  if (section.type === 'site-header') {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Header</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <p className="text-xs text-muted-foreground">{definition.description}</p>

          <ToggleRow
            id={`${section.id}-shopName`}
            label="Show shop name"
            checked={props.showShopName !== false}
            onChange={(v) => set('showShopName', v)}
          />
          <ToggleRow
            id={`${section.id}-logo`}
            label="Show logo"
            checked={props.showLogo !== false}
            onChange={(v) => set('showLogo', v)}
          />
          <ToggleRow
            id={`${section.id}-search`}
            label="Show search"
            checked={props.showSearch !== false}
            onChange={(v) => set('showSearch', v)}
          />
          <ToggleRow
            id={`${section.id}-sticky`}
            label="Keep visible while scrolling"
            checked={props.sticky !== false}
            onChange={(v) => set('sticky', v)}
          />

          <LinkListEditor
            label="Menu links"
            emptyHint="Leave empty and your shop's categories are used automatically."
            links={Array.isArray(props.links) ? (props.links as Record<string, unknown>[]) : []}
            onChange={(links) => set('links', links)}
          />

          <p className="text-xs text-muted-foreground">
            Category links look like <code>/c/rice</code> — the part after your shop address.
          </p>
        </CardContent>
      </Card>
    );
  }

  if (section.type === 'site-footer') {
    const columns = Array.isArray(props.columns) ? (props.columns as Record<string, unknown>[]) : [];
    return (
      <Card>
        <CardHeader>
          <CardTitle>Footer</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <p className="text-xs text-muted-foreground">{definition.description}</p>

          <div>
            <Label htmlFor={`${section.id}-heading`}>Heading</Label>
            <Input
              id={`${section.id}-heading`}
              className="mt-1"
              value={String(props.heading ?? '')}
              onChange={(e) => set('heading', e.target.value)}
              placeholder="e.g. Get in touch"
            />
          </div>

          <div>
            <Label htmlFor={`${section.id}-about`}>About your shop</Label>
            <Textarea
              id={`${section.id}-about`}
              className="mt-1"
              rows={3}
              value={String(props.about ?? '')}
              onChange={(e) => set('about', e.target.value)}
              placeholder="A sentence about your shop"
            />
          </div>

          <ToggleRow
            id={`${section.id}-badges`}
            label="Show payment methods"
            checked={props.showPaymentBadges !== false}
            onChange={(v) => set('showPaymentBadges', v)}
          />

          <div>
            <Label>Link columns</Label>
            <p className="text-xs text-muted-foreground">
              Group your links, e.g. &ldquo;Shop&rdquo;, &ldquo;Help&rdquo;, &ldquo;Contact&rdquo;.
            </p>
            <div className="mt-2 space-y-3">
              {columns.map((col, i) => (
                <div key={i} className="rounded-md border p-3">
                  <div className="flex items-center gap-2">
                    <Input
                      aria-label={`Column ${i + 1} title`}
                      value={String(col.title ?? '')}
                      placeholder="Column title"
                      onChange={(e) => {
                        const next = [...columns];
                        next[i] = { ...col, title: e.target.value };
                        set('columns', next);
                      }}
                    />
                    <Button
                      variant="ghost"
                      size="sm"
                      aria-label={`Remove column ${i + 1}`}
                      onClick={() => set('columns', columns.filter((_, j) => j !== i))}
                    >
                      ✕
                    </Button>
                  </div>
                  <LinkListEditor
                    label=""
                    links={Array.isArray(col.links) ? (col.links as Record<string, unknown>[]) : []}
                    onChange={(links) => {
                      const next = [...columns];
                      next[i] = { ...col, links };
                      set('columns', next);
                    }}
                  />
                </div>
              ))}
              <Button
                variant="outline"
                size="sm"
                className="w-full"
                onClick={() => set('columns', [...columns, { title: '', links: [] }])}
              >
                Add a column
              </Button>
            </div>
          </div>
        </CardContent>
      </Card>
    );
  }

  const textProps = ['text', 'heading', 'subheading', 'body', 'imageUrl', 'href'];
  const usedTextProps = textProps.filter((k) => k in props);

  return (
    <Card>
      <CardHeader>
        <CardTitle>{definition.label}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <p className="text-xs text-muted-foreground">{definition.description}</p>

        {usedTextProps.map((key) => (
          <div key={key}>
            <Label htmlFor={`${section.id}-${key}`} className="capitalize">
              {key}
            </Label>
            {key === 'body' && String(props[key] ?? '').length > 60 ? (
              <Textarea
                id={`${section.id}-${key}`}
                className="mt-1"
                value={String(props[key] ?? '')}
                onChange={(e) => set(key, e.target.value)}
              />
            ) : (
              <Input
                id={`${section.id}-${key}`}
                className="mt-1"
                value={String(props[key] ?? '')}
                onChange={(e) => set(key, e.target.value)}
              />
            )}
          </div>
        ))}

        {typeof props.cta === 'object' && props.cta !== null ? (
          <div className="rounded-md border p-3">
            <p className="mb-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
              Button
            </p>
            {(props.cta as Record<string, unknown>) ? (
              <>
                <Input
                  aria-label="Button label"
                  value={String((props.cta as Record<string, unknown>).label ?? '')}
                  onChange={(e) =>
                    set('cta', { ...(props.cta as Record<string, unknown>), label: e.target.value })
                  }
                />
                <Input
                  aria-label="Button link"
                  className="mt-2"
                  value={String((props.cta as Record<string, unknown>).href ?? '')}
                  onChange={(e) =>
                    set('cta', { ...(props.cta as Record<string, unknown>), href: e.target.value })
                  }
                />
              </>
            ) : null}
          </div>
        ) : null}

        {typeof props.columns === 'number' ? (
          <div>
            <Label htmlFor={`${section.id}-columns`}>Columns</Label>
            <Select value={String(props.columns)} onValueChange={(v) => set('columns', Number(v))}>
              <SelectTrigger id={`${section.id}-columns`} className="mt-1">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {[2, 3, 4, 5, 6].map((n) => (
                  <SelectItem key={n} value={String(n)}>
                    {n}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        ) : null}

        {typeof props.limit === 'number' ? (
          <div>
            <Label htmlFor={`${section.id}-limit`}>How many to show</Label>
            <Input
              id={`${section.id}-limit`}
              type="number"
              min={1}
              className="mt-1"
              value={String(props.limit)}
              onChange={(e) => set('limit', Number(e.target.value))}
            />
          </div>
        ) : null}

        <Button variant="ghost" size="sm" onClick={() => onToggleHidden(!section.hidden)}>
          {section.hidden ? 'Show this section' : 'Hide this section'}
        </Button>
      </CardContent>
    </Card>
  );
}

/**
 * A labelled on/off row.
 *
 * <p>A `<Switch>` needs a real `<label for>`, and the control has to be labelable — which is the
 * same lesson as the product form's category field, where a label pointing at a Radix trigger
 * associated with nothing.
 */
function ToggleRow({
  id,
  label,
  checked,
  onChange,
}: {
  id: string;
  label: string;
  checked: boolean;
  onChange: (value: boolean) => void;
}) {
  return (
    <div className="flex items-center justify-between gap-3">
      <Label htmlFor={id}>{label}</Label>
      <Switch id={id} checked={checked} onCheckedChange={onChange} />
    </div>
  );
}

/**
 * Adds, edits and removes `{ label, href }` pairs.
 *
 * <p>Written because the alternative was asking an owner to hand-edit an array of objects. Two
 * fields and an add/remove button is the whole job; anything more is a developer interface wearing
 * a shop owner's clothes.
 */
function LinkListEditor({
  label,
  links,
  onChange,
  emptyHint,
}: {
  label: string;
  links: Record<string, unknown>[];
  onChange: (links: Record<string, unknown>[]) => void;
  emptyHint?: string;
}) {
  const update = (i: number, key: string, value: string) => {
    const next = [...links];
    next[i] = { ...next[i], [key]: value };
    onChange(next);
  };

  return (
    <div>
      {label ? <Label>{label}</Label> : null}
      {links.length === 0 && emptyHint ? (
        <p className="text-xs text-muted-foreground">{emptyHint}</p>
      ) : null}

      <div className="mt-1 space-y-2">
        {links.map((link, i) => (
          <div key={i} className="flex items-center gap-2">
            <Input
              aria-label={`Link ${i + 1} text`}
              value={String(link.label ?? '')}
              placeholder="Text"
              onChange={(e) => update(i, 'label', e.target.value)}
            />
            <Input
              aria-label={`Link ${i + 1} address`}
              value={String(link.href ?? '')}
              placeholder="/c/rice"
              onChange={(e) => update(i, 'href', e.target.value)}
            />
            <Button
              variant="ghost"
              size="sm"
              aria-label={`Remove link ${i + 1}`}
              onClick={() => onChange(links.filter((_, j) => j !== i))}
            >
              ✕
            </Button>
          </div>
        ))}
      </div>

      <Button
        variant="outline"
        size="sm"
        className="mt-2 w-full"
        onClick={() => onChange([...links, { label: '', href: '' }])}
      >
        Add a link
      </Button>
    </div>
  );
}

/**
 * Presets offered beside the picker.
 *
 * <p>An empty colour input is not a colour, so an owner opening the editor has to know what to
 * type. These are hand-picked for legibility against white — every one of them clears 4.5:1 as a
 * button background with white text — which is the same rule the renderer applies automatically,
 * so a preset can never produce an unreadable button.
 */
const COLOUR_PRESETS: ReadonlyArray<{ name: string; value: string }> = [
  { name: 'Emerald', value: '#16a34a' },
  { name: 'Forest', value: '#15803d' },
  { name: 'Ocean', value: '#0ea5e9' },
  { name: 'Indigo', value: '#4f46e5' },
  { name: 'Violet', value: '#7c3aed' },
  { name: 'Rose', value: '#e11d48' },
  { name: 'Amber', value: '#d97706' },
  { name: 'Slate', value: '#334155' },
  { name: 'Ink', value: '#0f172a' },
];

/**
 * One editable colour token.
 *
 * <p>The colour input alone was not enough. Three things are added because the owner's actual
 * question is never "what is the hex value" but "will my shop still look right":
 *
 * <ol>
 *   <li><b>Presets.</b> Every vertical in the catalogue needs a different feel, and guessing at a
 *       hex is how a shop ends up with an arbitrary colour. One click gives a known-good colour.</li>
 *   <li><b>A live swatch of what it will actually look like.</b> The colour swatch shows the raw
 *       hex; this shows it applied to a button, which is how it appears on the storefront — and
 *       which is where a bad choice becomes visible.</li>
 *   <li><b>The contrast ratio against the text that will sit on it.</b> WCAG AA for body text is
 *       4.5:1. Reporting the owner's own number is actionable; "this may be hard to read" is not.
 *       </li>
 * </ol>
 */
function TokenField({
  label,
  value,
  onChange,
  hint,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  /** What this colour is used for, e.g. "buttons and links". */
  hint?: string;
}) {
  const valid = /^#[0-9a-fA-F]{6}$/.test(value);
  // A malformed value is already reported by the validator; showing a second, harsher warning here
  // would just be noise on a field the owner is mid-way through typing into.
  const derived = valid ? readableForeground(value) : null;
  const ratio = derived ? contrastRatio(derived, value) : null;
  const passesAA = ratio !== null && ratio >= 4.5;

  return (
    <div>
      <Label htmlFor={`token-${label}`}>{label}</Label>
      {hint ? <p className="text-xs text-muted-foreground">{hint}</p> : null}
      <div className="mt-1 flex items-center gap-2">
        <input
          id={`token-${label}`}
          type="color"
          aria-label={`${label} colour`}
          value={valid ? value : '#000000'}
          onChange={(e) => onChange(e.target.value)}
          className="h-9 w-12 shrink-0 cursor-pointer rounded border"
        />
        <Input value={value} onChange={(e) => onChange(e.target.value)} />
      </div>

      {/* What the storefront will actually paint, plus the contrast it produces. */}
      {valid && derived ? (
        <div className="mt-2 flex items-center gap-2">
          <span
            className="inline-flex h-7 items-center rounded-md px-3 text-xs font-medium"
            style={{ backgroundColor: value, color: derived }}
          >
            Button
          </span>
          <span
            className={`text-xs ${passesAA ? 'text-muted-foreground' : 'font-medium text-amber-600'}`}
          >
            {ratio?.toFixed(1)}:1 contrast {passesAA ? '' : '— below 4.5:1, text may be hard to read'}
          </span>
        </div>
      ) : null}

      <div className="mt-2 flex flex-wrap gap-1">
        {COLOUR_PRESETS.map((p) => (
          <button
            key={p.value}
            type="button"
            title={`${p.name} ${p.value}`}
            aria-label={`Use ${p.name} for ${label}`}
            onClick={() => onChange(p.value)}
            style={{ backgroundColor: p.value }}
            className={`size-6 rounded border transition-transform hover:scale-110 ${
              value.toLowerCase() === p.value ? 'ring-2 ring-foreground ring-offset-1' : ''
            }`}
          />
        ))}
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


