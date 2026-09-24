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

function flush() {
  const batch = pending;
  pending = new Map();
  timer = null;
  const ips = [...batch.keys()];
  Reputation.geo({ ips })
    .then((res) => {
      const results = (res && res.results) || {};
      batch.forEach((resolve, ip) => resolve(decorate(results[ip])));
    })
    .catch(() => {
      // a failed call should not pin every address to "unknown" for the rest of the session
      batch.forEach((resolve, ip) => {
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
