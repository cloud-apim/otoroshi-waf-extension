import { useState } from 'react';
import { useWorkspace } from '../App';
import { Icon } from '../components/icons';
import {
  Badge,
  Card,
  Drawer,
  ErrorAlert,
  Loading,
  Modal,
  PageHeader,
  Select,
  Tabs,
  TextInput,
  useAsync,
  useConfirm,
  useToast,
} from '../components/ui';
import { DataTable, NoExporter } from '../components/widgets';
import { fmtDate, fmtInt, fmtRelative } from '../lib/format';
import { itemsOf, NoExporterError, Q, runQuery } from '../lib/analytics';
import { Security } from '../lib/security';

/**
 * The console during an incident, rather than the report afterwards.
 *
 * Two halves that answer different questions. The first is this workspace's traffic, from the
 * analytics. The second is live state — what the fabric is holding *right now* — and that half is
 * install-wide by construction: a ban is issued against a caller, not against a route, and is
 * enforced everywhere. Pretending it could be scoped to a workspace would be a comfortable lie.
 */

function BanDrawer({ entry, onClose, onAction }) {
  if (!entry) return null;
  return (
    <Drawer open title="Ban" onClose={onClose}>
      <dl className="kv">
        <dt>Caller</dt>
        <dd className="mono">{entry.key}</dd>
        <dt>Reason</dt>
        <dd>{entry.reason || '—'}</dd>
        <dt>Score</dt>
        <dd>{entry.score ?? '—'}</dd>
        <dt>Issued</dt>
        <dd>{fmtDate(entry.issued_at)} by {entry.issued_by || 'the fabric'}</dd>
        <dt>Until</dt>
        <dd>{entry.until ? `${fmtDate(entry.until)} (${fmtRelative(entry.until)})` : 'permanent'}</dd>
        <dt>Signals</dt>
        <dd>{(entry.tags || []).join(', ') || '—'}</dd>
      </dl>
      {(entry.timeline || []).length > 0 && (
        <>
          <h3 style={{ margin: '22px 0 8px', fontSize: 15 }}>Evidence</h3>
          <table className="table">
            <tbody>
              {entry.timeline.map((t, i) => (
                <tr key={i}>
                  <td className="faint small" style={{ whiteSpace: 'nowrap' }}>{fmtDate(t.at)}</td>
                  <td>{t.reason || t.action || t.category || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
      <div className="row" style={{ gap: 8, marginTop: 22 }}>
        <button className="btn" onClick={() => onAction('extend', entry)}>
          Extend by an hour
        </button>
        <button className="btn danger" onClick={() => onAction('unban', entry)}>
          Lift the ban
        </button>
        <button className="btn" onClick={() => onAction('allow', entry)}>
          Allowlist
        </button>
      </div>
    </Drawer>
  );
}

function LiveState() {
  const toast = useToast();
  const confirm = useConfirm();
  const [open, setOpen] = useState(null);
  const bans = useAsync(() => Security.bans(), []);
  const incidents = useAsync(() => Security.incidents(), []);
  const allowlist = useAsync(() => Security.allowlist(), []);
  const status = useAsync(() => Security.status(), []);

  const reload = () => {
    bans.reload();
    incidents.reload();
    allowlist.reload();
  };

  const action = async (kind, entry) => {
    const ref = entry.ref || { kind: 'ip', value: entry.key };
    if (kind === 'unban') {
      const ok = await confirm({ title: 'Lift this ban?', message: `${entry.key} will be let through again.`, danger: true, confirmLabel: 'Lift' });
      if (!ok) return;
      await Security.unban({ ref }).catch(toast.error);
      toast.success('Ban lifted');
    }
    if (kind === 'extend') {
      await Security.extend({ ref, duration: 3600 }).catch(toast.error);
      toast.success('Ban extended');
    }
    if (kind === 'allow') {
      const ok = await confirm({
        title: 'Allowlist this caller?',
        message: `${entry.key} will be left alone by every detector, on every route.`,
        confirmLabel: 'Allowlist',
      });
      if (!ok) return;
      await Security.allow({ ref, reason: 'allowlisted from Threat Studio' }).catch(toast.error);
      toast.success('Allowlisted');
    }
    setOpen(null);
    reload();
  };

  const shared = status.data && status.data.shared_state;

  return (
    <>
      {shared && shared.warning && (
        <div className="alert" style={{ margin: '16px 0' }}>
          {shared.warning}
        </div>
      )}

      <Card className="flush" style={{ marginTop: 16, marginBottom: 18 }} title="What the fabric is holding now" description="Bans are issued against a caller and enforced on every route of the install.">
        {bans.loading ? (
          <div style={{ padding: 20 }}><Loading /></div>
        ) : bans.error ? (
          <div style={{ padding: 20 }}><ErrorAlert error={bans.error} /></div>
        ) : (bans.data.bans || []).length === 0 ? (
          <div className="chart-empty" style={{ height: 120 }}>Nobody is banned right now</div>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Caller</th>
                  <th>Reason</th>
                  <th style={{ textAlign: 'right' }}>Score</th>
                  <th>Until</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {(bans.data.bans || []).map((b) => (
                  <tr key={b.key} style={{ cursor: 'pointer' }} onClick={() => setOpen(b)}>
                    <td className="mono">{b.key}</td>
                    <td className="truncate">{b.reason || '—'}</td>
                    <td style={{ textAlign: 'right' }}>{b.score ?? '—'}</td>
                    <td className="faint small">{b.until ? fmtRelative(b.until) : 'permanent'}</td>
                    <td style={{ textAlign: 'right' }}>
                      <Badge kind="negative">{(b.tags || []).length} signal{(b.tags || []).length === 1 ? '' : 's'}</Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <div className="grid c2">
        <Card className="flush" title="Incidents" description="What the fabric is still watching, merged across every node.">
          {incidents.loading ? (
            <div style={{ padding: 20 }}><Loading /></div>
          ) : incidents.error ? (
            <div style={{ padding: 20 }}><ErrorAlert error={incidents.error} /></div>
          ) : (incidents.data.incidents || []).length === 0 ? (
            <div className="chart-empty" style={{ height: 120 }}>No open incident</div>
          ) : (
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th>Caller</th>
                    <th>State</th>
                    <th style={{ textAlign: 'right' }}>Evidence</th>
                    <th>Last seen</th>
                  </tr>
                </thead>
                <tbody>
                  {(incidents.data.incidents || []).map((i) => (
                    <tr key={i.key}>
                      <td className="mono">{i.key}</td>
                      <td>
                        <Select
                          value={i.state || 'open'}
                          onChange={(v) =>
                            Security.incidentState({ ref: i.ref, state: v })
                              .then(() => {
                                toast.success('Incident moved');
                                incidents.reload();
                              })
                              .catch(toast.error)
                          }
                          options={[
                            { value: 'open', label: 'Open' },
                            { value: 'acknowledged', label: 'Acknowledged' },
                            { value: 'resolved', label: 'Resolved' },
                          ]}
                        />
                      </td>
                      <td style={{ textAlign: 'right' }}>
                        {fmtInt(i.count)}
                        {i.banned && (
                          <Badge kind="negative" title="This caller is currently held">
                            held
                          </Badge>
                        )}
                      </td>
                      <td className="faint small">{fmtRelative(i.last_seen)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>

        <Card className="flush" title="Allowlist" description="What the fabric has been told to leave alone, on every route.">
          {allowlist.loading ? (
            <div style={{ padding: 20 }}><Loading /></div>
          ) : (allowlist.data.entries || []).length === 0 ? (
            <div className="chart-empty" style={{ height: 120 }}>Nothing allowlisted</div>
          ) : (
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th>Caller</th>
                    <th>Reason</th>
                    <th>Until</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {(allowlist.data.entries || []).map((e) => (
                    <tr key={e.key}>
                      <td className="mono">{e.key}</td>
                      <td className="truncate">{e.reason || '—'}</td>
                      <td className="faint small">{e.permanent ? 'permanent' : fmtRelative(e.until)}</td>
                      <td style={{ textAlign: 'right' }}>
                        <button
                          className="copy-btn"
                          title="Remove from the allowlist"
                          onClick={() =>
                            Security.disallow({ ref: e.ref })
                              .then(() => {
                                toast.success('Removed');
                                allowlist.reload();
                              })
                              .catch(toast.error)
                          }
                        >
                          <Icon name="trash" />
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>

      <BanDrawer entry={open} onClose={() => setOpen(null)} onAction={action} />
    </>
  );
}

function WorkspaceIncidents({ scope }) {
  const state = useAsync(() => runQuery(Q.topIncidents, { period: '7d', scope, params: { top_n: 25 } }), [scope.join(',')]);
  if (state.error instanceof NoExporterError) return <Card style={{ marginTop: 16 }}><NoExporter /></Card>;
  if (state.loading) return <Card style={{ marginTop: 16 }}><Loading /></Card>;
  if (state.error) return <ErrorAlert error={state.error} />;
  return (
    <Card
      className="flush"
      style={{ marginTop: 16 }}
      title="Incidents in this workspace's traffic"
      description="Correlated over the past week, on the routes this workspace governs."
    >
      <DataTable
        res={state.data}
        columns={[
          { key: 'source', label: 'Caller' },
          { key: 'events', label: 'Evidence', align: 'right', format: fmtInt },
          { key: 'decisions', label: 'Decisions', align: 'right', format: fmtInt },
          { key: 'enforced', label: 'Enforced', align: 'right', format: fmtInt },
          { key: 'max_score', label: 'Max score', align: 'right', format: fmtInt },
        ]}
        empty="No incident on these routes in the past week"
      />
    </Card>
  );
}

const TABS = [
  { value: 'workspace', label: 'This workspace' },
  { value: 'live', label: 'Live state (install-wide)' },
];

export function IncidentsPage() {
  const { workspace } = useWorkspace();
  const [tab, setTab] = useState('workspace');
  const scope = (workspace.claims || []).map((r) => r.id);

  return (
    <div className="content wide">
      <PageHeader
        title="Bans & incidents"
        description="Who is being stopped, on what evidence, and what to do about them."
      />
      <Tabs tabs={TABS} value={tab} onChange={setTab} />
      {tab === 'workspace' ? <WorkspaceIncidents scope={scope} /> : <LiveState />}
    </div>
  );
}
