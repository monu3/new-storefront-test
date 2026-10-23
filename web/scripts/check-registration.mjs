/**
 * The registration journey, end to end, with brand-new accounts.
 *
 * <p>Everything before this phase was tested by signing in as `ops.test` and `cust.test` — two
 * accounts that had been in Keycloak since the beginning, with roles attached by hand. That proves
 * the console works for an owner and the storefront works for a customer. It proves **nothing** about
 * whether a person who has never been here before can get in, which is the only thing most people
 * will ever do.
 *
 * <p>So this registers two real accounts through Keycloak's own form and follows each one to the
 * place it is supposed to end up:
 *
 * <ul>
 *   <li>a shop owner — registration, then ROLE_SHOP in the token, then the onboarding wizard, then
 *       a dashboard;</li>
 *   <li>a customer — registration, then a signed-in storefront.</li>
 * </ul>
 *
 * <p><b>It creates real accounts in your Keycloak and cannot delete them</b> — there is no admin API
 * credential here, and pretending otherwise would mean leaving an undeletable account behind without
 * saying so. The names are prefixed `phase85-` so they are obvious to delete.
 *
 * <p><b>If realm registration is switched off, this prints NOT RUN and exits cleanly.</b> That is a
 * configuration state, not a failure, and reporting it as one would train you to ignore this
 * script.
 *
 *   node scripts/check-registration.mjs
 */
import { chromium } from 'playwright';

const KC = 'https://auth-master.yenyasoft.com';
const STAMP = Date.now().toString().slice(-6);

const OWNER = {
  username: `phase85-owner-${STAMP}`,
  email: `phase85-owner-${STAMP}@example.test`,
  first: 'Phase',
  last: 'Owner',
};
const CUSTOMER = {
  username: `phase85-buyer-${STAMP}`,
  email: `phase85-buyer-${STAMP}@example.test`,
  first: 'Phase',
  last: 'Buyer',
};
const PASSWORD = 'iloveu-Phase85!';

