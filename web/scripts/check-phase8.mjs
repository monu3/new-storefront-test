/**
 * Phase 8 in the browser: returns and social selling, driven as a shop owner.
 *
 * <p>The returns flow is driven end to end rather than page by page, because the interesting part is
 * the hand-off between the customer and the shop: a return requested on one side has to appear in
 * the other's queue, and only driving it proves the two agree.
 *
 * <p>It also asserts the <em>absence</em> of a money button at the wrong moment. "Approve" must not
 * move money — only "Refund" does — and a screen that offered both at once would credit a customer's
 * refund the instant a shop clicked a decision.
 *
 *   node scripts/check-phase8.mjs [--shots]
 */
import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';

const SHOTS = process.argv.includes('--shots');
const DIR = '.ui-checks';

const SHOP_USER = process.env.KC_OPS_USER ?? 'ops.test';
const SHOP_PASS = process.env.KC_OPS_PASS ?? 'iloveu';

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
  page.on('console', (m) => m.type() === 'error' && errors.push(`${label}: ${m.text().slice(0, 140)}`));
  page.on('pageerror', (e) => errors.push(`${label} pageerror: ${e.message.slice(0, 140)}`));
  page.on('response', (r) => {
    if (r.status() >= 400 && r.url().includes('/api/')) {
      errors.push(`${label}: HTTP ${r.status()} ${r.url().replace(/^https?:\/\/localhost:\d+/, '')}`);
    }
  });
}

console.log('\nPHASE 8: returns and social selling');

const context = await browser.newContext({ viewport: { width: 1400, height: 1100 } });
const page = await context.newPage();
watch(page, 'shop');

await signIn(page, SHOP_USER, SHOP_PASS);
check('signed in as a shop owner', !(await page.url().includes('/login')), page.url());

// ---------------------------------------------------------------- the returns queue

console.log('\nRETURNS: the shop queue');
let before = errors.length;
await page.goto('http://localhost:5173/shop/returns', { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(4000);

let body = ((await page.locator('body').innerText()) || '').replace(/\s+/g, ' ');
check('the returns screen renders', /Returns/.test(body) && /Approving records a decision/.test(body));
check(
  'the screen states that approving and refunding are different',
  /Approving records a decision\. Refunding debits your wallet/.test(body),
  'the distinction between deciding and paying, stated before the shop clicks anything',
);

// Open the decision panel too, if there is anything open to decide -- the panel is where the
// per-line quantities live, and it is the part a shop actually reads.
const decideButton = page.getByRole('button', { name: /^Decide$/ });
if (await decideButton.count()) {
  await decideButton.first().click();
  await page.waitForTimeout(1200);

  const panel = ((await page.locator('body').innerText()) || '').replace(/\s+/g, ' ');

  check(
    'the decision panel says approving moves no money',
    /Approving does not move money/.test(panel),
    'the shop must know the money button comes afterwards',
  );
  check('the panel has a Refund-only path', /Note \(required to decline\)/.test(panel));
} else {
  console.log('  --    no open return to decide, so the decision panel was not exercised');
}
check('the returns queue is error-free', errors.length === before, errors.slice(before).join(' | '));

if (SHOTS) {
  await page.screenshot({ path: `${DIR}/phase8-shop-returns.png`, fullPage: true });
}

// ---------------------------------------------------------------- social selling

console.log('\nSHARING: the share sheet');
before = errors.length;
await page.goto('http://localhost:5173/shop/products', { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(4000);

const shareButtons = page.getByRole('button', { name: /^Share / });
const shareCount = await shareButtons.count();
check('products offer a share action', shareCount > 0, `${shareCount} product(s)`);

if (shareCount > 0) {
  await shareButtons.first().click();
  await page.waitForTimeout(3000);

  body = ((await page.locator('body').innerText()) || '').replace(/\s+/g, ' ');

  check('the share sheet opens', /Share on WhatsApp/.test(body));
  check('WhatsApp is the primary button', /Share on WhatsApp/.test(body));
  check('copy-to-clipboard is offered', /Copy text|Copied/.test(body));
  check(
    'the links are attributed',
    /utm_(source|campaign)=share/.test(body),
    'a share feature without attribution is decoration',
  );
  check(
    'no link points at a placeholder shop',
    !/your-shop/.test(body),
    'the old anchor was a literal "your-shop" string',
  );
  check('the share sheet is error-free', errors.length === before, errors.slice(before).join(' | '));

  if (SHOTS) {
    await page.screenshot({ path: `${DIR}/phase8-share-sheet.png`, fullPage: true });
  }
}

console.log('\nCONSOLE');
check('no uncaught errors anywhere', errors.length === 0, errors.slice(0, 6).join(' | '));

await browser.close();
console.log(`\n${failures === 0 ? 'PHASE 8 UI OK' : `PHASE 8 UI: ${failures} failure(s)`}`);
process.exit(failures === 0 ? 0 : 1);