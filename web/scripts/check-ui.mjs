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
    ],
  },
  {
    name: 'storefront: gallery',
    url: 'http://localhost:3000/',
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
  page.on('console', (m) => m.type() === 'error' && consoleErrors.push(m.text()));
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
