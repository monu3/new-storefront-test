/**
 * Sign in through Keycloak and screenshot the dashboard.
 *
 * This is the check that matters: every previous console bug needed a real browser and a real
 * login to reproduce. A screenshot of the logged-in shell is the only proof the replication works.
 */
import { chromium } from 'playwright';

const browser = await chromium.launch({ channel: 'chrome' });
const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
const page = await context.newPage();

const consoleErrors = [];
const pageErrors = [];
page.on('console', (m) => m.type() === 'error' && consoleErrors.push(m.text()));
page.on('pageerror', (e) => pageErrors.push(e.message));

await page.goto('http://localhost:5173/shop', { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(2500);
console.log('  after redirect, URL host/path:', new URL(page.url()).host + new URL(page.url()).pathname);

// Step 1: the console's own login card, which hands off to Keycloak.
const kcButton = page.getByRole('button', { name: /Continue with Keycloak/i });
if (await kcButton.count()) {
  await kcButton.click();
  await page.waitForTimeout(4000);
  console.log('  handed off to:', new URL(page.url()).host + new URL(page.url()).pathname);
}

// Step 2: the Keycloak form itself.
const user = page.locator('#username');
if (await user.count()) {
  await user.fill('ops.test');
  await page.locator('#password').fill('iloveu');
  await page.locator('#kc-login').click();
  await page.waitForTimeout(5000);
  console.log('  signed in, landed on:', new URL(page.url()).host + new URL(page.url()).pathname);
} else if (!new URL(page.url()).port.startsWith('5173')) {
  console.log('  left Keycloak but no form found');
}

await page.waitForTimeout(3500);
console.log('  final path:', new URL(page.url()).pathname);

// What the shell actually rendered.
const h1 = await page.locator('h1').first().textContent().catch(() => null);
const stats = await page.locator('[data-testid^="stat-"]').count();
const navLinks = await page.locator('aside a').count();
const svgs = await page.locator('svg.recharts-surface').count();
console.log('  h1                :', h1);
console.log('  stat cards        :', stats);
console.log('  sidebar links     :', navLinks);
console.log('  charts rendered   :', svgs);
console.log('  console errors    :', consoleErrors.length ? consoleErrors.slice(0, 3) : 'none');
console.log('  page errors       :', pageErrors.length ? pageErrors.slice(0, 3) : 'none');

await page.screenshot({ path: '.ui-checks/console-DASHBOARD.png', fullPage: true });
console.log('  screenshot: .ui-checks/console-DASHBOARD.png');
await browser.close();
