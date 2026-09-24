#!/usr/bin/env bun
/**
 * Demo traffic for Threat Studio.
 *
 * Keeps a conference stand supplied with fresh analytics: a slow, steady mix of ordinary browsing
 * and the requests the suite exists to catch, so every panel of the console has something real to
 * show — the enforcement rate, the dry-run gap, the detector split, the source ranking and the
 * incident console.
 *
 * Deliberately quiet, and uneven: each minute draws its own small number of requests, so it goes
 * three, then one, then none, then two — which is both what real low traffic looks like and slow
 * enough that a visitor can watch one decision land while you talk.
 *
 * Everything it does is printed as it happens: the draw for the minute, then one line per request
 * with the caller, the country, the path, the status and what the gateway made of it.
 *
 *   bun scripts/demo-traffic.js           # averages 1.5 requests a minute, until ctrl-c
 *   VOLUME=4 bun scripts/demo-traffic.js  # four times that
 *   VOLUME=0.3 bun scripts/demo-traffic.js  # barely ticking over
 *   bun scripts/demo-traffic.js --warm    # seed some history first, so nothing is empty at open
 *
 * The volume is also a dial while it runs: `+` and `-` scale it, `0` returns to the baseline, `b`
 * fires a burst now, `s` sweeps the demo bans, `q` stops. Turning the dial re-draws the rest of the
 * current minute, so it takes effect while you are still pointing at the screen.
 *
 * Every caller address is spoofed through `X-Forwarded-For`, which otoroshi trusts by default when
 * no trusted proxies are configured. Bans and incidents therefore land on invented callers and
 * never on the machine running the demo.
 *
 * The target must resolve to this machine. Pointing this at anything else is refused: the payloads
 * below are meant for your own console, and nobody else's.
 */

import { lookup } from 'node:dns/promises';

const TARGET = (process.env.TARGET || 'http://security-suite.oto.tools:9999').replace(/\/$/, '');
/** Average requests per *minute* — the mean of the per-minute draw, not a fixed pace. */
const RATE = Math.max(0, Number(process.env.RATE ?? 1.5));
/**
 * Minutes between two sweeps that lift the bans this script earned.
 *
 * Without it a stand that runs all day ends with every demo caller held and nothing but 403s on
 * screen. Only the addresses listed below are ever released — a ban you placed by hand while
 * demonstrating is left exactly where it is. `RECYCLE=0` turns the sweep off.
 */
const RECYCLE = Math.max(0, Number(process.env.RECYCLE ?? 15));
/** Where the suite's own endpoints live — the otoroshi admin host, not the proxied demo route. */
const ADMIN = (process.env.ADMIN || 'http://otoroshi.oto.tools:9999').replace(/\/$/, '');
const WARM = process.argv.includes('--warm');

// ------------------------------------------------------------------------------------------------
// the target has to be local
// ------------------------------------------------------------------------------------------------

const { hostname } = new URL(TARGET);
const resolved = await lookup(hostname).catch(() => null);
if (!resolved) {
  console.error(`cannot resolve ${hostname}`);
  process.exit(1);
}
if (!(resolved.address.startsWith('127.') || resolved.address === '::1')) {
  console.error(`${hostname} resolves to ${resolved.address}, which is not this machine.`);
  console.error('this script only drives a local otoroshi.');
  process.exit(1);
}

// ------------------------------------------------------------------------------------------------
// the callers
// ------------------------------------------------------------------------------------------------

/**
 * Addresses picked so the ASN database resolves each of them to a real network in a different
 * country — which is what puts a spread of flags in the console rather than one column of the same
 * one. The country and network on each line are what `reputation/_geo` actually answers for it,
 * and they are carried here only so this script's own log can show them too.
 */
