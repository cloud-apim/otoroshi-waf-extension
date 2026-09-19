import { useState } from 'react';
import { EntityList, SectionCard } from '../components/entities';
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

  const refresh = (id) =>
    Reputation.refresh({ ref: id })
      .then(() => {
        toast.success('Refresh requested');
        status.reload();
      })
      .catch(toast.error);

  const doLookup = () =>
    Reputation.lookup({ ip: lookup.trim() })
      .then(setResult)
      .catch(toast.error);

  const byId = ((status.data && status.data.feeds) || []).reduce((acc, f) => ({ ...acc, [f.id || f.ref]: f }), {});

  return (
    <div className="content wide">
      <PageHeader title="Threat feeds" description="Address ranges from threat intelligence, refreshed on a schedule. Consulted for every route whose workspace enables reputation.">
        <a className="btn" href="/bo/dashboard/extensions/cloud-apim/waf/threatfeedcatalog" target="_blank" rel="noreferrer">
          <Icon name="book" />
          Catalog
        </a>
      </PageHeader>

      <Card style={{ marginBottom: 18 }} title="Check one address" description="What every enabled source says about it, right now.">
        <div className="row" style={{ gap: 8 }}>
          <TextInput value={lookup} onChange={setLookup} placeholder="203.0.113.10" style={{ maxWidth: 240 }} />
          <button className="btn" onClick={doLookup} disabled={!lookup.trim()}>
            Look up
          </button>
        </div>
      </Card>

      <SectionCard title="Feeds" description="A disabled feed is not consulted at all." plural="threat-feeds">
        <EntityList
          state={feeds}
          plural="threat-feeds"
          emptyTitle="No threat feed"
          emptyBody={<p className="muted">The catalog has fourteen curated sources ready to add.</p>}
          columns={[
            {
              key: 'ranges',
              label: 'Ranges',
              render: (e) => {
                const s = byId[e.id];
                return s ? fmtInt(s.ranges ?? s.size) : '—';
              },
            },
            {
              key: 'refreshed',
              label: 'Refreshed',
              render: (e) => {
                const s = byId[e.id];
                return s && s.last_refresh ? fmtDate(s.last_refresh) : '—';
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
      </SectionCard>

      <Modal open={!!result} title={`What the sources say about ${lookup}`} onClose={() => setResult(null)} size="lg">
        <pre className="mono" style={{ maxHeight: 420, overflow: 'auto' }}>{JSON.stringify(result, null, 2)}</pre>
      </Modal>
    </div>
  );
}
