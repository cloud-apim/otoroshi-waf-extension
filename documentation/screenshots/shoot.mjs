// Logs into a local Otoroshi and captures the Threat Studio pages for the documentation.
//
//   npm run setup   # once: installs playwright and the chromium build
//   npm run shoot   # writes ../static/img/screenshots/studio-*.png
//
// Overrides via env: OTO_URL, OTO_USER, OTO_PASSWORD, TS_THEME (light|dark), TS_WS (workspace id).
//
// The workspace is auto-detected (the first rule of the global preset table) unless TS_WS is set, so
// the script works against any install that has one workspace — see the README to seed one.

import { chromium } from 'playwright';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { mkdirSync } from 'node:fs';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(HERE, '../static/img/screenshots');
mkdirSync(OUT, { recursive: true });

const BASE = (process.env.OTO_URL || 'http://otoroshi.oto.tools:9999').replace(/\/$/, '');
const USER = process.env.OTO_USER || 'admin@otoroshi.io';
const PASSWORD = process.env.OTO_PASSWORD || 'password';
const THEME = process.env.TS_THEME === 'dark' ? 'dark' : 'light';
const STUDIO = `${BASE}/extensions/cloud-apim/threat-studio`;

// 1568-wide to match the screenshots already in the docs; scale 1 keeps the files small
const VIEWPORT = { width: 1568, height: 950 };

async function login(page) {
  await page.goto(`${BASE}/bo/simple/login`, { waitUntil: 'networkidle' });
  // already signed in? otoroshi bounces /bo/simple/login to the dashboard
  if (!page.url().includes('/login')) return;
  await page.waitForSelector('input[name="email"]', { timeout: 15000 });
  await page.fill('input[name="email"]', USER);
  await page.fill('input[name="password"]', PASSWORD);
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
    page.click('button[type="submit"]'),
  ]);
}

async function firstWorkspaceId(page) {
  if (process.env.TS_WS) return process.env.TS_WS;
  const table = await page.evaluate(async (studioApi) => {
    const r = await fetch(studioApi, { credentials: 'include' });
    if (!r.ok) return null;
    return r.json();
  }, `${BASE}/extensions/cloud-apim/extensions/waf/studio/workspaces`);
  const ws = table && table.workspaces && table.workspaces[0];
  return ws ? ws.id : null;
}

async function shoot(page, name, { fullPage = false, settle = 900, before } = {}) {
  if (before) await before(page);
  // the analytics pages fire a burst of queries; wait for them to land, then a beat for the charts
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForTimeout(settle);
  const file = resolve(OUT, `${name}.png`);
  await page.screenshot({ path: file, fullPage });
  console.log(`  ✓ ${name}.png${fullPage ? ' (full page)' : ''}`);
}

async function goto(page, path) {
  await page.goto(`${STUDIO}${path}`, { waitUntil: 'networkidle' });
}

const run = async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext({
    viewport: VIEWPORT,
    deviceScaleFactor: 1,
    colorScheme: THEME,
  });
  // the studio reads its theme from localStorage before React mounts, so setting it here is what
  // keeps every capture on the same, doc-friendly background
  await context.addInitScript((theme) => {
    try {
      window.localStorage.setItem('threat_studio_theme', theme);
    } catch (e) {}
  }, THEME);

  const page = await context.newPage();

  console.log(`→ ${BASE} as ${USER} (${THEME} theme)`);
  await login(page);

  await goto(page, '');
  const ws = await firstWorkspaceId(page);
  if (!ws) {
    console.error(
      '\n✗ No workspace found. Threat Studio shows one per rule of the global preset table.\n' +
        '  Create one first (Workspaces → New workspace), or point TS_WS at an existing id.\n'
    );
    await browser.close();
    process.exit(1);
  }
  console.log(`  workspace: ${ws}`);
  const w = (p) => `/workspaces/${ws}${p}`;

  console.log('capturing…');
  // the table of workspaces — the way in
  await shoot(page, 'studio-workspaces', { before: (p) => goto(p, '') });

  // the analytics, which is what the studio is really for — full page so every panel shows
  await shoot(page, 'studio-activity', { before: (p) => goto(p, w('/activity')), fullPage: true, settle: 1600 });

  // the rows themselves, with the signals behind each decision
  await shoot(page, 'studio-events', { before: (p) => goto(p, w('/events')), settle: 1400 });

  // which routes the workspace governs, and the posture each ends up with
  await shoot(page, 'studio-routes', { before: (p) => goto(p, w('/routes')) });

  // the selector
  await shoot(page, 'studio-scope', { before: (p) => goto(p, w('/scope')) });

  // the arming console
  await shoot(page, 'studio-protection', { before: (p) => goto(p, w('/protection')), fullPage: true });

  // the workspace overview
  await shoot(page, 'studio-overview', { before: (p) => goto(p, w('/overview')), fullPage: true, settle: 1400 });

  // every route of the install, and where its protection comes from
  await shoot(page, 'studio-fleet', { before: (p) => goto(p, '/fleet') });

  // the entity editor, opened on the first threat policy — that page's only table is the entities,
  // so the first row is the one that opens the editor (the WAF page has the tuning table above it)
  await shoot(page, 'studio-entity-editor', {
    before: async (p) => {
      await goto(p, w('/policy'));
      await p.waitForLoadState('networkidle').catch(() => {});
      const row = p.locator('table tbody tr').first();
      if (await row.count()) {
        await row.click();
        await p.waitForSelector('.drawer', { state: 'visible', timeout: 5000 }).catch(() => {});
        await p.waitForTimeout(500);
      }
    },
    settle: 900,
  });

  await browser.close();
  console.log(`\nDone. Wrote to ${OUT}`);
};

run().catch((err) => {
  console.error(err);
  process.exit(1);
});