const HOMES = [
  ['92.184.100.5', 'FR', 'Orange'],
  ['82.64.12.9', 'FR', 'Free'],
  ['2.3.44.1', 'FR', 'Orange'],
  ['86.1.44.7', 'GB', 'NTL'],
  ['81.2.69.142', 'GB', 'Andrews & Arnold'],
  ['145.53.1.7', 'NL', 'KPN'],
  ['49.36.1.9', 'IN', 'Reliance Jio'],
  ['177.54.1.9', 'BR', 'Unifique'],
  ['189.6.44.7', 'BR', 'Claro'],
  ['95.173.1.9', 'TR', 'Türk Telekom'],
  ['200.40.1.9', 'UY', 'Antel'],
  ['190.2.44.7', 'AR', 'NSS'],
  ['105.235.44.7', 'CI', 'MTN'],
  ['41.77.1.9', 'ZM', 'Zain'],
];

/** Hosting and cloud networks: the ASN classification scores these, so they carry a head start. */
const CLOUDS = [
  ['3.87.44.2', 'US', 'Amazon'],
  ['52.14.9.7', 'US', 'Amazon'],
  ['104.131.3.4', 'US', 'DigitalOcean'],
  ['178.62.44.7', 'US', 'DigitalOcean'],
  ['5.9.100.20', 'DE', 'Hetzner'],
  ['88.198.44.7', 'DE', 'Hetzner'],
  ['176.31.200.7', 'FR', 'OVH'],
  ['51.15.44.9', 'FR', 'Scaleway'],
  ['217.160.0.50', 'DE', 'IONOS'],
  ['45.33.20.100', 'SG', 'Akamai'],
  ['1.12.44.7', 'CN', 'Tencent'],
  ['117.50.3.9', 'CN', 'China Mobile'],
  ['218.92.0.5', 'CN', 'Chinanet'],
  ['36.110.1.7', 'CN', 'CNIX'],
  ['185.220.101.5', 'DE', 'Tor exit'],
];

/**
 * Real crawler addresses, so the bot policy's reverse-dns verification actually passes: these
 * resolve to `crawl-…googlebot.com` and `msnbot-…search.msn.com`, which is exactly the check the
 * policy runs. They are the counterpart of the impersonator below — same user agent, one of them
 * provably the real thing — and that pair is the whole demonstration of bot verification.
 */
const SEARCH_BOTS = [
  ['66.249.66.1', 'US', 'Google', 'Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)'],
  ['40.77.167.1', 'US', 'Microsoft', 'Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)'],
  ['207.46.13.1', 'US', 'Microsoft', 'Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)'],
];

const OTHER_BOTS = [
  'Mozilla/5.0 (compatible; GPTBot/1.1; +https://openai.com/gptbot)',
  'Mozilla/5.0 (compatible; ClaudeBot/1.0; +claudebot@anthropic.com)',
  'Mozilla/5.0 (compatible; PerplexityBot/1.0; +https://perplexity.ai/perplexitybot)',
  'Mozilla/5.0 (compatible; AhrefsBot/7.0; +http://ahrefs.com/robot/)',
  'Mozilla/5.0 (compatible; SemrushBot/7~bl; +http://www.semrush.com/bot.html)',
  'Mozilla/5.0 (compatible; MJ12bot/v1.4.8; http://mj12bot.com/)',
];

const BROWSERS = [
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36',
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0 Safari/537.36',
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1',
  'Mozilla/5.0 (X11; Linux x86_64; rv:129.0) Gecko/20100101 Firefox/129.0',
];

// ------------------------------------------------------------------------------------------------
// what they ask for
// ------------------------------------------------------------------------------------------------

/** The shape of an api that is actually being used. Nothing here trips anything. */
const NORMAL = [
  '/',
  '/index.html',
  '/api/products',
  '/api/products/42',
  '/api/products?page=2&per_page=20',
  '/api/orders?status=shipped',
  '/api/customers/8821',
  '/api/search?q=running+shoes',
  '/api/search?q=winter%20jacket&sort=price',
  '/static/app.css',
  '/static/app.js',
  '/favicon.ico',
  '/health',
];

/**
 * The canonical strings a ruleset is tested with — the ones in the CRS documentation and in every
 * waf vendor's own demo. They exist to make a rule fire against the echo backend, which is all this
 * needs: the console is showing which rules matched, not exploiting anything.
 */
