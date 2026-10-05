import { useEffect, useState } from 'react';
import { describeGeo, getGeoAttributions, ipOfIdentity, lookupGeo, onGeoAttributions } from '../lib/geo';

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

export function useGeoAttributions() {
  const [list, setList] = useState(getGeoAttributions());
  useEffect(() => onGeoAttributions(setList), []);
  return list;
}

/** "IP Geolocation by DB-IP": the credit the free databases ask for, wherever a location is shown. */
export function GeoAttribution({ style }) {
  const list = useGeoAttributions();
  if (!list.length) return null;
  return (
    <div className="faint geo-attribution" style={{ fontSize: 11, marginTop: 8, ...style }}>
      {list.map((a, i) => (
        <span key={a.text}>
          {i > 0 && ' · '}
          {a.url ? (
            <a href={a.url} target="_blank" rel="noreferrer">
              {a.text}
            </a>
          ) : (
            a.text
          )}
        </span>
      ))}
    </div>
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
            {geo.country && !geo.located ? <span className="faint" title="No geolocation database knows this address: this is the country its network is registered in"> · registration</span> : null}
          </dd>
        </>
      )}
      {geo.city && (
        <>
          <dt>City</dt>
          <dd>{geo.city}</dd>
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
