import type { CSSProperties } from 'react';

import type { ThemeConfig, ThemeTokens } from './schema';
import { stripUnsafeTokens } from './schema';

/**
 * The tokens whose value is a colour, and so must be emitted in the form the stylesheet expects.
 *
 * <p>See {@link hexToHslTriplet} for why a hex value cannot simply be passed through.
 */
const COLOUR_TOKENS = new Set([
  'primary',
  'background',
  'foreground',
  'muted',
  'accent',
  'destructive',
  'border',
  'ring',
]);

/**
 * Tokens whose value is a font *family name* rather than a complete stack.
 *
 * <p>See {@link fontStack} for why these cannot be emitted as-written.
 */
const FONT_TOKENS = new Set(['fontHeading', 'fontBody']);

/**
 * Ensures a font token always ends in a generic family.
 *
 * <p><b>A theme naming a font that is not installed used to render in Times.</b> The token was
 * emitted verbatim as `--font-heading: Inter`, and `styles.css` consumed it as
 * `font-family: var(--font-heading, inherit)`. A CSS font-family list with no generic fallback and
 * no installed match does not inherit or degrade to the system sans — the browser drops to its own
 * default, which on Chrome is a serif. Every seeded template names Inter, and Inter is installed on
 * almost no machine, so a brand-new shop's headings were serif: a shop that had chosen "Inter" was
 * being served the opposite of its choice, with nothing anywhere reporting a problem.
 *
 * <p>The platform does not ship webfonts (no network font on a storefront that has to work on a
 * slow connection), so the honest thing is a stack: try the requested family, then the system UI
 * face. Owners who paste a full stack are left alone.
 */