const TRIPS = [
  "/api/products?id=1' OR '1'='1",
  '/api/search?q=<script>alert(1)</script>',
  '/api/search?q=<img src=x onerror=alert(1)>',
  '/api/files?name=../../../../etc/passwd',
  '/api/products?id=1 UNION SELECT null,null,null--',
  '/api/orders?sort=id;DROP TABLE orders--',
  '/api/report?cmd=|cat /etc/passwd',
  '/api/products?id=1%27%20AND%20SLEEP(5)--',
];

/** Paths an automated crawler tries on everything it finds. Against the echo backend they are 200s. */
const PROBES = [
  '/.env',
  '/.git/config',
  '/wp-login.php',
  '/wp-admin/',
  '/admin',
  '/administrator/index.php',
  '/phpmyadmin/',
  '/config.json',
  '/backup.sql',
  '/.aws/credentials',
  '/server-status',
  '/actuator/env',
  '/api/v1/debug',
  '/cgi-bin/test.cgi',
];

// ------------------------------------------------------------------------------------------------
// personas
// ------------------------------------------------------------------------------------------------

const pick = (xs) => xs[Math.floor(Math.random() * xs.length)];

/**
 * Weighted so most of what the console sees is ordinary. A demo where everything is an attack shows
 * a 100% enforcement rate and an empty "let through" band, which is exactly the picture the product
 * is arguing against.
 */
const PERSONAS = [
  { name: 'visitor', weight: 55, build: visitor },
  { name: 'crawler', weight: 12, build: crawler },
  { name: 'integration', weight: 10, build: integration },
  { name: 'scanner', weight: 12, build: scanner },
  { name: 'attacker', weight: 8, build: attacker },
  { name: 'impersonator', weight: 3, build: impersonator },
];

const TOTAL_WEIGHT = PERSONAS.reduce((a, p) => a + p.weight, 0);

function choosePersona() {
  let n = Math.random() * TOTAL_WEIGHT;
  for (const p of PERSONAS) {
    n -= p.weight;
    if (n <= 0) return p;
  }
  return PERSONAS[0];
}

/** Someone reading the site from a home connection. */
function visitor() {
  return { caller: pick(HOMES), ua: pick(BROWSERS), path: pick(NORMAL), note: 'ordinary browsing' };
}

/** A declared bot. The demo policy allows search engines and monitors the rest. */
function crawler() {
  if (Math.random() < 0.35) {
    const [ip, cc, org, ua] = pick(SEARCH_BOTS);
    return { caller: [ip, cc, org], ua, path: pick(['/robots.txt', '/sitemap.xml', ...NORMAL]), note: 'declared search bot' };
  }
  const ua = pick(OTHER_BOTS);
  const name = (ua.match(/(GPTBot|ClaudeBot|PerplexityBot|AhrefsBot|SemrushBot|MJ12bot)/i) || [])[1] || 'bot';
  return { caller: pick(CLOUDS), ua, path: pick(['/robots.txt', '/llms.txt', ...NORMAL]), note: `${name}` };
}

/** A server-to-server client: a hosting network, a plain user agent, ordinary calls. */
function integration() {
  return {
    caller: pick(CLOUDS),
    ua: pick(['python-requests/2.32.3', 'node-fetch/3.3.2', 'Go-http-client/2.0', 'curl/8.7.1']),
    path: pick(['/api/orders', '/api/products', '/api/customers/8821', '/health']),
    note: 'server to server',
  };
}

/** Walking the surface: many different paths from one address, which is the shape of a scan. */
function scanner() {
  return {
    caller: pick(CLOUDS),
    ua: pick(['python-requests/2.32.3', 'Mozilla/5.0 zgrab/0.x', 'masscan/1.3', 'curl/8.7.1']),
    path: pick(PROBES),
    note: 'probing for exposed files',
  };
}

/** The requests the WAF is there for. */
function attacker() {
  const path = pick(TRIPS);
  const kind = /union|OR '1'|SLEEP|DROP/i.test(path)
    ? 'sql injection'
    : /<script|onerror/i.test(path)
      ? 'cross site scripting'
      : /\.\.\//.test(path)
        ? 'path traversal'
        : 'command injection';
  return { caller: pick(CLOUDS), ua: pick([...BROWSERS, 'sqlmap/1.8#stable']), path, note: kind };
}

