import { useEffect, useMemo, useState } from 'react';

import { theme as themeApi } from '@store-app/api-client';
import type { ThemeDetail, ThemeView } from '@store-app/api-client';
import {
  ThemeRenderer,
  createSection,
  paletteGroups,
  parseTheme,
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
export function ShopTheme() {
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

  /** Preview data. Real products would come from the shop endpoint; the shape is what matters. */
  const previewData = useMemo<StorefrontData>(
    () => ({
      shopSlug: 'your-shop',
      shopName: 'Your shop',
      description: 'This is a preview. Nothing here is live until you publish.',
      categories: [
        { code: 'RICE', label: 'Rice & Grains', productCount: 12 },
        { code: 'LENTIL', label: 'Lentils', productCount: 8 },
        { code: 'SPICES', label: 'Spices', productCount: 24 },
        { code: 'OIL', label: 'Cooking Oil', productCount: 5 },
      ],
      featuredProducts: [
        { name: 'Sona Masoori 5kg', price: 45000, categoryLabel: 'Rice & Grains' },
        { name: 'Organic Red Lentils 1kg', price: 18000, categoryLabel: 'Lentils' },
        { name: 'Himalayan Salt 1kg', price: 9000, categoryLabel: 'Spices' },
        { name: 'Cold-pressed Mustard Oil 1L', price: 62000, categoryLabel: 'Cooking Oil' },
      ],
      latestProducts: [
        { name: 'Whole Black Pepper 100g', price: 14000, categoryLabel: 'Spices' },
        { name: 'Basmati Rice 1kg', price: 22000, categoryLabel: 'Rice & Grains' },
        { name: 'Red Lentils 2kg', price: 32000, categoryLabel: 'Lentils' },
      ],
      assets: [],
    }),
    [],
  );

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

  async function save() {
    if (!draft || !activeId) return;
    setBusy(true);
    setStatus(null);
    try {
      const detail: ThemeDetail = await themeApi.save(activeId, JSON.stringify(draft));
      editor.markSaved();
      setIssues(detail.warnings);
      setStatus('Draft saved.');
    } catch (e) {
      setStatus(describeError(e));
    } finally {
      setBusy(false);
    }
  }

  async function publish() {
    if (!activeId) return;
    setBusy(true);
    setStatus(null);
    try {
      // Publish only after a successful save, so what goes live is what the editor is showing.
      if (editor.dirty) {
        await themeApi.save(activeId, JSON.stringify(editor.draft));
        editor.markSaved();
      }
      await themeApi.publish(activeId);
      setStatus('Published. Your storefront is live.');
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
              <span aria-hidden className="mr-1.5 inline-block size-1.5 rounded-full bg-amber-500" />
              Unsaved changes
            </Badge>
          ) : null}
          <Button variant="outline" onClick={editor.undo} disabled={!editor.past.length}>
            Undo
          </Button>
          <Button variant="outline" onClick={editor.redo} disabled={!editor.future.length}>
            Redo
          </Button>
          <Button variant="secondary" onClick={save} disabled={busy || !editor.dirty}>
            Save draft
          </Button>
          <Button onClick={publish} disabled={busy}>
            Publish
          </Button>
        </div>
      </header>

      {status ? (
        <p className="rounded-md border bg-muted/40 px-3 py-2 text-sm">{status}</p>
      ) : null}
      {issues.length > 0 ? (
        <ul className="rounded-md border border-amber-500/40 bg-amber-500/10 p-3 text-sm">
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
            <ThemeRenderer
              config={draft}
              data={previewData}
              onIssues={setIssues}
              className="max-h-[70vh] overflow-y-auto p-4"
            />
          </CardContent>
        </Card>

        {/* ---------------------------------------------------------- inspector */}
        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle>Colours and type</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <TokenField
                label="Primary"
                value={draft.tokens.primary ?? '#16a34a'}
                onChange={(primary) => editor.updateTokens({ primary })}
              />
              <TokenField
                label="Background"
                value={draft.tokens.background ?? '#ffffff'}
                onChange={(background) => editor.updateTokens({ background })}
              />
              <TokenField
                label="Accent"
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

function TokenField({
  label,
  value,
  onChange,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <div>
      <Label htmlFor={`token-${label}`}>{label}</Label>
      <div className="mt-1 flex items-center gap-2">
        <input
          id={`token-${label}`}
          type="color"
          aria-label={`${label} colour`}
          value={/^#[0-9a-fA-F]{6}$/.test(value) ? value : '#000000'}
          onChange={(e) => onChange(e.target.value)}
          className="h-9 w-12 cursor-pointer rounded border"
        />
        <Input value={value} onChange={(e) => onChange(e.target.value)} />
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


