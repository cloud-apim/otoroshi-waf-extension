// Logs into a local Otoroshi and captures the Threat Studio pages for the documentation.
//
//   npm run setup   # once: installs playwright and the chromium build
//   npm run shoot   # writes ../static/img/screenshots/studio-*.png
//
// Overrides via env: OTO_URL, OTO_USER, OTO_PASSWORD, TS_THEME (light|dark), TS_WS (workspace id),
// TS_ONLY (a regex on the capture names, e.g. `TS_ONLY=activity|events` to redo only those).
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
const THEME = process.env.TS_THEME === 'light' ? 'light' : 'dark';
const STUDIO = `${BASE}/extensions/cloud-apim/threat-studio`;

// 1568-wide to match the screenshots already in the docs; scale 1 keeps the files small
const VIEWPORT = { width: 1568, height: 950 };

const ONLY = process.env.TS_ONLY ? new RegExp(process.env.TS_ONLY) : null;

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

async function demoBan(page, ip, on) {
  const path = on ? '_ban' : '_unban';
  const body = on ? { ref: `ip:${ip}`, duration_seconds: 3600, reason: 'demo caller, from the docs capture' } : { ref: `ip:${ip}` };
  await page.evaluate(
    async ({ base, path, body }) => {
      await fetch(`${base}/extensions/cloud-apim/extensions/waf/security/${path}`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
    },
    { base: BASE, path, body }
  );
}

async function shoot(page, name, { fullPage = false, settle = 900, before } = {}) {
  if (ONLY && !ONLY.test(name)) return;
  if (before) await before(page);
  // the analytics pages fire a burst of queries; wait for them to land, then a beat for the charts
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForTimeout(settle);
  const file = resolve(OUT, `${name}.png`);
  if (fullPage) {
    // not playwright's `fullPage`: it stitches a scrolled page with the sticky header and sidebar
    // frozen where the scroll left them, and the sidebar (100vh) stops at the first screen. A
    // window as tall as the page renders both where they belong.
    await page.evaluate(() => window.scrollTo(0, 0));
    const height = await page.evaluate(() => document.documentElement.scrollHeight);
    await page.setViewportSize({ width: VIEWPORT.width, height: Math.max(VIEWPORT.height, height) });
    await page.waitForTimeout(700); // the map follows its container with a resize observer
    await page.screenshot({ path: file });
    await page.setViewportSize(VIEWPORT);
  } else {
    await page.screenshot({ path: file });
  }
  console.log(`  ✓ ${name}.png${fullPage ? ' (full page)' : ''}`);
}

async function goto(page, path) {
  await page.goto(`${STUDIO}${path}`, { waitUntil: 'networkidle' });
}

/**
 * Every analytics capture is of the past hour, picked in the period selector like a user would: the
 * demo traffic is fresh there, where a week would average it into the quiet before it. Auto reload
 * is switched off first, so no refresh lands in the middle of a capture.
 */
async function lastHour(page) {
  const auto = page.locator('.refresh-control .toggle.on').first();
  if (await auto.count()) await auto.click();
  const hour = page.locator('.segmented button', { hasText: /^hour$/ }).first();
  await hour.waitFor({ state: 'visible', timeout: 15000 });
  if (!/active/.test((await hour.getAttribute('class')) || '')) {
    await hour.click();
  }
  await page.waitForLoadState('networkidle').catch(() => {});
}

async function clickTab(page, label) {
  await page.locator('.tabs button', { hasText: label }).first().click();
  await page.waitForLoadState('networkidle').catch(() => {});
}

async function clickSegment(page, label) {
  await page.locator('.segmented button', { hasText: new RegExp(`^${label}$`) }).first().click();
  await page.waitForLoadState('networkidle').catch(() => {});
}

/** Opens the drawer of the first row of the first table, and waits for its detail to land. */
async function openFirstRow(page) {
  const row = page.locator('table tbody tr').first();
  await row.waitFor({ state: 'visible', timeout: 15000 });
  await row.click();
  await page.waitForSelector('.drawer', { state: 'visible', timeout: 5000 }).catch(() => {});
  await page.waitForLoadState('networkidle').catch(() => {});
}

/** An Activity tab, on the past hour. */
function activity(ws, tab, then) {
  return async (page) => {
    await goto(page, `/workspaces/${ws}/activity`);
    await lastHour(page);
    if (tab) await clickTab(page, tab);
    if (then) await then(page);
  };
}

/** The Events page, on the past hour. */
function events(ws, then) {
  return async (page) => {
    await goto(page, `/workspaces/${ws}/events`);
    await lastHour(page);
    if (then) await then(page);
  };
}

/** The geography tab draws in webgl after its data: wait for the canvas, then for the geo lookups. */
async function mapDrawn(page) {
  await page.waitForSelector('.worldmap canvas', { state: 'visible', timeout: 15000 });
  await page.waitForLoadState('networkidle').catch(() => {});
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

  // the analytics, which is what the studio is really for — every tab, full page so every panel
  // shows, each on the past hour
  await shoot(page, 'studio-activity', { before: activity(ws), fullPage: true, settle: 1600 });
  await shoot(page, 'studio-activity-sources', { before: activity(ws, 'Sources'), fullPage: true, settle: 1600 });
  await shoot(page, 'studio-activity-geography', {
    before: activity(ws, 'Geography', mapDrawn),
    fullPage: true,
    settle: 2500,
  });
  // the same map as a globe, and a country picked on it: its sources and their networks
  await shoot(page, 'studio-activity-geography-globe', {
    before: activity(ws, 'Geography', async (p) => {
      await mapDrawn(p);
      await clickSegment(p, 'Globe');
    }),
    fullPage: true,
    settle: 2500,
  });
  await shoot(page, 'studio-activity-geography-country', {
    before: activity(ws, 'Geography', async (p) => {
      await mapDrawn(p);
      // the "By country" table is the first table of the tab, ranked, so its first row is the top country
      await p.locator('table tbody tr').first().click();
      await p.waitForLoadState('networkidle').catch(() => {});
    }),
    fullPage: true,
    settle: 2500,
  });
  await shoot(page, 'studio-activity-detectors', { before: activity(ws, 'Detectors'), fullPage: true, settle: 1600 });
  await shoot(page, 'studio-activity-routes', { before: activity(ws, 'Routes'), fullPage: true, settle: 1600 });
  await shoot(page, 'studio-activity-waf', { before: activity(ws, 'WAF'), fullPage: true, settle: 1600 });
  await shoot(page, 'studio-activity-consumers', { before: activity(ws, 'Consumers'), fullPage: true, settle: 1600 });

  // the rows themselves, with the signals behind each decision
  await shoot(page, 'studio-events', { before: events(ws), settle: 1400 });
  await shoot(page, 'studio-events-enforced', { before: events(ws, (p) => clickSegment(p, 'Enforced')), settle: 1400 });
  // one decision opened: where it came from, what it did, and why
  await shoot(page, 'studio-events-decision', { before: events(ws, openFirstRow), settle: 1400 });
  // the waf trail, all of it, then only what a monitoring ruleset would have blocked, then one opened
  await shoot(page, 'studio-events-waf', { before: events(ws, (p) => clickTab(p, 'WAF trail')), settle: 1400 });
  await shoot(page, 'studio-events-waf-would-block', {
    before: events(ws, async (p) => {
      await clickTab(p, 'WAF trail');
      await clickSegment(p, 'Would have blocked');
    }),
    settle: 1400,
  });
  await shoot(page, 'studio-events-waf-detail', {
    before: events(ws, async (p) => {
      await clickTab(p, 'WAF trail');
      // a request a monitoring ruleset would have refused: the verdict and the rules that got it there
      await clickSegment(p, 'Would have blocked');
      await openFirstRow(p);
    }),
    settle: 1400,
  });

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

  // the WAF tuning assistant, opened on the first candidate
  await shoot(page, 'studio-waf-tuning', {
    before: async (p) => {
      await goto(p, w('/waf'));
      await p.waitForLoadState('networkidle').catch(() => {});
      // switch to the Tuning tab
      await p.evaluate(() => {
        const t = [...document.querySelectorAll('.tabs button')].find((b) => /Tuning/.test(b.textContent));
        t && t.click();
      });
      await p.waitForTimeout(800);
      // open the first candidate's assistant
      const tune = p.locator('table tbody tr button', { hasText: 'Tune' }).first();
      if (await tune.count()) {
        await tune.click();
        await p.waitForSelector('.drawer', { state: 'visible', timeout: 5000 }).catch(() => {});
        await p.waitForTimeout(900);
      }
    },
    settle: 900,
  });

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

  // bans & incidents, on the install-wide tab, with one demo ban so the page is not empty
  const demoIp = '198.51.100.200';
  if (!ONLY || ONLY.test('studio-incidents')) try {
    await goto(page, w('/incidents'));
    await demoBan(page, demoIp, true);
    await goto(page, w('/incidents'));
    await page.evaluate(() => {
      const t = [...document.querySelectorAll('.tabs button')].find((b) => /Live state/.test(b.textContent));
      t && t.click();
    });
    await page.waitForTimeout(1000);
    await shoot(page, 'studio-incidents', {});
  } finally {
    await demoBan(page, demoIp, false);
  }

  await browser.close();
  console.log(`\nDone. Wrote to ${OUT}`);
};

run().catch((err) => {
  console.error(err);
  process.exit(1);
});
