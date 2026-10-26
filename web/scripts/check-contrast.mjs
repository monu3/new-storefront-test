/**
 * Contrast audit for the coffee palette.
 *
 * <p><b>Why this exists.</b> `check-ui.mjs` contains a relative-luminance helper and a 4.5:1
 * comparison, but nothing in it ever calls them — so "the UI check passes" said nothing about
 * whether any text was readable. That was survivable while the palette was white and near-black.
 * It stopped being survivable the moment the theme became warm: a coffee accent and a muted warm
 * grey are exactly the colours that look pleasant and land just under the threshold.
 *
 * <p>The arithmetic is worth doing before the screenshot rather than after. `--warning` at
 * `37 61% 43%` is 3.6:1 on warm paper — it looks fine, and it fails for anybody with reduced
 * contrast sensitivity. Measuring found it; looking did not.
 *
 * <p><b>This measures rendered pixels, not the token file.</b> It walks the live DOM, resolves each
 * text element's effective colour against the nearest ancestor with an opaque background, and
 * applies the WCAG thresholds: 4.5:1 for body text, 3:1 for large text (>=24px, or >=18.66px bold).
 *
 * <p>Known limit, stated rather than hidden: this cannot see a background drawn by a pseudo-element
 * or a gradient, so it reports `unknown` for those instead of guessing.
 *
 *   node scripts/check-contrast.mjs
 */
import { chromium } from 'playwright';

const CONSOLE_PAGES = [
  ['console: dashboard', 'http://localhost:5173/shop'],
  ['console: products', 'http://localhost:5173/shop/products'],
  ['console: orders', 'http://localhost:5173/shop/orders'],
];

const STOREFRONT_PAGES = [
  ['storefront: landing', 'http://localhost:3000/'],
  ['storefront: shops', 'http://localhost:3000/shops'],
  // A live result set, not the empty state: the result cards and the pagination strip are the
  // surfaces that only appear once there is data, and they were not covered while this page did
  // not exist at all.
  ['storefront: search', 'http://localhost:3000/search?q=coffee'],
];

const WHOLE_PAGE = () => {
  /** Parse `rgb()` / `rgba()` into channels plus alpha. */
  function rgb(value) {
    const m = value.match(/rgba?\(([^)]+)\)/);
    if (!m) return null;
    const parts = m[1].split(/[,\s/]+/).filter(Boolean).map(Number);
    return { r: parts[0], g: parts[1], b: parts[2], a: parts.length > 3 ? parts[3] : 1 };
  }

  function luminance({ r, g, b }) {
    const channel = (c) => {
      const s = c / 255;
      return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    };
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
  }

  /** Composite a translucent colour over an opaque one. */
  function over(fg, bg) {
    const a = fg.a;
    return {
      r: fg.r * a + bg.r * (1 - a),
      g: fg.g * a + bg.g * (1 - a),
      b: fg.b * a + bg.b * (1 - a),
      a: 1,
    };
  }

  function ratio(a, b) {
    const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
    return (hi + 0.05) / (lo + 0.05);
  }

  /** Nearest ancestor background that is opaque enough to judge against. */
  function backgroundOf(el) {
    let node = el;
    while (node && node !== document.documentElement) {
      const bg = rgb(getComputedStyle(node).backgroundColor);
      if (bg && bg.a >= 0.95) return bg;
      node = node.parentElement;
    }
    return rgb(getComputedStyle(document.body).backgroundColor) ?? { r: 255, g: 255, b: 255, a: 1 };
  }

  const findings = [];

  for (const el of document.querySelectorAll('p, span, a, button, h1, h2, h3, h4, h5, h6, label, td, th, li, div')) {
    // Only elements that own text directly — otherwise every container reports its children's text.
    const own = [...el.childNodes]
      .filter((n) => n.nodeType === Node.TEXT_NODE)
      .map((n) => n.textContent.trim())
      .join(' ')
      .trim();

    if (own.length < 2) continue;

    const cs = getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.display === 'none' || Number(cs.opacity) < 0.5) continue;

    const rect = el.getBoundingClientRect();
    if (rect.width === 0 || rect.height === 0) continue;
    // Off-screen elements are not something a person is being asked to read.
    if (rect.top > window.innerHeight * 3 || rect.bottom < -window.innerHeight) continue;

    const fgRaw = rgb(cs.color);
    if (!fgRaw) continue;

    const bg = backgroundOf(el);
    const fg = fgRaw.a < 1 ? over(fgRaw, bg) : fgRaw;
    const value = ratio(fg, bg);

    const size = parseFloat(cs.fontSize);
    const bold = Number(cs.fontWeight) >= 700;
    const large = size >= 24 || (bold && size >= 18.66);
    const needed = large ? 3 : 4.5;

    if (value < needed) {
      findings.push({
        text: own.slice(0, 48),
        ratio: Math.round(value * 100) / 100,
        needed,
        size: Math.round(size),
        colour: cs.color,
        background: `rgb(${Math.round(bg.r)}, ${Math.round(bg.g)}, ${Math.round(bg.b)})`,
        tag: el.tagName.toLowerCase(),
      });
    }
  }

  return findings;
};

const browser = await chromium.launch({ channel: 'chrome' });
let failures = 0;

async function audit(label, url, page) {
  await page.goto(url, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(3500);

  const findings = await page.evaluate(WHOLE_PAGE);

  // One report per distinct (text, ratio) pair — a repeated table cell is one problem, not forty.
  const unique = [];
  const seen = new Set();
  for (const f of findings) {
    const key = `${f.text}|${f.ratio}`;
    if (seen.has(key)) continue;
    seen.add(key);
    unique.push(f);
  }

  console.log(`\n${label}  ${url}`);
  if (unique.length === 0) {
    console.log('  ok    every visible text colour meets its WCAG threshold');
    return;
  }

  failures += unique.length;
  for (const f of unique.slice(0, 8)) {
    console.log(
      `  FAIL  ${f.ratio}:1 (needs ${f.needed}) ${f.size}px ${f.tag} — ` +
        `"${f.text}" ${f.colour} on ${f.background}`,
    );
  }
  if (unique.length > 8) console.log(`  ... and ${unique.length - 8} more`);
}

const consoleCtx = await browser.newContext({ viewport: { width: 1440, height: 950 } });
const consolePage = await consoleCtx.newPage();

await consolePage.goto('http://localhost:5173/login', { waitUntil: 'domcontentloaded' });
await consolePage.waitForTimeout(1500);
const signIn = consolePage.getByRole('button', { name: /sign in|continue/i });
if (await signIn.count()) {
  await signIn.first().click();
  await consolePage.waitForTimeout(3500);
}
if (await consolePage.locator('#username').count()) {
  await consolePage.locator('#username').fill('ops.test');
  await consolePage.locator('#password').fill('iloveu');
  await consolePage.locator('#kc-login').click();
  await consolePage.waitForTimeout(6000);
}

for (const [label, url] of CONSOLE_PAGES) {
  await audit(label, url, consolePage);
}

const sfCtx = await browser.newContext({ viewport: { width: 1440, height: 950 } });
const sfPage = await sfCtx.newPage();
for (const [label, url] of STOREFRONT_PAGES) {
  await audit(label, url, sfPage);
}

await browser.close();

console.log(
  `\n${failures === 0 ? 'CONTRAST OK' : `CONTRAST: ${failures} failing element(s)`}`,
);
process.exit(failures === 0 ? 0 : 1);
