import type { CSSProperties } from 'react';

import type { ThemeConfig, ThemeTokens } from './schema';
import { stripUnsafeTokens } from './schema';

/**
 * Applies a theme's tokens as CSS custom properties.
 *
 * Tokens are written onto a scoped element rather than `document.documentElement`, so the
 * storefront's chosen palette cannot leak into the admin console in the same tab, and two
 * storefronts previewed side by side do not fight over the same global variable.
 *
 * D15: `customCss` is stripped here. It stays in the schema so a saved theme keeps the data,
 * but nothing renders it until the sanitizer exists — a raw `<style>` from a shop owner is an
 * XSS and layout-breaker waiting to happen.
 */
export function tokensToCssVariables(tokens: ThemeTokens): Record<string, string> {
  const safe = stripUnsafeTokens({ tokens } as ThemeConfig).tokens;
  const vars: Record<string, string> = {};

  for (const [key, value] of Object.entries(safe)) {
    if (value == null) continue;
    const cssName = `--${key.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}`;
    vars[cssName] = String(value);
  }
  return vars;
}

/** The inline `style` prop for a theme scope element. */
export function themeScopeStyle(tokens: ThemeTokens): CSSProperties {
  return tokensToCssVariables(tokens) as CSSProperties;
}
