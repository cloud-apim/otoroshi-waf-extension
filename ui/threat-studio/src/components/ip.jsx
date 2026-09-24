import { useEffect, useState } from 'react';
import { describeGeo, ipOfIdentity, lookupGeo } from '../lib/geo';

export function useGeo(ip) {
  const [geo, setGeo] = useState(null);
  useEffect(() => {
    let alive = true;
    setGeo(null);
    if (ip) lookupGeo(ip).then((g) => alive && setGeo(g));
    return () => {
      alive = false;
    };
  }, [ip]);
  return geo;
}

/**
 * An address, or an identity key (`ip:…`), with the flag of the country its network is registered
 * in and the network itself on hover. Anything that is not an address renders as plain text.
 */
export function IpAddress({ value, fallback = '—' }) {
  const geo = useGeo(ipOfIdentity(value));
  if (!value) return fallback;
  return (
    <span className="ip-address" title={describeGeo(geo) || undefined}>
      {geo && geo.flag && <span className="flag" aria-hidden="true">{geo.flag}</span>}
      <span className="mono">{value}</span>
    </span>
  );
}

/** The `dt`/`dd` rows a drawer adds under an address: country and network, when known. */
export function GeoRows({ value }) {
  const geo = useGeo(ipOfIdentity(value));
  if (!geo) return null;
  return (
    <>
      {geo.country && (
        <>
          <dt>Country</dt>
          <dd>
            {geo.flag} {geo.countryName}
          </dd>
        </>
      )}
      {(geo.org || geo.asn) && (
        <>
          <dt>Network</dt>
          <dd>
            {geo.org || '—'}
            {geo.asn ? <span className="faint"> · AS{geo.asn}</span> : null}
          </dd>
        </>
      )}
    </>
  );
}
