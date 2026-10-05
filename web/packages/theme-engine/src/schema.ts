import { z } from 'zod';

/**
 * The theme document schema (plan decision D8).
 *
 * This is the contract between three things: the admin who publishes a template, the shop who
 * customises it, and the renderer that turns it into HTML. Keeping it in one zod schema means
 * a malformed theme is rejected at the point it is written, not discovered as a blank page in
 * front of a customer.
 *
 * `schemaVersion` is stored on both `theme_template` and `shop_theme`. When the section set
 * changes, bump it and add a migration; a v1 theme keeps rendering through the v1 renderer
 * rather than silently changing meaning.
 */
export const THEME_SCHEMA_VERSION = 1;

/**
 * Section types. A closed union is the point of the design (D8): templates are JSON over a
 * fixed set of components, so a shop can never upload code, and a typo is a build error rather
 * than a runtime blank space.
 */
export const SECTION_TYPES = [
  'announcement-bar',
  'site-header',
  'hero',
  'category-grid',
  'product-carousel',
  'product-grid',
  'collection-list',
  'promo-banner',
  'image-with-text',
  'rich-text',
  'video',
  'gallery',
  'testimonials',
  'faq',
  'newsletter',
  'countdown',
  'trust-badges',
  'feature-list',
  'cta-banner',
  'map',
  'social-feed',
  'divider',
  'spacer',
  'html',
  'site-footer',
  'unknown-section',
] as const;

export const sectionTypeSchema = z.enum(SECTION_TYPES);
export type SectionType = z.infer<typeof sectionTypeSchema>;

/**
 * Props are intentionally permissive per type. A strict union of 24 prop shapes would need a
 * discriminated union the moment two sections share a field, and would make every new prop a
 * breaking schema change. Instead the base shape is validated structurally and each section
 * component validates its own props at render time.
 */
export const sectionSchema = z.object({
  id: z.string().min(1),
  type: sectionTypeSchema,
  /** Render variant within a type, e.g. hero has 'split-image' and 'centered'. */
  variant: z.string().optional(),
  props: z.record(z.unknown()).default({}),
  /** Section is present but hidden; useful for toggling without deleting work. */
  hidden: z.boolean().optional(),
});

export type ThemeSection = z.infer<typeof sectionSchema>;

/**
 * Design tokens map 1:1 onto shadcn's CSS custom properties, so a theme's colours and radii
 * flow into every shadcn component automatically. That is why the storefront and the dashboard
 * look consistent without a second token system.
 */
