/**
 * PostCSS wiring for Tailwind v4.
 *
 * <p>Next.js does **not** use the `@tailwindcss/vite` plugin the console uses; it goes through
 * PostCSS. Without this file, `@import 'tailwindcss'` in `globals.css` is left unprocessed, the
 * output is empty, and the storefront renders completely unstyled with no error anywhere — the
 * markup is all there and every class name simply does nothing.
 *
 * <p>Declared in this app's package.json rather than the workspace root so the two frontends keep
 * independent build pipelines, which is what lets the console and the storefront ship on
 * different schedules.
 */
const config = {
  plugins: {
    '@tailwindcss/postcss': {},
  },
};

export default config;
