/**
 * Load the real pages in a real browser and report what a user would actually see.
 *
 * Everything before this was HTTP fetches of HTML, CSS and served modules. Those proved the server
 * responded and the right markup existed; they could not see a blank page, an unstyled page, a
 * console error, or a failed runtime chunk. Four console bugs and two styling bugs got through that
 * way, so this exists to close the gap.
 *
 * Uses the Chrome already installed on this machine (`channel: 'chrome'`) rather than downloading
 * a browser binary.
 *
 *   node scripts/check-ui.mjs                  # both apps
 *   node scripts/check-ui.mjs --shots          # also write screenshots to .ui-checks/
 */
import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';

const SHOTS = process.argv.includes('--shots');
const DIR = '.ui-checks';

const targets = [
  {
    name: 'storefront: shop home',
    url: 'http://localhost:3000/s/my-shop',
    // A storefront is a page a customer reads. If the theme token is not on the element, the
    // palette silently fell back to the platform default.
    expect: [
      ['h1 present', (p) => p.locator('h1').count().then((n) => n > 0)],
      ['theme token applied', (p) => p.locator('[style*="--primary"]').count().then((n) => n > 0)],
      ['stylesheet actually applied', (p) =>
        p.evaluate(() => {
          const el = document.querySelector('main, body');
          if (!el) return false;
          const s = getComputedStyle(el);
          // A background that resolves to transparent on every element means no stylesheet.
          return s.getPropertyValue('font-family') !== '' && getComputedStyle(document.body).margin !== '16px';
        })],
      ['product content rendered', (p) => p.locator('a[href*="/c/"]').count().then((n) => n > 0)],

      // The regression this guards: theme tokens are stored as hex (#7c3aed) but the stylesheet
      // wraps every colour variable in hsl(var(--primary)), so a hex written straight onto the
      // element computed to hsl(#7c3aed) — not a colour. The browser dropped the declaration and
      // every storefront rendered monochrome, with white CTA text on a transparent background.
      //
      // Nothing errored, the 5,810 backend tests passed, and every earlier appearance check
      // asserted on HTML/CSS *text* rather than on a computed style. So this asserts on what the
      // browser actually painted, which is the whole point.
      ['theme colour actually paints', (p) =>
        p.evaluate(() => {
          const scope = document.querySelector('[style*="--primary"]');
          if (!scope) return false;

          // A token still written as hex fails here: hsl(#7c3aed) is not a colour.
          const primary = getComputedStyle(scope).getPropertyValue('--primary').trim();
          if (primary.startsWith('#')) return false;

          // And the visible proof: a bg-primary element must not be transparent. That was the
          // exact failure — an invisible call-to-action.
          const el = document.querySelector('.bg-primary');
          if (!el) return true;
          return getComputedStyle(el).backgroundColor !== 'rgba(0, 0, 0, 0)';
        })],

      // A -foreground companion is derived from the colour's luminance when the theme does not set
      // one, so a pale brand colour cannot produce white-on-white button text.
      ['cta text is readable against its background', (p) =>
        p.evaluate(() => {
          const el = document.querySelector('.bg-primary');
          if (!el) return true;
          const cs = getComputedStyle(el);
          const lum = (c) => {
            const [r, g, b] = c
              .match(/\d+/g)
              .map(Number)
              .map((v) => {
                const x = v / 255;
                return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4);
              });
            return 0.2126 * r + 0.7152 * g + 0.0722 * b;
          };
          const a = lum(cs.color);
          const b = lum(cs.backgroundColor);
          return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05) >= 4.5;
        })],
    ],
  },
  {
    // The gallery moved to /shops in Phase 8.5; `/` is now the landing page. Leaving this pointed
    // at `/` reported a working site as broken, which is the kind of false alarm that trains you
    // to stop reading the output.
    name: 'storefront: gallery',
    url: 'http://localhost:3000/shops',
    expect: [
      ['shop links rendered', (p) => p.locator('a[href^="/s/"]').count().then((n) => n > 0)],
      ['stylesheet actually applied', (p) =>
        p.evaluate(() => getComputedStyle(document.body).margin !== '16px')],
    ],
  },
  {
    name: 'console: login screen',
    url: 'http://localhost:5173/login',
    expect: [
      ['renders a sign-in control', (p) =>
        p.locator('button, a[href], input').count().then((n) => n > 0)],
      ['no white page', (p) =>
        p.evaluate(() => document.getElementById('root')?.children.length > 0)],
      ['stylesheet actually applied', (p) =>
        p.evaluate(() => getComputedStyle(document.body).margin !== '16px')],
    ],
  },
  {
    name: 'console: silent SSO callback page',
    url: 'http://localhost:5173/silent-check-sso.html',
    // Without this page `kc.init({ onLoad: 'check-sso' })` never settles and the console is
    // permanently white. It is easy to delete as "unused" and there is no error until someone
    // tries to sign in.
    expect: [
      ['is served, not 404', (p) => p.evaluate(() => document.title !== '')],
      ['posts its URL to the parent', (p) => p.content().then((c) => c.includes('postMessage'))],
    ],
  },
  {
    name: 'console: guarded route survives a reload',
    url: 'http://localhost:5173/shop/orders',
    // The regression this guards: RequireRole read getSession() synchronously, so on a full reload
    // the owner was bounced to /login while soft navigation worked. Fails closed now, which is
    // also why an unauthenticated visitor lands on /login and not on a blank page.
    expect: [
      ['does not white-page', (p) =>
        p.evaluate(() => document.getElementById('root')?.children.length > 0)],
      ['lands somewhere real', (p) =>
        p.evaluate(() => location.pathname === '/login' || location.pathname.startsWith('/shop'))],
      ['stylesheet actually applied', (p) =>
        p.evaluate(() => getComputedStyle(document.body).margin !== '16px')],
    ],
  },
  {
    // Phase 6. These are guarded routes, so unauthenticated they must land on /login rather than
    // white-page -- the same property the orders route is checked for. The authenticated rendering
    // of all three is covered by scripts/check-phase6.mjs, which signs in.
    name: 'console: subscription route guards',
    url: 'http://localhost:5173/shop/billing',
    expect: [
      ['does not white-page', (p) =>
        p.evaluate(() => document.getElementById('root')?.children.length > 0)],
      ['lands somewhere real', (p) =>
        p.evaluate(() => location.pathname === '/login' || location.pathname.startsWith('/shop'))],
    ],
  },
  {
    name: 'console: admin revenue route guards',
    url: 'http://localhost:5173/admin/revenue',
    expect: [
      ['does not white-page', (p) =>
        p.evaluate(() => document.getElementById('root')?.children.length > 0)],
      ['lands somewhere real', (p) =>
        p.evaluate(() => location.pathname === '/login' || location.pathname.startsWith('/admin'))],
    ],
  },
  {
    name: 'console: admin plans route guards',
    url: 'http://localhost:5173/admin/plans',
    expect: [
      ['does not white-page', (p) =>
        p.evaluate(() => document.getElementById('root')?.children.length > 0)],
      ['lands somewhere real', (p) =>
        p.evaluate(() => location.pathname === '/login' || location.pathname.startsWith('/admin'))],
    ],
  },
];

