/**
 * Shared layout for every storefront (D1, D8).
 *
 * <p>Theme tokens are applied by the page rather than here, and this file deliberately exports
 * nothing but the layout: Next.js reserves every other export name in a layout module, so a
 * helper alongside it is a build error rather than a lint warning.
 *
 * <p>They are applied on a scoped element rather than `documentElement` for two reasons, both
 * about containment: a shop's palette must not leak into the gallery or into the admin console
 * when both are open in one browser, and two storefronts previewed side by side must not fight
 * over one global variable.
 */
export default function StorefrontLayout({ children }: { children: React.ReactNode }) {
  return <div className="min-h-dvh bg-background text-foreground">{children}</div>;
}
