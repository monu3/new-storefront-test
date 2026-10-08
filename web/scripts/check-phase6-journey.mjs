/**
 * The whole Phase 6 buying journey, driven through the UI as a shop owner and an admin.
 *
 * <p>Verifying the screens in isolation left the interesting part unproven: a dashboard showing
 * "Rs. 0.00" because nothing has ever been bought tells me the empty state renders, and nothing
 * about whether a purchase moves the numbers.
 *
 * <p>So this requests a plan, submits payment, approves it as an admin, and then re-reads the
 * revenue dashboard — asserting that MRR became non-zero and that the trend chart grew bars. If any
 * step of the hand-off between the shop and the admin is broken, this is what notices.
 *
 *   node scripts/check-phase6-journey.mjs [--shots]
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

const errors = [];
function watch(page, label) {
  page.on('console', (m) => m.type() === 'error' && errors.push(`${label}: ${m.text().slice(0, 120)}`));
  page.on('pageerror', (e) => errors.push(`${label} pageerror: ${e.message.slice(0, 120)}`));
  page.on('response', (r) => {
    if (r.status() >= 400 && r.url().includes('/api/')) {
      errors.push(`${label}: HTTP ${r.status()} ${r.url().replace(/^https?:\/\/localhost:\d+/, '')}`);
    }
  });
}

// ---------------------------------------------------------------- shop buys
console.log('\nSHOP: request a plan');

const shop = await browser.newContext({ viewport: { width: 1400, height: 1000 } });
const shopPage = await shop.newPage();
watch(shopPage, 'shop');
await signIn(shopPage, SHOP_USER, SHOP_PASS);

await shopPage.goto('http://localhost:5173/shop/billing', { waitUntil: 'domcontentloaded' });
await shopPage.waitForTimeout(4000);

const beforeCommission = await shopPage
  .getByText(/5% commission|No commission/)
  .first()
  .textContent();
// Precondition, stated once. This script exercises the *whole* purchase, so it needs a shop on the
// free tier. Running it against an already-subscribed shop produced a cascade of eleven failures that
// all read as bugs and none of which were -- so it says so and stops.
//
// Checked *before* any assertion, so the precondition is not itself reported as a failure.
if (!/5%/.test(beforeCommission ?? '')) {
  console.log('\n  This shop is not on the free tier, so there is no purchase to drive.');
  console.log('  Reset it first: clear the subscription rows in the dev database, or let the');
  console.log('  current one lapse. Re-running against a subscribed shop only proves the');
  console.log('  idempotency guards work.');
  await browser.close();
  process.exit(0);
}

check('starts on commission', true, beforeCommission ?? '');
const requestButtons = shopPage.getByRole('button', { name: /request this plan/i });
const planCount = await requestButtons.count();
check('paid plans are offered', planCount > 0, `${planCount} plan(s)`);

// Skip requesting when a payment step is already on screen. A queued request survives a reload, so
// re-running this script against a shop that has one would otherwise earn a 409 for the duplicate --
// correctly, but it is not what this check is about.
if (await shopPage.getByText(/^Send Rs\./).count()) {
  console.log('    (a request is already queued; continuing from it)');
} else {
  // Monthly, deliberately: the monthly plan's full price is what MRR counts, which makes the
  // dashboard assertion below exact rather than a twelfth of something.
  await requestButtons.first().click();
  await shopPage.waitForTimeout(3000);
}

const amountSentence = await shopPage.getByText(/^Send Rs\./).first().textContent().catch(() => null);
const reference = await shopPage.locator('p.font-mono.font-semibold').first().textContent().catch(() => null);
check('the amount is quoted', Boolean(amountSentence), amountSentence ?? 'nothing said');
check('a bank reference is generated', Boolean(reference), reference ?? '');

// Resumability: the payment step must survive a reload, or a shop that closes the tab mid-purchase
// can never finish. This is the assertion that would have caught the local-state bug.
await shopPage.reload({ waitUntil: 'domcontentloaded' });
await shopPage.waitForTimeout(4000);
const afterReload = await shopPage.getByText(/^Send Rs\./).count();
check('the payment step survives a reload', afterReload > 0, `${afterReload} payment block(s)`);

// The transfer itself cannot happen in a browser, so the reference is what an admin matches by.
await shopPage.getByLabel('Transfer reference').fill(reference ?? 'REF');
await shopPage.getByRole('button', { name: /i have paid/i }).click();
await shopPage.waitForTimeout(3500);

const stillCommission = await shopPage
  .getByText(/5% commission|No commission/)
  .first()
  .textContent();
check(
  'nothing changed before approval',
  /5%/.test(stillCommission ?? ''),
  'still ' + stillCommission,
);

if (SHOTS) await shopPage.screenshot({ path: `${DIR}/p6-journey-1-requested.png`, fullPage: true });

// ---------------------------------------------------------------- admin approves
console.log('\nADMIN: approve the payment');

const admin = await browser.newContext({ viewport: { width: 1400, height: 1000 } });
const adminPage = await admin.newPage();
watch(adminPage, 'admin');
await signIn(adminPage, ADMIN_USER, ADMIN_PASS);
check('an admin lands on /admin, not /forbidden', !adminPage.url().includes('/forbidden'), adminPage.url().replace('http://localhost:5173', ''));

await adminPage.goto('http://localhost:5173/admin/revenue', { waitUntil: 'domcontentloaded' });
await adminPage.waitForTimeout(4000);

/** The card's text for a stat, as `label`, the value, then the hint. */
const readStat = async (label) =>
  adminPage
    .getByText(label, { exact: true })
    .first()
    .locator('..')
    .innerText();