function fontStack(value: string): string {
  const trimmed = value.trim();
  if (!trimmed) return '';
  if (trimmed.includes(',')) return trimmed;

  const family = trimmed.replace(/["'\\;]/g, '').trim();
  return family ? `"${family}", ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif` : '';
}

/**
 * Each colour and the foreground that stays readable on top of it.
 *
 * <p>A shop picks a brand colour and gets white button text unconditionally otherwise. Choose a
 * pale yellow and the call to action becomes white-on-yellow — invisible, and the one element on
 * the page a customer must be able to see and press (the point of the theme engine: an owner
 * building their own shop). Deriving the foreground from the colour's own luminance makes the
 * palette readable by default rather than by the owner's care.
 */
const FOREGROUND_FOR: Record<string, string> = {
  primary: 'primary-foreground',
  background: 'foreground',
  foreground: 'background',
  muted: 'muted-foreground',
  accent: 'accent-foreground',
  destructive: 'destructive-foreground',
};

/** Expands `#abc` to `#aabbcc`. Returns null for anything that is not a hex colour. */
function normaliseHex(value: string): string | null {
  const raw = value.trim();
  const short = /^#([0-9a-f])([0-9a-f])([0-9a-f])$/i.exec(raw);
  const hex = short ? `#${short[1]}${short[1]}${short[2]}${short[2]}${short[3]}${short[3]}` : raw;
  return /^#[0-9a-f]{6}$/i.test(hex) ? hex : null;
}

/**
 * Converts a hex colour into the bare `H S% L%` triplet the stylesheet wraps in `hsl()`.
 *
 * <p><b>This function exists because of a bug that survived five phases.</b> The shadcn tokens in
 * {@code packages/ui/src/styles.css} store colours as space-separated HSL triplets
 * (`--primary: 262 83% 58%`), and every app maps Tailwind's colour utilities through
 * `hsl(var(--primary))` in its `@theme inline` block. A theme document, however, stores colours as
 * 6-digit hex — which is what <em>both</em> validators insist on (`ThemeValidator.java` and the
 * zod schema), and rightly so, because hex is what a colour picker emits.
 *
 * <p>So the value written onto the element was `#7c3aed` while the consumer computed
 * `hsl(#7c3aed)` — not a colour, so the browser silently discarded the declaration. Every storefront
 * rendered monochrome, with white call-to-action text on a transparent background: invisible and
 * unclickable. Nothing errored, the 5,810 backend tests passed, and every appearance check to that
 * point had asserted on HTML/CSS <em>text</em> rather than on a computed style.
 *
 * <p>Converting here is the smallest correct fix: the stored contract stays hex (validated in two
 * languages, editable in the console), the shadcn root tokens stay untouched, and no stored theme
 * document needs migrating. The alternative — dropping the `hsl()` wrapper in `@theme` — would mean
 * rewriting every root token to hex and every `hsl(var(--x))` in `styles.css`: the same bug with a
 * wider blast radius.
 *
 * @returns the triplet, or null when the value is not a hex colour. The caller then passes the
 *   value through untouched, so a future non-colour token is never mangled by this function.
 */
export function hexToHslTriplet(hex: string): string | null {
  const normalised = normaliseHex(hex);
  if (!normalised) return null;

  const r = parseInt(normalised.slice(1, 3), 16) / 255;
  const g = parseInt(normalised.slice(3, 5), 16) / 255;
  const b = parseInt(normalised.slice(5, 7), 16) / 255;

  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const delta = max - min;
  const lightness = (max + min) / 2;

  let hue = 0;
  let saturation = 0;
  if (delta !== 0) {
    saturation = lightness > 0.5 ? delta / (2 - max - min) : delta / (max + min);
    if (max === r) hue = (g - b) / delta + (g < b ? 6 : 0);
    else if (max === g) hue = (b - r) / delta + 2;
    else hue = (r - g) / delta + 4;
    hue /= 6;
  }

  // Two decimal places, not whole units. Integer HSL is lossy: #7c3aed round-trips to
  // rgb(124, 59, 237) instead of rgb(124, 58, 237). The drift is imperceptible channel by
  // channel, but it is a real difference from the colour the owner picked, and it would read as
  // "the preview is not quite my colour" on a colour they chose deliberately. Two decimals puts
  // the round-trip inside one 8-bit step for every colour tested, at no real cost to the markup.
  return `${round2(hue * 360)} ${round2(saturation * 100)}% ${round2(lightness * 100)}%`;
}

/** Two decimal places, without a trailing `.00` on whole numbers. */
function round2(value: number): string {
  return String(Math.round(value * 100) / 100);
}

/**
 * WCAG relative luminance.
 *
 * <p>The sRGB channel is linearised first, because a gamma-encoded value is not proportional to
 * perceived brightness — which is exactly why guessing with an average of the RGB numbers picks
 * white on mid-green and black on dark blue, both wrong.
 */
export function relativeLuminance(hex: string): number {
  const normalised = normaliseHex(hex);
  if (!normalised) return 0;

  const channel = (raw: number): number => {
    const c = raw / 255;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  };

  const r = channel(parseInt(normalised.slice(1, 3), 16));
  const g = channel(parseInt(normalised.slice(3, 5), 16));
  const b = channel(parseInt(normalised.slice(5, 7), 16));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/**
 * The contrast ratio between two colours, from 1:1 to 21:1.
 *
 * <p>Exported because the appearance editor shows the owner their own number rather than
 * describing the problem: "3.1:1 — below the 4.5:1 minimum" is actionable, "your theme may be hard
 * to read" is not.
 */
export function contrastRatio(foreground: string, background: string): number | null {
  const a = normaliseHex(foreground);
  const b = normaliseHex(background);
  if (!a || !b) return null;

  const la = relativeLuminance(a);
  const lb = relativeLuminance(b);
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

/**
 * Picks black or white text for an arbitrary background.
 *
 * <p>0.179 is where black and white achieve equal contrast, so it is the point that minimises the
 * worst case either way.
 */
export function readableForeground(background: string): '#000000' | '#ffffff' {
  const normalised = normaliseHex(background);
  if (!normalised) return '#ffffff';
  return relativeLuminance(normalised) > 0.179 ? '#000000' : '#ffffff';
}

/**
 * Applies a theme's tokens as CSS custom properties.
 *
 * Tokens are written onto a scoped element rather than `document.documentElement`, so the
 * storefront's chosen palette cannot leak into the admin console in the same tab, and two
 * storefronts previewed side by side do not fight over the same global variable.
 *
 * Colours are converted from hex to an HSL triplet on the way out, because the stylesheet wraps
 * each variable in `hsl()`. See {@link hexToHslTriplet} for why that conversion belongs here rather
 * than in the stored document.
 *
 * <p>A `-foreground` companion is derived for every colour that has one, unless the theme set it
 * explicitly — so an owner's explicit choice still wins, and an unset one is still readable.
 *
 * <p>D15: `customCss` is stripped here. It stays in the schema so a saved theme keeps the data,
 * but nothing renders it until the sanitizer exists — a raw `<style>` from a shop owner is an
 * XSS and layout-breaker waiting to happen.
 */
export function tokensToCssVariables(tokens: ThemeTokens): Record<string, string> {
  const safe = stripUnsafeTokens({ tokens } as ThemeConfig).tokens;
  const vars: Record<string, string> = {};

  for (const [key, value] of Object.entries(safe)) {
    if (value == null) continue;

    if (FONT_TOKENS.has(key)) {
      const stack = fontStack(String(value));
      if (stack) vars[camelToCssVar(key)] = stack;
      continue;
    }

    const triplet = COLOUR_TOKENS.has(key) ? hexToHslTriplet(String(value)) : null;
    // A colour token that failed to parse is skipped rather than emitted raw: the browser would
    // discard the declaration anyway, and emitting it would also hand the derived-foreground pass
    // below a value it cannot reason about.
    if (COLOUR_TOKENS.has(key) && triplet === null) continue;

    vars[camelToCssVar(key)] = triplet ?? String(value);
  }

  // Derived foregrounds, for the pairs the theme did not pin itself.
  for (const [colourToken, foregroundToken] of Object.entries(FOREGROUND_FOR)) {
    const foregroundVar = camelToCssVar(foregroundToken);
    if (vars[foregroundVar] !== undefined) continue;

    const background = tokens[colourToken as keyof ThemeTokens];
    if (typeof background !== 'string' || hexToHslTriplet(background) === null) continue;

    // The derived foreground is black or white, so its HSL is exact rather than converted.
    vars[foregroundVar] = readableForeground(background) === '#ffffff' ? '0 0% 100%' : '0 0% 0%';
  }

  return vars;
}

/** `primaryForeground` -> `--primary-foreground`, matching the names in `styles.css`. */
function camelToCssVar(key: string): string {
  return `--${key.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}`;
}

/** The inline `style` prop for a theme scope element. */
export function themeScopeStyle(tokens: ThemeTokens): CSSProperties {
  return tokensToCssVariables(tokens) as CSSProperties;
}
