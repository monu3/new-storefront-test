/**
 * Appearance (themes gallery + customiser) — a real walk, not a markup check.
 *
 * <p>Written because the screens are new and the failure modes are silent: a theme grid that
 * renders empty, a preview that shows the wrong shop, a tab that changes the URL but not the
 * panel, and a Publish button that is enabled when there is nothing to publish all look fine in a
 * screenshot and are all broken. So this script clicks the controls and reads what changed.
 *
 * <p>It deliberately does <b>not</b> publish. The walk runs against a real shop's live theme, and
 * a check that mutates the thing it is checking is not a check.
 *
 * Usage: node scripts/check-appearance.mjs [--shots]
 */
import { chromium } from 'playwright';

const SHOTS = process.argv.includes('--shots');
const DIR = '/tmp/appearance-shots';
const USER = process.env.KC_OPS_USER ?? 'ops.test';
const PASS = process.env.KC_OPS_PASS ?? 'iloveu';

let failures = 0;
const check = (name, ok, detail = '') => {
  console.log(`  ${ok ? 'ok  ' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
  if (!ok) failures++;
};

const errors = [];

async function signIn(page) {
  await page.goto('http://localhost:5173/login', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1500);

  const button = page.getByRole('button', { name: /sign in|continue/i });
  if (await button.count()) {
    await button.first().click();
    await page.waitForTimeout(3500);
  }
  if (await page.locator('#username').count()) {
    await page.locator('#username').fill(USER);
    await page.locator('#password').fill(PASS);
    await page.locator('#kc-login').click();
    await page.waitForTimeout(6000);
  }
}

const browser = await chromium.launch({ channel: 'chrome' });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const page = await context.newPage();

page.on('console', (m) => m.type() === 'error' && errors.push(m.text().slice(0, 160)));
page.on('pageerror', (e) => errors.push(`pageerror: ${e.message.slice(0, 160)}`));
page.on('response', (r) => {
  if (r.status() >= 400 && r.url().includes('/api/')) {
    errors.push(`HTTP ${r.status()} ${r.url().replace(/^https?:\/\/localhost:\d+/, '')}`);
  }
});

if (SHOTS) {
  const { mkdir } = await import('node:fs/promises');
  await mkdir(DIR, { recursive: true });
}

await signIn(page);
if (page.url().includes('/login')) {
  console.log('  FAIL  could not sign in — is the console running?');
  await browser.close();
  process.exit(1);
}

console.log('\nAPPEARANCE: themes gallery');

await page.goto('http://localhost:5173/shop/theme', { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(6000);

check('gallery renders its title', (await page.getByRole('heading', { name: 'Themes', exact: true }).count()) === 1);

const useThemeButtons = page.getByRole('button', { name: /^Use theme$/ });
const useThemeCount = await useThemeButtons.count();
check('at least one template is offered', useThemeCount >= 1, `${useThemeCount} found`);

const customize = page.getByRole('button', { name: /^(Customize|Edit draft)$/ });
const customizeCount = await customize.count();
check('an installed theme offers an editor', customizeCount >= 1, `${customizeCount} found`);

// The previews are the whole point of the screen. If the renderer failed, the fallback text in
// ThemeCard is the only thing in the frame — assert on the shop's real name appearing more than
// once (card footers carry theme names, not the shop's).
const shopName = 'Phase1 Corner Store';
const shopNameHits = await page.getByText(shopName, { exact: false }).count();
check('theme previews render this shop', shopNameHits >= 2, `${shopNameHits} occurrences`);

if (SHOTS) await page.screenshot({ path: `${DIR}/gallery.png`, fullPage: true });

console.log('\nAPPEARANCE: customiser');

await customize.first().click();
await page.waitForURL(/\/shop\/theme\/customize/, { timeout: 15000 }).catch(() => {});
await page.waitForTimeout(5000);

check('customiser is a separate screen', page.url().includes('/shop/theme/customize'), page.url());
check(
  'customiser names the theme',
  (await page.getByRole('heading', { name: 'Customize theme', exact: true }).count()) === 1,
);

// The preview must be the shop's own storefront, not a mock. "Phase1 Corner Store" is the real
// shop name; the old mock said "Your shop".
check(
  'live preview is the real storefront',
  (await page.getByText(shopName, { exact: false }).count()) >= 1,
);

const publish = page.getByRole('button', { name: /Publish changes/ });
check('nothing to publish on a clean draft', await publish.isDisabled());

const tabs = [
  ['Header', /Show shop name/],
  ['Banners', /announcement|hero|banner/i],
  ['Sections', /Your sections, in order/],
  ['Fonts & colours', /These colours are your shop/],
  ['Advanced', /No problems found|Checks/],
];

for (const [label, panelText] of tabs) {
  await page.getByRole('button', { name: new RegExp(`^${label}`) }).first().click();
  await page.waitForTimeout(700);
  const visible = await page.getByText(panelText).count();
  check(`tab "${label}" shows its panel`, visible >= 1);
  if (SHOTS) {
    await page.screenshot({
      path: `${DIR}/tab-${label.toLowerCase().replace(/[^a-z]+/g, '-')}.png`,
      fullPage: true,
    });
  }
}

// Edit through the UI and confirm the draft/publish state actually tracks it. This is the one
// assertion that would have caught a "Publish" button wired to nothing.
await page.getByRole('button', { name: /^Header/ }).first().click();
await page.waitForTimeout(500);
const showName = page.getByRole('switch').first();
if (await showName.count()) {
  await showName.click();
  await page.waitForTimeout(600);
  check('editing enables Publish', await publish.isEnabled());
  check(
    'editing marks the theme unpublished',
    (await page.getByText(/Unpublished changes/).count()) >= 1,
  );
  await page.getByRole('button', { name: /^Undo$/ }).click();
  await page.waitForTimeout(600);
  check('undo returns to a clean draft', await publish.isDisabled());
} else {
  check('header inspector exposes a switch', false, 'no switch found');
}

// The sidebar link is the one link whose whole job is "see your shop", and it used to point at a
// console route that does not exist.
const visit = page.locator('aside a', { hasText: 'Visit your shop' }).first();
const visitHref = (await visit.getAttribute('href')) ?? '';
check('sidebar shop link leaves the console', visitHref.startsWith('http'), visitHref);

// Measured, not asserted. The console has no phone layout at all: its 264px fixed sidebar sits
// beside the content at every width, so `/shop/products` overflows a 390px viewport by 900px
// before Appearance is involved. Reporting the number keeps it visible without pretending this
// work either caused or fixed it.
console.log('\nAPPEARANCE: phone (console-wide limitation, not a gate)');
await page.setViewportSize({ width: 390, height: 844 });

for (const [name, url] of [
  ['gallery', 'http://localhost:5173/shop/theme'],
  ['customiser', 'http://localhost:5173/shop/theme/customize'],
]) {
  await page.goto(url, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(5000);
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  console.log(`  note  ${name} overflows a 390px viewport by ${overflow}px`);
  if (SHOTS) await page.screenshot({ path: `${DIR}/phone-${name}.png`, fullPage: true });
}

console.log('\nAPPEARANCE: console health');
const uniqueErrors = [...new Set(errors)];
check('no console errors or failed API calls', uniqueErrors.length === 0, uniqueErrors.slice(0, 4).join(' | '));

await browser.close();
console.log(`\n${failures === 0 ? 'APPEARANCE OK' : `APPEARANCE FAILED: ${failures} check(s)`}`);
process.exit(failures === 0 ? 0 : 1);