export const themeTokensSchema = z.object({
  primary: z.string().regex(/^#[0-9a-fA-F]{6}$/, 'Use a 6-digit hex colour').optional(),
  background: z.string().regex(/^#[0-9a-fA-F]{6}$/).optional(),
  foreground: z.string().regex(/^#[0-9a-fA-F]{6}$/).optional(),
  muted: z.string().regex(/^#[0-9a-fA-F]{6}$/).optional(),
  accent: z.string().regex(/^#[0-9a-fA-F]{6}$/).optional(),
  destructive: z.string().regex(/^#[0-9a-fA-F]{6}$/).optional(),
  border: z.string().regex(/^#[0-9a-fA-F]{6}$/).optional(),
  ring: z.string().regex(/^#[0-9a-fA-F]{6}$/).optional(),
  fontHeading: z.string().trim().min(1).max(80).optional(),
  fontBody: z.string().trim().min(1).max(80).optional(),
  radius: z.string().trim().min(1).max(20).optional(),
  mode: z.enum(['light', 'dark', 'system']).optional(),
  /**
   * Paid-tier custom CSS (D15). Deliberately accepted by the schema but NOT rendered — the
   * field exists so a saved theme does not lose data when the feature is still deferred, and
   * shipping it is gated on a sanitizer that is not built yet.
   */
  customCss: z.string().max(20000).optional(),
});

export type ThemeTokens = z.infer<typeof themeTokensSchema>;

/**
 * A navigation link.
 *
 * <p>Site-relative, an absolute `https` URL, or a same-page fragment.
 *
 * <p><b>The fragment case was missing and broke every storefront.</b> The server's
 * `ThemeValidator.isSafeHref` has always accepted `#`, and every shipped default theme uses
 * `#products` for its hero call-to-action — but this schema only allowed `/` and `https://`.
 * So a theme the server had validated rendered as an <em>empty page</em>: `parseTheme` rejected
 * the whole document, returned an empty theme, and the homepage showed nothing at all.
 *
 * <p>That is the worst possible failure: no error, no partial render, just a blank storefront.
 * The lesson is that the two validators must agree, and that disagreement has to fail loudly
 * rather than silently — see `ThemeRenderer`, which now reports issues and renders a fallback.
 */
export const navItemSchema = z.object({
  label: z.string().trim().min(1).max(60),
  href: z
    .string()
    .trim()
    .refine(
      (h) =>
        h.startsWith('/') ||
        h.startsWith('https://') ||
        (h.startsWith('#') && h.length > 1),
      {
        message:
          'Links must be site-relative, an absolute https URL, or a same-page fragment like #products',
      },
    )
    // Protocol-relative "//evil.com" leaves the site, so it is not a site-relative path even
    // though it starts with a slash. Matches ThemeValidator.isSafeHref.
    .refine((h) => !(h.startsWith('//') || h.startsWith('/\\')), {
      message: 'Protocol-relative links leave the site',
    }),
});

/**
 * The whole document.
 *
 * <p>Section ids are checked for uniqueness here as well as on the server. A duplicate id makes
 * the editor's "select this section" ambiguous and renders the section twice, which is the kind of
 * thing that is obvious the moment it happens and invisible the moment it is fixed by hand.
 */
export const themeConfigSchema = z
  .object({
    schemaVersion: z.number().int().positive().default(THEME_SCHEMA_VERSION),
    tokens: themeTokensSchema.default({}),
    sections: z.array(sectionSchema).default([]),
  nav: z.object({ menu: z.array(navItemSchema).default([]) }).default({ menu: [] }),
  footer: z
    .object({
      assetHandle: z.string().optional(),
      columns: z.number().int().min(1).max(6).optional(),
      showPaymentBadges: z.boolean().optional(),
      showSocial: z.boolean().optional(),
    })
      .default({}),
  })
  .refine(
    (t) => new Set(t.sections.map((s) => s.id)).size === t.sections.length,
    {
      message: 'Two sections share an id. Ids must be unique — the editor uses them to select a section.',
      path: ['sections'],
    },
  );

export type ThemeConfig = z.infer<typeof themeConfigSchema>;

/**
 * Parses a theme, falling back to an empty theme rather than throwing.
 *
 * A shop must never be able to publish a document that leaves the storefront unrenderable, so
 * a failed parse degrades to a bare page with the problem reported, instead of a 500.
 *
 * <p>Accepts a JSON **string** as well as an object, because the theme crosses the wire as a string
 * (`theme_template.config` and `shop_theme.config` are jsonb, serialised to a string by the API) and
 * the alternative was two callers with different habits. One did `parseTheme(detail.config)` and
 * the other did `parseTheme(JSON.stringify(draft))`; the first produced an empty theme and a
 * `(root): Expected object, received string` issue, so the Appearance editor silently loaded no
 * sections at all. Handling both here makes that mistake unmakeable.
 */
export function parseTheme(input: unknown): {
  theme: ThemeConfig;
  issues: string[];
} {
  let value = input;

  if (typeof value === 'string') {
    try {
      value = JSON.parse(value);
    } catch {
      // Malformed JSON. Reported as an issue rather than thrown, so a storefront degrades to a
      // visible notice instead of a 500 in front of a customer.
      return {
        theme: emptyTheme(),
        issues: ['(root): the theme document is not valid JSON'],
      };
    }
  }

  const result = themeConfigSchema.safeParse(value);
  if (result.success) {
    return { theme: result.data, issues: [] };
  }
  return {
    theme: emptyTheme(),
    issues: result.error.issues.map(
      (i) => `${i.path.join('.') || '(root)'}: ${i.message}`,
    ),
  };
}

/** The document a failed parse degrades to: valid, empty, and renderable. */
function emptyTheme(): ThemeConfig {
  return {
    schemaVersion: THEME_SCHEMA_VERSION,
    tokens: {},
    sections: [],
    nav: { menu: [] },
    footer: {},
  };
}

/** Strips the deferred customCss field before a theme is sent to the browser (D15). */
export function stripUnsafeTokens(theme: ThemeConfig): ThemeConfig {
  const { customCss: _omitted, ...safeTokens } = theme.tokens;
  return { ...theme, tokens: safeTokens };
}