const browser = await chromium.launch({ channel: 'chrome' });
if (SHOTS) await mkdir(DIR, { recursive: true });

let failures = 0;

for (const t of targets) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();

  // A console error or a failed chunk load is a bug even when the assertions pass — they are how a
  // page ends up blank or half-rendered.
  const consoleErrors = [];
  const pageErrors = [];
  const failedRequests = [];
  page.on('console', (m) => {
    // A missing favicon is a 404, not a defect: it is a browser-initiated request that no page
    // depends on, and failing on it would train the check to be ignored.
    if (m.type() !== 'error') return;
    if (m.location()?.url?.includes('favicon.ico')) return;
    consoleErrors.push(m.text());
  });
  page.on('pageerror', (e) => pageErrors.push(e.message));
  page.on('requestfailed', (r) => failedRequests.push(`${r.url()} ${r.failure()?.errorText}`));

  console.log(`\n${t.name}`);
  console.log(`  ${t.url}`);

  let ok = true;
  try {
    const response = await page.goto(t.url, { waitUntil: 'networkidle', timeout: 30_000 });
    const status = response?.status();
    console.log(`  HTTP ${status}`);
    if (!status || status >= 400) ok = false;

    for (const [label, fn] of t.expect) {
      try {
        const passed = await fn(page);
        console.log(`  ${passed ? 'ok     ' : 'FAIL   '} ${label}`);
        if (!passed) ok = false;
      } catch (e) {
        console.log(`  ERROR   ${label}: ${e.message.split('\n')[0]}`);
        ok = false;
      }
    }

    if (consoleErrors.length) {
      console.log(`  console errors (${consoleErrors.length}):`);
      consoleErrors.slice(0, 3).forEach((e) => console.log(`    - ${e.slice(0, 140)}`));
      ok = false;
    }
    if (pageErrors.length) {
      console.log(`  page errors (${pageErrors.length}):`);
      pageErrors.slice(0, 3).forEach((e) => console.log(`    - ${e.slice(0, 140)}`));
      ok = false;
    }
    if (failedRequests.length) {
      console.log(`  failed requests (${failedRequests.length}):`);
      failedRequests.slice(0, 3).forEach((r) => console.log(`    - ${r.slice(0, 140)}`));
      ok = false;
    }

    if (SHOTS) {
      const file = `${DIR}/${t.name.replace(/[^a-z0-9]+/gi, '-')}.png`;
      await page.screenshot({ path: file, fullPage: false });
      console.log(`  screenshot: ${file}`);
    }
  } catch (e) {
    console.log(`  FAIL    navigation: ${e.message.split('\n')[0]}`);
    ok = false;
  }

  console.log(ok ? '  PASS' : '  FAIL');
  if (!ok) failures++;
  await context.close();
}

await browser.close();
console.log(`\n${failures === 0 ? 'ALL PAGES OK' : `${failures} page(s) failing`}`);
process.exit(failures === 0 ? 0 : 1);
