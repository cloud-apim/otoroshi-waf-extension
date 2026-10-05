import { useEffect, useState } from 'react';
import { EntitySection } from '../components/entities';
import { Icon } from '../components/icons';
import { Badge, Card, ErrorAlert, Loading, Modal, PageHeader, TextInput, useAsync, useToast } from '../components/ui';
import { fmtDate, fmtInt } from '../lib/format';
import { Resources } from '../lib/entities';
import { Reputation } from '../lib/security';

/**
 * Threat feeds belong to the install, not to a workspace.
 *
 * Every enabled feed is consulted for every route whose workspace enables reputation, so listing
 * them under one workspace would suggest a scoping that does not exist.
 */
export function FeedsPage() {
  const toast = useToast();
  const feeds = useAsync(() => Resources.threatFeeds.list(), []);
  const status = useAsync(() => Reputation.status().catch(() => null), []);
  const [lookup, setLookup] = useState('');
  const [result, setResult] = useState(null);

  // the snapshot of each feed, as this node holds it: the entity says what to fetch, the snapshot
  // what was fetched
  const byId = ((status.data && status.data.feeds) || []).reduce((acc, f) => ({ ...acc, [f.id]: f.snapshot }), {});

  // a feed is fetched in the background, 5 to 25 seconds after it is created or after a restart:
  // keep asking until every one that can be fetched has loaded or failed, so "pending" does not stay.
  // one without a url never is, the server skips it
  const fetchable = (e) => e.enabled && !!(e.url || '').trim();
  const pending = !!status.data && (feeds.data || []).some((e) => fetchable(e) && !byId[e.id]) && !status.loading;
  useEffect(() => {
    if (!pending) return undefined;
    const timer = setInterval(() => status.reload({ silent: true }), 3000);
    return () => clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pending]);

  // `feed` names the one to refresh; without it the server refreshes every feed
  const refresh = (id) =>
    Reputation.refresh({ feed: id })
      .then((r) => {
        const failed = r && r.done ? ((r.snapshots || [])[0] || {}).error : (r && r.error) || 'could not refresh';
        if (failed) toast.error(failed);
        else toast.success('Feed refreshed');
        status.reload();
      })
      .catch(toast.error);

  const doLookup = () =>
    Reputation.lookup({ ip: lookup.trim() })
      .then(setResult)
      .catch(toast.error);

  return (
    <div className="content wide">
      <PageHeader title="Threat feeds" description="Address ranges from threat intelligence, refreshed on a schedule. Consulted for every route whose workspace enables reputation. Add one from the curated catalog." />

      <Card style={{ marginBottom: 18 }} title="Check one address" description="What every enabled source says about it, right now.">
        <div className="row" style={{ gap: 8 }}>
          <TextInput value={lookup} onChange={setLookup} placeholder="203.0.113.10" style={{ maxWidth: 240 }} />
          <button className="btn" onClick={doLookup} disabled={!lookup.trim()}>
            Look up
          </button>
        </div>
      </Card>

      <EntitySection
        plural="threat-feeds"
        title="Feeds"
        description="A disabled feed is not consulted at all."
        state={feeds}
        createLabel="Add a feed"
        emptyTitle="No threat feed"
        emptyBody={<p className="muted">The catalog carries curated sources with their parser, refresh interval and weight already set.</p>}
        onChanged={() => status.reload()}
        columns={[
          {
            key: 'state',
            label: 'Status',
            render: (e) => {
              const st = byId[e.id];
              if (!e.enabled) return <Badge>disabled</Badge>;
              if (!fetchable(e)) return <Badge kind="warning" title="Nothing is fetched until it has one">no url</Badge>;
              if (!st) return <Badge kind="info">pending</Badge>;
              // a failed refresh keeps serving the last good snapshot, which is what stale means
              if (st.error && st.entries > 0) return <Badge kind="warning" title={st.error}>stale</Badge>;
              if (st.error) return <Badge kind="negative" title={st.error}>failed</Badge>;
              return <Badge kind="positive">loaded</Badge>;
            },
          },
          { key: 'action', label: 'Action', render: (e) => e.action || 'monitor' },
          { key: 'weight', label: 'Weight', render: (e) => e.weight },
          {
            key: 'ranges',
            label: 'Ranges',
            render: (e) => {
              const st = byId[e.id];
              if (!st) return '—';
              const detail = `${fmtInt(st.entries)} entries${st.rejected ? `, ${fmtInt(st.rejected)} rejected` : ''}, merged into ${fmtInt(st.ranges)} ranges`;
              return <span title={detail}>{fmtInt(st.ranges)}</span>;
            },
          },
          {
            key: 'refreshed',
            label: 'Refreshed',
            // the last attempt, failed or not: a failure is on the status, with its reason
            render: (e) => {
              const st = byId[e.id];
              return st && st.fetched_at ? fmtDate(st.fetched_at) : '—';
            },
          },
          {
            key: 'refresh',
            label: '',
            render: (e) => (
              <button className="copy-btn" title="Refresh now" onClick={() => refresh(e.id)}>
                <Icon name="refresh" />
              </button>
            ),
          },
        ]}
      />

      <DnsBlocklists rbl={status.data && status.data.rbl} />

      <Modal open={!!result} title={`What the sources say about ${lookup}`} onClose={() => setResult(null)} size="lg">
        <pre className="mono" style={{ maxHeight: 420, overflow: 'auto' }}>{JSON.stringify(result, null, 2)}</pre>
      </Modal>
    </div>
  );
}

/**
 * The zones `@rbl` asks, as this node sees them.
 *
 * Shown only once a WAF rule has used one: a blocklist that refuses the resolver answers "not
 * listed" to everyone, which nothing else on screen would ever reveal.
 */
function DnsBlocklists({ rbl }) {
  const zones = Object.entries((rbl && rbl.zones) || {});
  if (!zones.length) return null;
  return (
    <Card
      style={{ marginTop: 18 }}
      title="DNS blocklists"
      description={`Zones asked by @rbl in WAF rules, through ${rbl.nameservers === 'system' ? "the system's name servers" : rbl.nameservers.join(', ')}. An unhealthy zone answers "not listed" to everyone.`}
    >
      <table className="table">
        <thead>
          <tr>
            <th>Zone</th>
            <th>Health</th>
            <th>Detail</th>
            <th>Checked</th>
          </tr>
        </thead>
        <tbody>
          {zones.map(([zone, h]) => (
            <tr key={zone}>
              <td className="mono">{zone}</td>
              <td>{h.healthy ? <Badge kind="positive">healthy</Badge> : <Badge kind="negative">unhealthy</Badge>}</td>
              <td className="muted">{h.detail}</td>
              <td>{fmtDate(h.checked_at)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="faint" style={{ marginTop: 8 }}>
        {fmtInt(rbl.lookups)} lookups · {fmtInt(rbl.listed)} listed · {fmtInt(rbl.not_listed)} not listed · {fmtInt(rbl.refused)} refused ·{' '}
        {fmtInt(rbl.unknown)} unanswered
      </p>
    </Card>
  );
}