/** A search engine user agent from an address that is plainly not the search engine. */
function impersonator() {
  return {
    caller: pick(CLOUDS),
    ua: 'Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)',
    path: pick(NORMAL),
    note: 'claims to be googlebot',
  };
}

// ------------------------------------------------------------------------------------------------
// the loop
// ------------------------------------------------------------------------------------------------

/** Every address this script ever speaks as — what the ban sweep is allowed to touch. */
const CALLERS = [
  ...new Set([...HOMES.map(([ip]) => ip), ...CLOUDS.map(([ip]) => ip), ...SEARCH_BOTS.map(([ip]) => ip)]),
];

const tally = { sent: 0, ok: 0, denied: 0, failed: 0, recycled: 0, byPersona: {}, slowest: 0 };
let inFlight = 0;

// ------------------------------------------------------------------------------------------------
// the log
// ------------------------------------------------------------------------------------------------
//
// The point of the stand is to narrate: something leaves here, and a second later it is a row in the
// console. So every request is printed as it happens, with what it was meant to be and what the
// gateway answered, and the two can be read side by side.

const C = process.stdout.isTTY
  ? { dim: '\x1b[2m', red: '\x1b[31m', amber: '\x1b[33m', green: '\x1b[32m', bold: '\x1b[1m', off: '\x1b[0m' }
  : { dim: '', red: '', amber: '', green: '', bold: '', off: '' };

const clock = () => new Date().toTimeString().slice(0, 8);

const flag = (cc) =>
  cc && cc.length === 2
    ? String.fromCodePoint(0x1f1e6 + cc.charCodeAt(0) - 65, 0x1f1e6 + cc.charCodeAt(1) - 65)
    : '  ';

const pad = (s, n) => (s.length > n ? `${s.slice(0, n - 1)}…` : s.padEnd(n));

/** Colour by what the gateway did, not by what the request was: that is the interesting half. */
function paint(status) {
  if (status === 0) return `${C.dim}  ×${C.off}`;
  if (status >= 500) return `${C.red}${status}${C.off}`;
  if (status === 403 || status === 429) return `${C.red}${status}${C.off}`;
  if (status >= 400) return `${C.amber}${status}${C.off}`;
  return `${C.green}${status}${C.off}`;
}

/** What the gateway's answer means here, in the suite's own vocabulary. */
function verdict(status, elapsed) {
  if (status === 0) return 'no answer';
  if (status === 403) return 'denied';
  if (status === 429) return 'rate limited';
  if (elapsed > 1500) return `tarpitted ${(elapsed / 1000).toFixed(1)}s`;
  return 'let through';
}

async function fire() {
  // a tarpit tier holds a request for a few seconds; without a ceiling those pile up
  if (inFlight > 40) return;
  const persona = choosePersona();
  const { caller, ua, path, note } = persona.build();
  const [ip, cc, org] = caller;
  inFlight += 1;
  tally.sent += 1;
  tally.byPersona[persona.name] = (tally.byPersona[persona.name] || 0) + 1;
  const started = Date.now();
  let status = 0;
  try {
    const res = await fetch(TARGET + path, {
      headers: { 'X-Forwarded-For': ip, 'User-Agent': ua, Accept: '*/*' },
      redirect: 'manual',
      signal: AbortSignal.timeout(20000),
    });
    status = res.status;
    await res.arrayBuffer();
    if (status === 403 || status === 429) tally.denied += 1;
    else tally.ok += 1;
  } catch (e) {
    tally.failed += 1;
  } finally {
    inFlight -= 1;
  }
  const elapsed = Date.now() - started;
  tally.slowest = Math.max(tally.slowest, elapsed);
  console.log(
    `${C.dim}${clock()}${C.off} ${paint(status)} ${pad(persona.name, 13)}` +
      `${flag(cc)} ${pad(ip, 15)} ${C.dim}${pad(org, 14)}${C.off}` +
      `GET ${pad(decodeURIComponent(path), 44)} ${C.dim}${verdict(status, elapsed)}, ${note}${C.off}`
  );
}

