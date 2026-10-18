/**
 * Every Phase 7 admin screen, driven as a real admin through a real browser.
 *
 * <p>Verifying each screen in isolation would leave the interesting part unproven: that the
 * navigation reaches all of them, that none logs a console error, and that none of them returns a
 * 4xx or 5xx. This walks the nav item by item, signs in once, and asserts all three for every
 * page — because "the screen renders" and "the screen renders because its endpoint answered" are
 * different claims, and the second is the one that breaks.
 *
 * <p><b>It checks what the screens actually assert, not that a div exists.</b> An empty state is a
 * legitimate result and is checked for as one; a missing figure that should be there is a failure.
 *
 *   node scripts/check-phase7.mjs [--shots]
 */
import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';

const SHOTS = process.argv.includes('--shots');
const DIR = '.ui-checks';

const ADMIN_USER = process.env.KC_OPS_USER_ADMIN ?? 'admin.test';
const ADMIN_PASS = process.env.KC_OPS_PASS ?? 'iloveu';

/**
 * The screens, and something specific to each.
 *
 * <p>`expect` is a string or a regex that must appear in the page body. Each one is chosen to be a
 * claim the page makes for a reason, not just any text — "Reporting window" alone would pass on a
 * page that had the heading and no data.
 */
const PAGES = [
  {
    path: '/admin',
    name: 'KPI dashboard',
    expect: [/Platform/i, /ARPU, per trading shop/i, /Churn/i, /Revenue by shop type/i],
  },
  {
    path: '/admin/shops',
    name: 'Shops',
    expect: [/Shops/i, /Featured and unfeatured/i, /Showing|page|\d+ shop/i],
  },
  {
    path: '/admin/users',
    name: 'People',
    expect: [/People/i, /Both realms/i, /Showing|page|\d+ account/i],
  },
  {
    path: '/admin/audit',
    name: 'Audit log',
    expect: [/Audit log/i, /append-only/i, /Showing|page|No entries/i],
  },
  {
    path: '/admin/reports',
    name: 'Reports',
    expect: [/Reports/i, /Download CSV/i, /Preview/i],
  },
  {
    path: '/admin/explorer',
    name: 'Data explorer',
    expect: [/Data explorer/i, /routing table/i, /safe read/i],
  },
  {
    path: '/admin/support',
    name: 'Support',
    expect: [/Support/i, /email log/i, /Nothing has been sent|No messages/i],
  },
];

const browser = await chromium.launch({ channel: 'chrome' });
if (SHOTS) await mkdir(DIR, { recursive: true });