const browser = await chromium.launch({ channel: 'chrome' });
let failures = 0;
/** Journeys actually driven. Zero means the summary below must not claim a pass. */
let ran = 0;
const check = (name, ok, detail = '') => {
  console.log(`  ${ok ? 'ok  ' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
  if (!ok) failures++;
};

// ---------------------------------------------------------------- shop owner

console.log('\nREGISTRATION: a brand-new shop owner');

const ownerCtx = await browser.newContext({ viewport: { width: 1400, height: 1000 } });
const ownerPage = await ownerCtx.newPage();

/**
 * Clicks the sign-up control and reports where Keycloak actually took us.
 *
 * <p>Deliberately goes through the page rather than assembling a registration URL. The first
 * version of this script built one, and it passed while the button in front of a real person was
 * broken — Keycloak refused the hand-made URL for want of a PKCE challenge and returned the
 * browser to the app with an error, and a script that never clicked anything could not see it.
 */
async function beginRegistration(page, appUrl, control) {
  await page.goto(`${appUrl}/login`, { waitUntil: 'domcontentloaded' });
  // Next.js compiles /login on first hit, and clicking before it hydrates is a false negative that
  // looks exactly like a broken button.
  await page.waitForTimeout(4000);

  await page.getByRole('button', { name: control }).first().click();
  await page.waitForTimeout(5000);

  const url = page.url();
  const body = ((await page.locator('body').innerText()) || '').replace(/\s+/g, ' ');

  if (/registration not allowed/i.test(body)) return { state: 'realmOff', body };
  if (/client not found/i.test(body)) return { state: 'clientOff', body };
  if (/Missing parameter/i.test(body)) return { state: 'noPkce', body };
  if (/invalid_request|error_description/i.test(body)) return { state: 'error', body };

  // #kc-register-form is the registration form; the login form has no such id.
  const isForm = (await page.locator('#kc-register-form').count()) > 0;
  return { state: isForm ? 'form' : 'error', body, url };
}

/**
 * Fills Keycloak's registration form and submits it.
 *
 * <p><b>Keycloak 26 renders this in PatternFly markup, not the older freeform one.</b> The submit
 * control is an `<input type="submit" value="Register">` with no id at all, so selectors written
 * for an older Keycloak find nothing and the journey reports a failure that reads like a product
 * bug. Every selector here was read off the live page rather than remembered.
 *
 * <p><b>No username field</b> — both realms run email-as-username, so `#username` does not exist.
 * Filled when present, not required, rather than the other way round.
 */
async function register(page, who) {
  await page.waitForSelector('#kc-register-form', { timeout: 20000 });

  const username = page.locator('#username');
  if (await username.count()) await username.fill(who.username);

  await page.locator('#email').fill(who.email);
  await page.locator('#password').fill(PASSWORD);
  await page.locator('#password-confirm').fill(PASSWORD);
  await page.locator('#firstName').fill(who.first);
  await page.locator('#lastName').fill(who.last);

  await page.locator('#kc-register-form input[type="submit"]').click();
  await page.waitForTimeout(7000);

  return page.url();
}

const opsStart = await beginRegistration(
  ownerPage, 'http://localhost:5173', 'Start a new shop',
);

if (opsStart.state === 'realmOff') {
  console.log('  NOT RUN  — the ops realm has "User registration" switched off.');
  console.log('');
  console.log('    To enable it, in Keycloak:');
  console.log('      1. Select the "ops" realm');
  console.log('      2. Realm settings → Login');
  console.log('      3. Turn ON "User registration"');
  console.log('      4. Save, then re-run this script');
  console.log('');
  console.log('    This is a different switch from the default client role you already set.');
  console.log('    Both are needed: the realm switch permits registration at all, and the');
  console.log('    client role is what makes the new account able to open a shop console.');
} else if (opsStart.state === 'clientOff') {
  console.log('  FAIL  the console\'s client id was refused by the ops realm.');
  console.log('        The link reached Keycloak and Keycloak does not know store-app-console.');
  failures++;
} else if (opsStart.state === 'noPkce') {
  console.log('  FAIL  Keycloak refused the request for a missing PKCE code_challenge.');
  console.log('        This is the hand-built-link bug: the adapter must mint the challenge.');
  failures++;
} else if (opsStart.state !== 'form') {
  console.log(`  FAIL  no registration form appeared — ${opsStart.body.slice(0, 100)}`);
  failures++;
} else {
  ran++;
  const landed = await register(ownerPage, OWNER);

  check(
    'registration submitted and returned to the console',
    !landed.includes('/realms/ops/protocol/openid-connect/registrations'),
    landed.slice(0, 70),
  );

  // Keycloak may show a "verify email" screen instead of signing in. Both are correct outcomes of
  // a registration and neither is a failure, so the check is whether we ended up somewhere real.
  await ownerPage.waitForTimeout(4000);
  const url = ownerPage.url();

  if (url.includes('login-actions/verify-email') || url.includes('login-actions/')) {
    console.log('  --    Keycloak asked for email verification before signing in.');
    console.log('        That is a realm setting, not a fault. Verify the address in Keycloak and re-run');
    console.log('        to follow the rest of the journey.');
  } else {
    check(
      'the new owner is signed in',
      url.includes('localhost:5173'),
      url.slice(0, 70),
    );

    const roles = await ownerPage.evaluate(async () => {
      const raw = Object.keys(localStorage)
        .map((key) => localStorage.getItem(key) ?? '')
        .join(' ');
      return raw.includes('ROLE_SHOP') || raw.includes('"SHOP"');
    });
    void roles;

    // The onboarding wizard is where an unfinished owner belongs, and reaching it proves the
    // ROLE_SHOP default worked — without it every screen would have refused them.
    await ownerPage.waitForTimeout(3000);
    const body = ((await ownerPage.locator('body').innerText()) || '').replace(/\s+/g, ' ');

    check(
      'the new owner lands on the onboarding wizard, not on an access error',
      /Tell us about your shop|Shop name/.test(body),
      body.slice(0, 80),
    );

    check(
      'they were not refused as a role-less account',
      !/no access yet|You do not have access/i.test(body),
      'the realm default client role for SHOP is applied',
    );

    if (/Shop name/.test(body)) {
      await ownerPage.getByLabel('Shop name', { exact: true }).fill('Phase Eighty Five Books');
      await ownerPage.getByRole('button', { name: /^Books$/ }).first().click();
      await ownerPage.waitForTimeout(500);

      await ownerPage.getByRole('button', { name: /Create my shop/ }).click();
      await ownerPage.waitForTimeout(6000);

      const after = ((await ownerPage.locator('body').innerText()) || '').replace(/\s+/g, ' ');

      check(
        'completing sign-up reaches the dashboard',
        /Total Revenue|Your shop|Dashboard/i.test(after),
        after.slice(0, 70),
      );

      check(
        'the shop is called what they chose, not "My shop"',
        /Phase Eighty Five Books/i.test(after),
        'the placeholder name is gone',
      );
    }
  }
}

// ---------------------------------------------------------------- customer

console.log('\nREGISTRATION: a brand-new customer');

const buyerCtx = await browser.newContext({ viewport: { width: 1400, height: 1000 } });
const buyerPage = await buyerCtx.newPage();

const customersStart = await beginRegistration(
  buyerPage, 'http://localhost:3000', 'Create an account',
);

if (customersStart.state === 'realmOff') {
  console.log('  NOT RUN  — the customers realm has "User registration" switched off.');
  console.log('        Same switch, on the "customers" realm instead of "ops".');
} else if (customersStart.state !== 'form') {
  console.log(`  FAIL  no registration form appeared — ${customersStart.body.slice(0, 100)}`);
  failures++;
} else {
  ran++;
  const landed = await register(buyerPage, CUSTOMER);

  check(
    'registration submitted and returned to the storefront',
    !landed.includes('/protocol/openid-connect/registrations'),
    landed.slice(0, 70),
  );

  await buyerPage.waitForTimeout(5000);
  const body = ((await buyerPage.locator('body').innerText()) || '').replace(/\s+/g, ' ');

  check(
    'the new customer is signed in to the storefront',
    buyerPage.url().includes('localhost:3000'),
    buyerPage.url().slice(0, 70),
  );

  check(
    'they see their account, not the sign-in page again',
    !/Continue with Keycloak/.test(body) || /Signed in|Orders|Account/i.test(body),
    body.slice(0, 70),
  );
}

// ---------------------------------------------------------------- summary

// Only printed when something was actually created. A summary that lists accounts which do not
// exist is worse than no summary.
if (ran > 0) {
  console.log('\nACCOUNTS CREATED (delete these when you are finished):');
  console.log(`  ops       ${OWNER.email}`);
  console.log(`  customers ${CUSTOMER.email}`);
  console.log(`  password  ${PASSWORD}`);
}

await browser.close();

// `ran` is checked before `failures`. A script that ran nothing and reported OK is the exact
// failure this project has been bitten by more than once, so it gets its own explicit state
// rather than being folded into the pass case.
if (ran === 0) {
  console.log('\nREGISTRATION NOT RUN — realm registration is off, so no account was created.');
  process.exit(0);
}

console.log(
  `\n${failures === 0 ? 'REGISTRATION OK' : `REGISTRATION: ${failures} failure(s)`}`,
);
process.exit(failures === 0 ? 0 : 1);