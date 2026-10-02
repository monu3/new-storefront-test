/**
 * Shared layout for every storefront (D1).
 *
 * Theme tokens are written to CSS variables on this wrapper rather than on `documentElement`,
 * so a shop's palette applies to their storefront without leaking into the gallery or, when
 * both are open in one browser, fighting with it.
 */
export default function StorefrontLayout({ children }: { children: React.ReactNode }) {
  return <div className="min-h-dvh bg-background text-foreground">{children}</div>;
}
