import { useEffect, useState } from 'react';
import { EntitySection } from '../components/entities';
import { Icon } from '../components/icons';
import { GeoAttribution } from '../components/ip';
import { Badge, Card, PageHeader, TextInput, useAsync, useToast } from '../components/ui';
import { fmtDate, fmtDay } from '../lib/format';
import { Resources } from '../lib/entities';
import { countryName, flagOf, setGeoAttributions } from '../lib/geo';
import { Reputation } from '../lib/security';

/**
 * Geolocation belongs to the install: every node downloads the databases and memory-maps them.
 *
 * What they back is `@geoLookup` and the `GEO` collection in WAF rules, and the location the
 * consoles show next to an address.
 */
export function GeoPage() {
  const toast = useToast();
  const dbs = useAsync(() => Resources.geoDatabases.list(), []);
  const status = useAsync(() => Reputation.status().catch(() => null), []);
  const [ip, setIp] = useState('');
  const [found, setFound] = useState(undefined);

  const byId = ((status.data && status.data.geo) || []).reduce((acc, g) => ({ ...acc, [g.id]: g.snapshot }), {});

  // a database is fetched by the node in the background, a few seconds after it is created or after
  // a restart: keep asking until every enabled one has loaded or failed, so "pending" does not stay
  const pending = (dbs.data || []).some((e) => e.enabled && !byId[e.id]) && !status.loading;
  useEffect(() => {
    if (!pending) return undefined;
    const timer = setInterval(() => status.reload({ silent: true }), 3000);
    return () => clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pending]);

  const refresh = () =>
    Reputation.refresh({ geo: true })
      .then((r) => {
        if (r && r.done) toast.success('Databases checked');
        else toast.error((r && r.error) || 'A database could not be refreshed, see its status');
        status.reload();
      })
      .catch(toast.error);

  const locate = () => {
    const address = ip.trim();
    Reputation.geo({ ips: [address] })
      .then((r) => {
        setGeoAttributions(r && r.attributions);
        setFound((r && r.results && r.results[address]) || null);
      })
      .catch(toast.error);
  };

  return (
    <div className="content wide">
      <PageHeader
        title="Geolocation"
        description="Where an address is, from a MaxMind DB file each node downloads and memory-maps. It backs @geoLookup and the GEO collection in WAF rules, and the location shown next to every address."
      >
        <button className="btn" onClick={refresh}>
          <Icon name="refresh" /> Check now
        </button>
      </PageHeader>

      <Card style={{ marginBottom: 18 }} title="Locate one address" description="What the loaded databases say, right now.">
        <div className="row" style={{ gap: 8 }}>
          <TextInput value={ip} onChange={setIp} placeholder="203.0.113.10" style={{ maxWidth: 240 }} />
          <button className="btn" onClick={locate} disabled={!ip.trim()}>
            Locate
          </button>
          {found === null && <span className="muted">No database knows this address.</span>}
          {found && (
            <span>
              {flagOf(found.country)} {[found.city, countryName(found.country)].filter(Boolean).join(', ') || '—'}
              {found.org ? <span className="faint"> · {found.org}{found.asn ? ` (AS${found.asn})` : ''}</span> : null}
              {found.country && !found.located ? <span className="faint"> · registration country, no geolocation database knows it</span> : null}
            </span>
          )}
        </div>
        <GeoAttribution />
      </Card>

      <EntitySection
        plural="geo-databases"
        title="Databases"
        description="When several are loaded, the first one, by id, that knows an address answers. A failed refresh keeps serving the last good file and is retried after five minutes."
        state={dbs}
        createLabel="New database"
        emptyTitle="No geolocation database"
        emptyBody={
          <p className="muted">
            Without one, @geoLookup never matches and the consoles show the country an address's network is registered in, from the ASN databases. The default is DB-IP lite, country level: free, and no key to ask for.
          </p>
        }
        onChanged={() => status.reload()}
        columns={[
          {
            key: 'state',
            label: 'Status',
            render: (e) => {
              const st = byId[e.id];
              if (!e.enabled) return <Badge>disabled</Badge>;
              if (!st) return <Badge kind="info">pending</Badge>;
              if (st.loaded && st.error) return <Badge kind="warning" title={st.error}>stale</Badge>;
              if (st.loaded) return <Badge kind="positive">loaded</Badge>;
              return <Badge kind="negative" title={st.error || ''}>failed</Badge>;
            },
          },
          { key: 'type', label: 'Database', render: (e) => (byId[e.id] && byId[e.id].database_type) || '—' },
          { key: 'build', label: 'Built', render: (e) => (byId[e.id] && byId[e.id].build_time ? fmtDay(byId[e.id].build_time) : '—') },
          { key: 'checked', label: 'Checked', render: (e) => (byId[e.id] && byId[e.id].fetched_at ? fmtDate(byId[e.id].fetched_at) : '—') },
          {
            key: 'error',
            label: '',
            render: (e) => {
              const st = byId[e.id];
              return st && st.error ? <span className="truncate muted" title={st.error}>{st.error}</span> : null;
            },
          },
        ]}
      />
    </div>
  );
}