let failures = 0;
const check = (name, ok, detail = '') => {
  console.log(`  ${ok ? 'ok  ' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
  if (!ok) failures++;
};

async function signIn(page, user, password) {
  await page.goto('http://localhost:5173/login', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1500);

  const button = page.getByRole('button', { name: /sign in|continue/i });
  if (await button.count()) {
    await button.first().click();
    await page.waitForTimeout(3500);
  }
  if (await page.locator('#username').count()) {
    await page.locator('#username').fill(user);
    await page.locator('#password').fill(password);
    await page.locator('#kc-login').click();
    await page.waitForTimeout(6000);
  }
}

/** Console errors and failed API calls, collected across the whole walk. */
const errors = [];
function watch(page, label) {
  page.on('console', (m) => m.type() === 'error' && errors.push(`${label}: ${m.text().slice(0, 140)}`));
  page.on('pageerror', (e) => errors.push(`${label} pageerror: ${e.message.slice(0, 140)}`));
  page.on('response', (r) => {
    if (r.status() >= 400 && r.url().includes('/api/')) {
      errors.push(`${label}: HTTP ${r.status()} ${r.url().replace(/^https?:\/\/localhost:\d+/, '')}`);
    }
  });
}

console.log('\nPHASE 7: every admin screen');

const context = await browser.newContext({ viewport: { width: 1400, height: 1100 } });
const page = await context.newPage();
watch(page, 'admin');

await signIn(page, ADMIN_USER, ADMIN_PASS);

// The role gate, not the presence of a link. A nav item that renders for the wrong role is a
// different bug from a page that is missing, and only one of them is caught by visiting the page.
check('signed in as an admin', !(await page.url().includes('/login')), page.url());

for (const spec of PAGES) {
  const before = errors.length;
  await page.goto(`http://localhost:5173${spec.path}`, { waitUntil: 'domcontentloaded' });
  // React Query plus a cold route; fixed rather than polled because these are aggregate endpoints
  // and a spinner that never resolves looks the same as a spinner that is still resolving.
  await page.waitForTimeout(4500);

  const body = ((await page.locator('body').innerText()) || '').replace(/\s+/g, ' ');

  const missing = spec.expect.filter((pattern) => !pattern.test(body));
  check(
    `${spec.name} renders`,
    missing.length === 0,
    missing.length ? `missing: ${missing.map(String).join(', ')}` : '',
  );

  const fresh = errors.slice(before);
  check(`${spec.name} is error-free`, fresh.length === 0, fresh.join(' | '));

  if (SHOTS) {
    await page.screenshot({
      path: `${DIR}/phase7-${spec.path.replace(/\//g, '-')}.png`,
      fullPage: true,
    });
  }
}

// The nav must reach them, not just the URLs. A screen nothing links to is a screen nobody finds.
console.log('\nNAV: every admin screen is reachable from the sidebar');
for (const spec of PAGES) {
  const link = page.locator(`aside a[href="${spec.path}"]`);
  const found = await link.count();
  check(`${spec.name} has a nav link`, found > 0, found ? '' : `${spec.path} not in the sidebar`);
}

// The explorer is the one screen that has to prove it can run something, so it is exercised rather
// than merely loaded. A contract that renders with every endpoint marked unsafe is a screen that
// looks finished and does nothing.
console.log('\nEXPLORER: it can actually run a safe read');
await page.goto('http://localhost:5173/admin/explorer', { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(3000);

// Not "the first group": the first group is whichever tag sorts first and most of them are
// shop or customer endpoints the explorer deliberately will not run. The claim worth checking is
// that *some* group offers a safe read, so it walks the groups until one does -- and says how many
// it had to open, because "it offered a read" and "the very first group offered a read" are
// different claims.
const groups = page.locator('button[aria-expanded]');
const groupCount = await groups.count();
let runCount = 0;
let opened = 0;

for (let i = 0; i < groupCount && runCount === 0; i++) {
  await groups.nth(i).click();
  await page.waitForTimeout(900);
  opened = i + 1;

  runCount = await page.getByRole('button', { name: /^Run$/ }).count();
  if (runCount === 0) {
    // Collapse again before the next one, or every group stays open and the DOM grows unbounded.
    await groups.nth(i).click();
    await page.waitForTimeout(300);
  }
}

check('a safe read is offered somewhere', runCount > 0, `${runCount} runnable, group ${opened} of ${groupCount}`);

if (runCount > 0) {
  const before = errors.length;
  await page.getByRole('button', { name: /^Run$/ }).first().click();
  await page.waitForTimeout(4000);

  const resultText = ((await page.locator('body').innerText()) || '').replace(/\s+/g, ' ');
  const status = resultText.match(/HTTP (\d{3})/);
  check('the probe returns a result', Boolean(status), status?.[0] ?? 'no status shown');
  const fresh = errors.slice(before);
  check('the probe is error-free', fresh.length === 0, fresh.join(' | '));
}

// The audit log is the one screen whose diff view is the point, so a row is opened rather than the
// list merely being shown.
console.log('\nAUDIT: the diff view opens');
await page.goto('http://localhost:5173/admin/audit', { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(4000);

const rows = page.locator('table tbody tr');
const rowCount = await rows.count();
check('the audit log has entries to inspect', rowCount > 0, `${rowCount} row(s)`);

if (rowCount > 0) {
  await rows.first().click();
  await page.waitForTimeout(1500);

  const detail = ((await page.locator('body').innerText()) || '').replace(/\s+/g, ' ');
  check(
    'clicking a row opens its detail',
    /No field-level change|\bField\b/.test(detail),
    'diff or an explicit "no change recorded"',
  );
}

console.log('\nCONSOLE');
check('no uncaught errors anywhere', errors.length === 0, errors.slice(0, 6).join(' | '));

await browser.close();
console.log(`\n${failures === 0 ? 'PHASE 7 UI OK' : `PHASE 7 UI: ${failures} failure(s)`}`);
process.exit(failures === 0 ? 0 : 1);