function line() {
  const mix = Object.entries(tally.byPersona)
    .sort((a, b) => b[1] - a[1])
    .map(([k, v]) => `${k} ${v}`)
    .join('  ');
  return (
    `${C.bold}${clock()} — ${tally.sent} sent · ${tally.ok} through · ${tally.denied} denied · ` +
    `${tally.failed} failed · ${tally.recycled} bans lifted${C.off}\n         ${C.dim}${mix}${C.off}`
  );
}

/**
 * Lifts the bans held against this script's own callers, and nothing else.
 *
 * Reads the ban list first and only releases what is actually held. That is not an optimisation:
 * `_unban` answers `done: true` whether or not the identity was banned — it reports that the delete
 * went through, not that anything was there — so counting its answers would claim a sweep released
 * thirty addresses every time it ran.
 *
 * It releases one identity at a time on purpose: the endpoint also understands `{"all": true}`,
 * which would wipe a ban you placed by hand mid-demonstration, so this never calls it.
 */
async function recycle() {
  const security = `${ADMIN}/extensions/cloud-apim/extensions/waf/security`;
  const mine = new Set(CALLERS.map((ip) => `ip:${ip}`));
  const lifted = [];
  try {
    const res = await fetch(`${security}/_bans`, { signal: AbortSignal.timeout(10000) });
    const held = (await res.json()).bans || [];
    for (const ban of held.filter((b) => mine.has(b.key))) {
      await fetch(`${security}/_unban`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ref: ban.key }),
        signal: AbortSignal.timeout(10000),
      });
      lifted.push(ban.key.slice(3));
      tally.recycled += 1;
    }
  } catch (e) {
    console.log(`${C.dim}${clock()} — ban sweep failed: ${e.message}${C.off}`);
    return;
  }
  console.log(
    lifted.length === 0
      ? `${C.dim}${clock()} — ban sweep: nothing held against the demo callers${C.off}`
      : `${C.dim}${clock()} — ban sweep: released ${lifted.length} (${lifted.join(', ')})${C.off}`
  );
}

// ------------------------------------------------------------------------------------------------
// volume
// ------------------------------------------------------------------------------------------------
//
// `RATE` is the baseline in requests per minute; `volume` is the dial on top of it, so the same
// script covers "barely ticking over while nobody is at the stand" and "make the graph move, I am
// explaining the graph" without stopping and restarting anything.

const MIN_VOLUME = 0.1;
const MAX_VOLUME = 40;
const STEP = 1.5;

let volume = Math.min(MAX_VOLUME, Math.max(MIN_VOLUME, Number(process.env.VOLUME ?? 1)));

const effectiveRate = () => RATE * volume;

const describeVolume = () =>
  `volume ×${volume < 1 ? volume.toFixed(2) : volume.toFixed(1)} → ${effectiveRate().toFixed(1)} req/min on average`;

/**
 * How many requests a window gets.
 *
 * Drawn per minute rather than paced, because a fixed interval draws a flat comb across the time
 * series and the hour grid, which reads as a generator the moment anyone looks at it. A Poisson
 * draw around the average gives what real low traffic looks like: three requests one minute, one
 * the next, none the minute after.
 *
 * `share` is the fraction of a minute being drawn for, so that re-drawing the tail of a minute
 * after the dial moves asks for the right amount rather than a whole minute's worth.
 */
function requestsFor(share = 1) {
  const lambda = effectiveRate() * share;
  if (lambda <= 0) return 0;
  const limit = Math.exp(-lambda);
  let k = 0;
  let p = 1;
  do {
    k += 1;
    p *= Math.random();
  } while (p > limit);
  return k - 1;
}

let minute = null;
let report = null;
let sweeper = null;
let stopping = false;
let minuteStartedAt = Date.now();
const pending = new Set();

function quit() {
  stopping = true;
  clearInterval(minute);
  clearInterval(report);
  clearInterval(sweeper);
  pending.forEach(clearTimeout);
  if (process.stdin.isTTY) process.stdin.setRawMode(false);
  console.log(`\n${line()}\nstopped.`);
  process.exit(0);
}

