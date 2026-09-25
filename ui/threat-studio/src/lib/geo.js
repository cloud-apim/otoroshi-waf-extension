import { Reputation } from './security';

// Where an address comes from, as the ASN databases know it: the network, its AS number and the
// country it is registered in. Display only — the score never reads this.
//
// A table of callers asks for a page worth of addresses at once, so lookups are coalesced: every
// address requested in the same tick goes out in one call, and the answer is kept for the session.
// An address no database knows is cached as `null` too, so it is never asked for twice.

const cache = new Map(); // ip -> Promise<geo | null>
let pending = new Map(); // ip -> resolve
let timer = null;

// the endpoint answers for at most 500 addresses per call and silently drops the rest
const MAX_PER_CALL = 500;

function flush() {
  const batch = [...pending.entries()];
  pending = new Map();
  timer = null;
  for (let i = 0; i < batch.length; i += MAX_PER_CALL) ask(batch.slice(i, i + MAX_PER_CALL));
}

function ask(chunk) {
  Reputation.geo({ ips: chunk.map(([ip]) => ip) })
    .then((res) => {
      const results = (res && res.results) || {};
      chunk.forEach(([ip, resolve]) => resolve(decorate(results[ip])));
    })
    .catch(() => {
      // a failed call should not pin every address to "unknown" for the rest of the session
      chunk.forEach(([ip, resolve]) => {
        cache.delete(ip);
        resolve(null);
      });
    });
}

export function lookupGeo(ip) {
  if (!ip) return Promise.resolve(null);
  if (!cache.has(ip)) {
    cache.set(ip, new Promise((resolve) => pending.set(ip, resolve)));
    if (!timer) timer = setTimeout(flush, 20);
  }
  return cache.get(ip);
}

/** `ip:203.0.113.10` → `203.0.113.10`; any other identity kind (apikey, user…) → null. */
export function ipOfIdentity(key) {
  if (!key) return null;
  const idx = key.indexOf(':');
  if (idx < 0) return looksLikeIp(key) ? key : null;
  const kind = key.substring(0, idx);
  if (kind === 'ip') return key.substring(idx + 1);
  // a bare IPv6 address has colons too
  return looksLikeIp(key) ? key : null;
}

function looksLikeIp(s) {
  return /^\d{1,3}(\.\d{1,3}){3}$/.test(s) || (/^[0-9a-fA-F:]+$/.test(s) && s.includes(':'));
}

// regional indicator symbols: FR → 🇫🇷
export function flagOf(country) {
  if (!country || !/^[A-Za-z]{2}$/.test(country)) return '';
  const cc = country.toUpperCase();
  return String.fromCodePoint(0x1f1e6 + cc.charCodeAt(0) - 65, 0x1f1e6 + cc.charCodeAt(1) - 65);
}

let regionNames = null;
try {
  regionNames = new Intl.DisplayNames(['en'], { type: 'region' });
} catch (e) {
  regionNames = null;
}

export function countryName(country) {
  if (!country) return '';
  try {
    return (regionNames && regionNames.of(country.toUpperCase())) || country;
  } catch (e) {
    return country;
  }
}

function decorate(raw) {
  if (!raw || (!raw.country && !raw.org)) return null;
  // iptoasn marks unregistered space as `None`
  const country = raw.country && raw.country !== 'None' ? raw.country : '';
  return {
    asn: raw.asn,
    org: raw.org || '',
    country,
    countryName: countryName(country),
    flag: flagOf(country),
  };
}

/** "France · Clever Cloud SAS (AS213394)" */
export function describeGeo(geo) {
  if (!geo) return '';
  const network = geo.org ? `${geo.org}${geo.asn ? ` (AS${geo.asn})` : ''}` : geo.asn ? `AS${geo.asn}` : '';
  return [geo.countryName, network].filter(Boolean).join(' · ');
}

/**
 * Rows keyed by address (`key`, plus numeric columns) folded into one row per country.
 *
 * The same lookup the address cells use, so the map and the flags next to an address never
 * disagree. Addresses no database knows land in `unknown` rather than being dropped, so the share
 * the map cannot place stays visible.
 */
export async function foldByCountry(rows) {
  const geos = await Promise.all(rows.map((row) => lookupGeo(ipOfIdentity(row.key))));
  const countries = new Map();
  const unknown = { sources: 0, decisions: 0, enforced: 0 };
  rows.forEach((row, i) => {
    const geo = geos[i];
    const decisions = Number(row.decisions) || 0;
    const enforced = Number(row.enforced) || 0;
    if (!geo || !geo.country) {
      unknown.sources += 1;
      unknown.decisions += decisions;
      unknown.enforced += enforced;
      return;
    }
    const cc = geo.country.toUpperCase();
    const c = countries.get(cc) || {
      key: cc,
      country: cc,
      name: geo.countryName,
      flag: geo.flag,
      sources: 0,
      decisions: 0,
      enforced: 0,
      max_score: 0,
      top: [],
    };
    c.sources += 1;
    c.decisions += decisions;
    c.enforced += enforced;
    c.max_score = Math.max(c.max_score, Number(row.max_score) || 0);
    c.top.push({ ...row, geo });
    countries.set(cc, c);
  });
  const list = [...countries.values()].sort((a, b) => b.decisions - a.decisions);
  list.forEach((c) => c.top.sort((a, b) => (Number(b.decisions) || 0) - (Number(a.decisions) || 0)));
  return { countries: list, unknown };
}
