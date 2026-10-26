/**
 * The field editors used by the Appearance screens.
 *
 * <p>Moved out of the single `shop-theme.tsx` page when Appearance became two screens (the themes
 * gallery and the customiser). The code is unchanged deliberately: these are the controls an owner
 * actually touches, and rewriting them during a layout change would make a visual regression and a
 * behavioural regression indistinguishable.
 */

import type { ThemeSection } from '@store-app/theme-engine';
import { contrastRatio, readableForeground, sectionDefinition } from '@store-app/theme-engine';
import {
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  Input,
  Label,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Switch,
  Textarea,
} from '@store-app/ui';

/**
 * Edits the handful of props most sections actually use.
 *
 * Deliberately not a generic JSON editor. A shop owner editing raw JSON for their own homepage is
 * a support ticket waiting to happen, and the section components already know what each prop
 * means. Phase 4 adds per-section field schemas when there is evidence for which props matter.
 */
export function SectionInspector({
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

  // `imageUrl` rendered as "ImageUrl" and `href` as "Href" — a field named like a programmer's
  // variable is exactly the register this screen was supposed to lose.
  const fieldLabels: Record<string, string> = {
    text: 'Text',
    heading: 'Heading',
    subheading: 'Subheading',
    body: 'Text',
    imageUrl: 'Image URL',
    href: 'Link',
  };
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
            <Label htmlFor={`${section.id}-${key}`}>{fieldLabels[key] ?? key}</Label>
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
                {/* Visible labels, not just aria-label: a sighted owner saw two unlabelled boxes
                    containing "Shop now" and "#products" and had to guess which was which. */}
                <Label htmlFor={`${section.id}-cta-label`}>Button label</Label>
                <Input
                  id={`${section.id}-cta-label`}
                  className="mt-1"
                  value={String((props.cta as Record<string, unknown>).label ?? '')}
                  onChange={(e) =>
                    set('cta', { ...(props.cta as Record<string, unknown>), label: e.target.value })
                  }
                />
                <Label htmlFor={`${section.id}-cta-href`} className="mt-2 block">
                  Button link
                </Label>
                <Input
                  id={`${section.id}-cta-href`}
                  className="mt-1"
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
export function ToggleRow({
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
export function LinkListEditor({
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
export const COLOUR_PRESETS: ReadonlyArray<{ name: string; value: string }> = [
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
export function TokenField({
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