// registered before anything starts, so ctrl-c also works during the seeding phase
for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, quit);

const KEYS = 'keys: + louder · - quieter · 0 baseline · b burst · s sweep bans · q quit';

console.log(`demo traffic → ${TARGET} (${resolved.address})`);
console.log(
  `${describeVolume()}, drawn fresh each minute` +
    `${RECYCLE ? `, bans recycled every ${RECYCLE}min` : ''}`
);
// only advertised when there is a terminal to press them on: piped to a file or run under nohup,
// stdin is not a tty and the dial is whatever VOLUME was set to
if (process.stdin.isTTY) console.log(`${C.dim}${KEYS}${C.off}`);
console.log('callers are spoofed addresses; nothing here leaves this machine.\n');

if (WARM) {
  // enough history that no panel is empty when the first visitor walks up. slow enough that it
  // reads as traffic rather than as one spike, and that the score ledger does not ban the whole
  // caller pool in the first ten seconds
  const burst = 120;
  process.stdout.write(`seeding ${burst} requests, about a minute… `);
  for (let i = 0; i < burst && !stopping; i += 1) {
    fire();
    await Bun.sleep(400);
  }
  console.log('done\n');
}

/** Scatters `count` requests at random points across the next `window` milliseconds. */
function scatter(count, window) {
  for (let i = 0; i < count; i += 1) {
    const handle = setTimeout(() => {
      pending.delete(handle);
      fire();
    }, Math.random() * window);
    pending.add(handle);
  }
}

/** Draws this minute's share and scatters it across the sixty seconds. */
function runMinute() {
  minuteStartedAt = Date.now();
  const count = requestsFor();
  console.log(
    count === 0
      ? `${C.dim}${clock()} — quiet minute${C.off}`
      : `${C.dim}${clock()} — ${count} request${count === 1 ? '' : 's'} this minute${C.off}`
  );
  scatter(count, 60000);
}

/**
 * Re-draws whatever is left of the current minute at the new volume.
 *
 * Without this the dial would only take effect at the next minute boundary, which on a stand is
 * both too slow to demonstrate and impossible to narrate — you turn it up and nothing happens for
 * fifty seconds.
 */
function setVolume(next, why) {
  volume = Math.min(MAX_VOLUME, Math.max(MIN_VOLUME, next));
  const remaining = Math.max(0, 60000 - (Date.now() - minuteStartedAt));
  pending.forEach(clearTimeout);
  pending.clear();
  const count = requestsFor(remaining / 60000);
  scatter(count, remaining);
  console.log(
    `${C.bold}${clock()} — ${why}: ${describeVolume()}${C.off}` +
      `${C.dim} · ${count} request${count === 1 ? '' : 's'} left in this minute${C.off}`
  );
}

/**
 * The stand controls.
 *
 * Raw mode means ctrl-c no longer arrives as a signal, so it is handled here by hand — forget that
 * and the script becomes unkillable from its own terminal.
 */
function listenForKeys() {
  if (!process.stdin.isTTY) return;
  process.stdin.setRawMode(true);
  process.stdin.resume();
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', (key) => {
    if (key === '\u0003' || key === 'q') return quit();
    if (key === '+' || key === '=') return setVolume(volume * STEP, 'louder');
    if (key === '-' || key === '_') return setVolume(volume / STEP, 'quieter');
    if (key === '0') return setVolume(1, 'back to baseline');
    if (key === 'b') {
      // a handful right now, for when someone is watching the console and you want it to move
      const count = 8;
      console.log(`${C.bold}${clock()} — burst of ${count}${C.off}`);
      scatter(count, 4000);
      return;
    }
    if (key === 's') return recycle();
    if (key === '?' || key === 'h') return console.log(`${C.dim}${KEYS}${C.off}`);
  });
}

runMinute();
listenForKeys();
minute = setInterval(runMinute, 60000);
report = setInterval(() => console.log(line()), 600000);
if (RECYCLE) sweeper = setInterval(recycle, RECYCLE * 60000);