/** The value out of a stat card. Matches the money line rather than a line index. */
const statValue = async (label) => {
  const text = await readStat(label);
  return text.match(/Rs\.[\d,.]+/)?.[0] ?? text.split('\n').filter(Boolean)[1] ?? '';
};

const subscribersBefore = Number((await readStat('Paying subscribers')).match(/[\d,]+/)?.[0].replace(/,/g, '') ?? '0');
const mrrValueBefore = await statValue('MRR');
check('no subscribers before the approval', subscribersBefore === 0, `${subscribersBefore}`);

// Approve through the wallet queue, which is where the top-up lands.
await adminPage.goto('http://localhost:5173/admin/wallet', { waitUntil: 'domcontentloaded' });
await adminPage.waitForTimeout(3500);
// Anchored and exact, because the status filter tab is *also* called "Approved" -- a loose
// /approve/i match clicks the tab, changes no rows, and reports success while nothing happened.
const approved = await adminPage
  .getByRole('button', { name: /^approve$/i })
  .first()
  .click()
  .then(() => true)
  .catch(() => false);
check('the top-up is approveable from the queue', approved, approved ? 'approved' : 'no approve button found');

await adminPage.waitForTimeout(4000);

// ---------------------------------------------------------------- the numbers move
console.log('\nTHE NUMBERS MOVE');

await adminPage.goto('http://localhost:5173/admin/revenue', { waitUntil: 'domcontentloaded' });
await adminPage.waitForTimeout(4500);

const subscribersAfter = Number((await readStat('Paying subscribers')).match(/[\d,]+/)?.[0].replace(/,/g, '') ?? '0');
const mrrValueAfter = await statValue('MRR');
// The BARS, not the columns wrapping them -- counting `[role=img] > div` reported six
// while the page rendered none, because a percentage height against an indefinite parent
// collapses to zero and the columns were still there.
const bars = await adminPage.locator('[data-testid="mrr-bar"]').count();

check('a subscriber now exists', subscribersAfter === subscribersBefore + 1, `${subscribersBefore} → ${subscribersAfter}`);
check('MRR is no longer zero', mrrValueAfter !== mrrValueBefore, `${mrrValueBefore} → ${mrrValueAfter}`);
check('the trend chart drew bars', bars > 0, `${bars} bar(s)`);
const tallest = await adminPage.evaluate(() => {
  const rendered = [...document.querySelectorAll('[data-testid="mrr-bar"]')].map((el) =>
    el.getBoundingClientRect().height,
  );
  return { count: rendered.length, tallest: Math.round(Math.max(0, ...rendered)) };
});
check(
  'at least one bar is taller than a stub',
  tallest.tallest > 20,
  `tallest ${tallest.tallest}px of ${tallest.count}`,
);
const commissionFree = await statValue('Commission-free shops');
check('the commission-free count followed', commissionFree.includes('1'), commissionFree);

if (SHOTS) await adminPage.screenshot({ path: `${DIR}/p6-journey-2-revenue.png`, fullPage: true });

// ---------------------------------------------------------------- the shop sees it too
console.log('\nSHOP: sees the effect');

await shopPage.goto('http://localhost:5173/shop/billing', { waitUntil: 'domcontentloaded' });
await shopPage.waitForTimeout(4500);

const commissionNow = await shopPage.getByText(/5% commission|No commission/).first().textContent();
check('the shop is now commission-free', /No commission/i.test(commissionNow ?? ''), commissionNow ?? '');
check('a running plan is shown', (await shopPage.getByText(/days left/i).count()) > 0);

const runningRow = await shopPage.getByText('Running').count();
check('history shows it as running', runningRow > 0, `${runningRow} running row(s)`);

if (SHOTS) await shopPage.screenshot({ path: `${DIR}/p6-journey-3-shop.png`, fullPage: true });

// ---------------------------------------------------------------- clean up
// Leave the shop on the plan so the developer sees a populated dashboard; a throwaway purchase
// would make the seeded dev data misleading.

console.log('\nERRORS');
check('no console errors or 4xx/5xx', errors.length === 0, errors.slice(0, 5).join(' | ') || 'none');

await browser.close();
console.log(`\n${failures === 0 ? 'JOURNEY OK' : `${failures} check(s) failing`}`);
process.exit(failures === 0 ? 0 : 1);
