/**
 * The theme renderer (D8).
 *
 * A pure server component: it takes a theme document and the storefront data, resolves every
 * section, and streams the markup. No fetching, no hooks, no effects — which is what makes the
 * page indexable and share-previewable (D1), and what makes the editor preview identical to the
 * live storefront because both call this same function with the same input.
 *
 * <p><b>There is deliberately no {@code onIssues} callback.</b> It existed, and calling it from the
 * render body set state in the <em>parent</em> mid-render, which React rejects — and because every
 * parse produced a fresh {@code issues} array, re-rendering the parent, which re-rendered this,
 * it was also an infinite loop: "Maximum update depth exceeded".
 *
 * <p>A component cannot both report and stay pure, so reporting belongs to the caller. The editor
 * parses the same document with the exported {@link parseTheme} and reads the issues itself; the
 * storefront renders them through {@code <ThemeIssues>}, which is already inline below. This went
 * unnoticed for a long time because the editor's API calls were unauthenticated and never
 * returned a theme to render at all — two independent faults, one hiding the other.
 */

import type { CSSProperties, ReactNode } from 'react';

import { SECTION_COMPONENTS } from './sections';
import type { SectionProps } from './sections';
import { themeScopeStyle } from './tokens';
import type { ThemeConfig } from './schema';
import { parseTheme } from './schema';
import { resolveSections } from './resolve';
import type { ResolvedSection, StorefrontData } from './resolve';

export interface ThemeRendererProps {
  /** The raw theme document, exactly as stored. Parsed here so a bad one degrades rather than throws. */
  config: string | ThemeConfig | unknown;
  data: StorefrontData;
  /** Wrapper class, so the editor can add its own chrome around the same content. */
  className?: string;
  /**
   * Render a visible notice when the theme does not validate.
   *
   * <p>On by default, and it should stay that way. A theme that fails to parse used to produce an
   * <em>empty page</em> — no error, no partial render, no trace — because `parseTheme` degrades to
   * an empty theme by design. That is the right behaviour for a parser and the wrong behaviour for
   * a renderer, and the two have to be separated here.
   *
   * <p>Set it to false only where a blank area is genuinely the intent, which in practice means
   * nowhere.
   */
  showIssues?: boolean;
}

export function ThemeRenderer({
  config,
  data,
  className,
  showIssues = true,
}: ThemeRendererProps) {
  const { theme, issues } = parseTheme(
    typeof config === 'string' ? safeParse(config) : config,
  );

  const resolved = resolveSections(theme.sections, data);

  // A theme that does not validate still renders its valid parts, and says so. Swallowing it is
  // how a storefront ends up silently blank.
  const invalid = issues.length > 0;

  return (
    <div
      className={className}
      // Tokens are written to a scoped element, not :root. A storefront's palette must not leak
      // into the admin console in the same tab, and two storefronts previewed side by side must not
      // fight over one global variable.
      style={themeScopeStyle(theme.tokens) as CSSProperties}
    >
      {invalid ? <ThemeIssues issues={issues} visible={showIssues} shop={data.shopName} /> : null}
      {resolved.map((section) => (
        <Section key={section.section.id} resolved={section} />
      ))}
      {invalid && resolved.length === 0 ? (
        // Nothing parsed, so nothing to show. A shop with an entirely invalid theme still gets a
        // storefront rather than a white page.
        <section className="py-16 text-center">
          <h1 className="text-2xl font-bold">{data.shopName}</h1>
          <p className="mt-2 text-sm text-muted-foreground">
            This storefront has not been set up yet.
          </p>
        </section>
      ) : null}
    </div>
  );
}

/**
 * Reports a theme that failed validation.
 *
 * <p>Logged on the server whatever the caller asks for: a storefront rendering a degraded theme
 * with nobody watching the logs is exactly the situation where the log line matters most.
 */
function ThemeIssues({
  issues,
  visible,
  shop,
}: {
  issues: string[];
  visible: boolean;
  shop: string;
}) {
  if (typeof console !== 'undefined') {
    console.error(`[theme] ${shop}: the theme document did not validate`, issues);
  }
  if (!visible) {
    return null;
  }
  return (
    <div
      // eslint-disable-next-line jsx-a11y/role-supports-aria-props
      role="status"
      data-theme-issues={issues.length}
      className="rounded-lg border border-amber-500/40 bg-amber-500/10 p-4 text-sm"
    >
      <p className="font-medium">This storefront could not be laid out as configured.</p>
      <ul className="mt-1 list-disc pl-5 text-muted-foreground">
        {issues.slice(0, 5).map((issue) => (
          <li key={issue}>{issue}</li>
        ))}
      </ul>
      {issues.length > 5 ? (
        <p className="mt-1 text-muted-foreground">and {issues.length - 5} more.</p>
      ) : null}
    </div>
  );
}

/**
 * Renders one section.
 *
 * The lookup is total — the map is a `Record` over the closed union, so TypeScript guarantees a
 * component exists — with a runtime guard anyway. A theme persisted by a build that knew a type
 * this one does not must degrade to a visible placeholder, which is the entire point of
 * `unknown-section`.
 */
function Section({ resolved }: SectionProps): ReactNode {
  const Component = SECTION_COMPONENTS[resolved.section.type] as
    | ((props: SectionProps) => ReactNode)
    | undefined;

  if (!Component) {
    return SECTION_COMPONENTS['unknown-section']({ resolved });
  }
  return Component({ resolved });
}

/**
 * Parses a JSON string without throwing.
 *
 * `parseTheme` already degrades on a schema mismatch, but it would throw on malformed JSON, and a
 * storefront page is the wrong place for that.
 */
function safeParse(raw: string): unknown {
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

export type { StorefrontData, ResolvedSection };
