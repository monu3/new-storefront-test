/**
 * Phase 6's console screens, signed in, against the real API.
 *
 * <p>`check-ui.mjs` only reaches these routes unauthenticated, which proves they guard rather than
 * white-page. That is not the same as proving they render: a page can guard perfectly and then fail
 * on the first real response. So this signs in as a shop owner and as an admin, and reports what
 * each screen actually painted, plus every console error and every 4xx/5xx along the way.
 *
 *   node scripts/check-phase6.mjs [--shots]
 */
import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';

const SHOTS = process.argv.includes('--shots');
const DIR = '.ui-checks';

const SHOP_USER = process.env.KC_OPS_USER ?? 'ops.test';
const SHOP_PASS = process.env.KC_OPS_PASS ?? 'iloveu';
const ADMIN_USER = 'admin.test';
const ADMIN_PASS = process.env.KC_OPS_PASS ?? 'iloveu';

const browser = await chromium.launch({ channel: 'chrome' });
if (SHOTS) await mkdir(DIR, { recursive: true });

let failures = 0;

async function signIn(page, user, password) {
  await page.goto('http://localhost:5173/login', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1500);

  const signIn = page.getByRole('button', { name: /sign in|continue/i });
  if (await signIn.count()) {
    await signIn.first().click();
    await page.waitForTimeout(3500);
  }
  if (await page.locator('#username').count()) {
    await page.locator('#username').fill(user);
    await page.locator('#password').fill(password);
    await page.locator('#kc-login').click();
    await page.waitForTimeout(6000);
  }
  return page.url();
}

async function visit(page, path, label, probes) {
  const errors = [];
  const onConsole = (m) => m.type() === 'error' && errors.push(`console: ${m.text().slice(0, 140)}`);
  const onError = (e) => errors.push(`pageerror: ${e.message.slice(0, 140)}`);
  const onResponse = (r) => {
    if (r.status() >= 400 && r.url().includes('/api/')) {
      errors.push(`HTTP ${r.status()} ${r.url().replace('http://localhost:8080', '').replace('http://localhost:5173', '')}`);
    }
  };

  page.on('console', onConsole);
  page.on('pageerror', onError);
  page.on('response', onResponse);

  await page.goto(`http://localhost:5173${path}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(4000);

  console.log(`\n  ${label}`);
  console.log('    url      :', page.url().replace('http://localhost:5173', ''));

  for (const [name, probe] of Object.entries(probes)) {
    const value = await probe(page).catch((e) => `threw: ${e.message.slice(0, 90)}`);
    console.log(`    ${name.padEnd(9)}:`, typeof value === 'string' ? value : JSON.stringify(value));
  }

  console.log('    errors   :', errors.length ? errors.slice(0, 4) : 'none');

  if (SHOTS) {
    await page.screenshot({ path: `${DIR}/p6-${label.toLowerCase().replace(/[^a-z0-9]+/g, '-')}.png`, fullPage: true });
  }

  page.off('console', onConsole);
  page.off('pageerror', onError);
  page.off('response', onResponse);

  if (errors.length) {
    failures++;
    console.log('    VERDICT  : FAIL');
  } else {
    console.log('    VERDICT  : PASS');
  }
}

const heading = (p) => p.locator('h1').first().textContent().catch(() => '(none)');
const body = (p) => p.locator('main').innerText().then((t) => t.slice(0, 160).replace(/\n+/g, ' | '));

// ---------------------------------------------------------------- shop owner

console.log('\nSHOP OWNER');
const shop = await browser.newContext({ viewport: { width: 1400, height: 1000 } });
const shopPage = await shop.newPage();
console.log('  signed in at:', (await signIn(shopPage, SHOP_USER, SHOP_PASS)).slice(0, 70));

await visit(shopPage, '/shop/billing', 'shop billing', {
  heading,
  commission: (p) =>
    p.getByText(/5% commission|No commission/).first().textContent().catch(() => '(no badge)'),
  plans: (p) => p.locator('#plans button').allTextContents(),
  features: (p) =>
    p.locator('ul li p.font-medium').first().textContent().catch(() => '(none)'),
  body,
});

await visit(shopPage, '/shop/products/new', 'product form', {
  heading,
  // The upgrade prompt only appears after a refusal, so this just proves the form is reachable and
  // the new prompt component did not break rendering.
  body,
});

// ---------------------------------------------------------------- admin

console.log('\nADMIN');
const admin = await browser.newContext({ viewport: { width: 1400, height: 1000 } });
const adminPage = await admin.newPage();
console.log('  signed in at:', (await signIn(adminPage, ADMIN_USER, ADMIN_PASS)).slice(0, 70));

await visit(adminPage, '/admin/revenue', 'admin revenue', {
  heading,
  mrr: (p) => p.getByText('MRR', { exact: true }).locator('..').innerText().catch(() => '(none)'),
  stats: (p) => p.locator('.tabular-nums').allTextContents().then((t) => t.slice(0, 8)),
  trendBars: (p) => p.locator('[role="img"] > div').count(),
  window: (p) => p.locator('header p').first().textContent().catch(() => '(none)'),
});

await visit(adminPage, '/admin/plans', 'admin plans', {
  heading,
  rows: (p) => p.locator('tbody tr').count(),
  planNames: (p) => p.locator('tbody tr td:first-child p').allTextContents(),
  newPlanButton: (p) => p.getByRole('button', { name: /new plan/i }).count(),
});

await visit(adminPage, '/admin', 'admin home', {
  heading,
  body,
});

await browser.close();

console.log(`\n${failures === 0 ? 'ALL PAGES OK' : `${failures} page(s) failing`}`);
process.exit(failures === 0 ? 0 : 1);
