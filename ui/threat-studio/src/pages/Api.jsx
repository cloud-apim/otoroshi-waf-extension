import { useState } from 'react';
import { useWorkspace } from '../App';
import { Badge, Card, Empty, ErrorAlert, Loading, NumberInput, PageHeader, Tabs, useAsync } from '../components/ui';
import { fmtInt, fmtRelative } from '../lib/format';
import { Link } from '../lib/router';
import { workspaceSecurity } from '../lib/security';

const SEVERITY = { high: 'negative', medium: 'warning', low: 'info', info: '' };
const STATE = { used: 'positive', unused: '', zombie: 'warning' };

function Kpi({ label, value, vs, alarm }) {
  return (
    <Card className="tight">
      <div className="kpi">
        <span className="label">{label}</span>
        <span className="value" style={alarm ? { color: 'var(--negative-text)' } : undefined}>
          {fmtInt(value)}
        </span>
        {vs && <span className="vs">{vs}</span>}
      </div>
    </Card>
  );
}

const statuses = (s) =>
  s
    ? ['2xx', '3xx', '4xx', '5xx', 'refused']
        .filter((k) => s[k] > 0)
        .map((k) => `${fmtInt(s[k])} ${k}`)
        .join(' · ') || '—'
    : '—';

/**
 * What a workspace's APIs actually are, as opposed to what their contracts say (API-2, API-3, API-4):
 * the credentials that apply to each endpoint, the operations used and the ones nobody calls, the
 * paths outside the contracts, and how the traffic drifts from them.
 */
export function ApiPage() {
  const { workspace } = useWorkspace();
  const [tab, setTab] = useState('auth');
  const [zombieDays, setZombieDays] = useState(90);
  const ids = (workspace.claims || []).map((r) => r.id);
  // the api narrows the report to the workspace's routes, and to none when it claims none
  const report = useAsync(() => workspaceSecurity(workspace.id).apiReport(zombieDays), [workspace.id, ids.length, zombieDays]);

  const routes = (report.data && report.data.routes) || [];
  const summary = (report.data && report.data.summary) || {};
  const auth = summary.auth || {};
  const rows = (pick) => routes.flatMap((r) => (pick(r) || []).map((x) => ({ route: r, ...x })));

  return (
    <div className="content wide">
      <PageHeader
        title="API"
        description="What the APIs of this workspace actually are: who can call what, what is used, what is undocumented, what drifted."
      />
      {report.error ? (
        <ErrorAlert error={report.error} />
      ) : !report.data ? (
        <Card>
          <Loading />
        </Card>
      ) : ids.length === 0 ? (
        <Empty title="No route">This workspace claims no route yet.</Empty>
      ) : (
        <>
          <div className="grid c4" style={{ marginBottom: 18 }}>
            <Kpi label="Unauthenticated" value={auth.high || 0} vs={`${fmtInt(auth.medium || 0)} scheme mismatches`} alarm={(auth.high || 0) > 0} />
            <Kpi label="Shadow endpoints" value={summary.confirmed_shadows || 0} vs={`${fmtInt(summary.shadows || 0)} paths outside the contracts`} alarm={(summary.confirmed_shadows || 0) > 0} />
            <Kpi label="Zombie operations" value={summary.zombies || 0} vs={`${fmtInt(summary.unused || 0)} not called yet`} />
            <Kpi label="Drift" value={summary.drift || 0} vs={`${fmtInt(summary.sensitive_drift || 0)} on sensitive fields`} alarm={(summary.sensitive_drift || 0) > 0} />
          </div>
          {summary.with_contract === 0 && (
            <div className="alert" style={{ marginBottom: 14 }}>
              No route of this workspace is checked against a contract: only the credentials are reported. Point the workspace at
              one in <Link to={`/workspaces/${workspace.id}/protection`}>Protection → API contract</Link>.
            </div>
          )}
          <Tabs
            value={tab}
            onChange={setTab}
            tabs={[
              { value: 'auth', label: 'Credentials' },
              { value: 'endpoints', label: 'Operations' },
              { value: 'shadows', label: 'Outside the contract' },
              { value: 'drift', label: 'Drift' },
            ]}
          />
          <Card style={{ marginTop: 12 }}>
            {tab === 'auth' && (
              <>
                <p className="muted small">
                  Read off each route&apos;s plugins — the ones refusing a request with no credential, and the paths they are scoped to
                  — and compared with what its contract declares, operation by operation. A credential checked by a global plugin is not
                  seen here.
                </p>
                <table className="table">
                  <thead>
                    <tr>
                      <th>Severity</th>
                      <th>Route</th>
                      <th>Endpoint</th>
                      <th>What</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows((r) => r.auth && r.auth.findings).map((f, i) => (
                      <tr key={i}>
                        <td>
                          <Badge kind={SEVERITY[f.severity]}>{f.severity}</Badge>
                        </td>
                        <td>{f.route.route_name}</td>
                        <td className="mono">
                          {f.method} {f.path}
                        </td>
                        <td className="small">{f.detail}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {rows((r) => r.auth && r.auth.findings).length === 0 && <p className="muted">Every endpoint checks the credential its contract asks for.</p>}
              </>
            )}
            {tab === 'endpoints' && (
              <>
                <div className="row" style={{ gap: 8, marginBottom: 10 }}>
                  <span className="muted small">A zombie is an operation nobody called for</span>
                  <NumberInput value={zombieDays} allowEmpty={false} min={1} style={{ width: 80 }} onChange={(v) => setZombieDays(Math.max(1, Math.floor(v || 90)))} />
                  <span className="muted small">days, on a route observed for at least as long.</span>
                </div>
                <table className="table">
                  <thead>
                    <tr>
                      <th>Route</th>
                      <th>Operation</th>
                      <th>State</th>
                      <th>Calls</th>
                      <th>Answered</th>
                      <th>Last called</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows((r) => r.operations).map((o, i) => (
                      <tr key={i}>
                        <td>{o.route.route_name}</td>
                        <td className="mono">
                          {o.method} {o.path}
                          {o.operation_id && <div className="muted small">{o.operation_id}</div>}
                        </td>
                        <td>
                          <Badge kind={STATE[o.state]}>{o.state}</Badge>
                        </td>
                        <td>{fmtInt(o.hits)}</td>
                        <td className="small">{statuses(o.statuses)}</td>
                        <td className="small">{o.last_seen ? fmtRelative(o.last_seen) : 'never'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </>
            )}
            {tab === 'shadows' && (
              <>
                <p className="muted small">
                  Paths no operation of the contract has, identifiers folded into <code>{'{id}'}</code>. One the backend answered with a
                  2xx is an endpoint that exists and that nobody documented; the rest are mostly probes.
                </p>
                <table className="table">
                  <thead>
                    <tr>
                      <th>Route</th>
                      <th>Path</th>
                      <th>Calls</th>
                      <th>Answered</th>
                      <th>Last seen</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows((r) => r.shadows).map((s, i) => (
                      <tr key={i}>
                        <td>{s.route.route_name}</td>
                        <td className="mono">
                          {s.method} {s.path} {s.confirmed && <Badge kind="negative">answered</Badge>}
                        </td>
                        <td>{fmtInt(s.hits)}</td>
                        <td className="small">{statuses(s.statuses)}</td>
                        <td className="small">{fmtRelative(s.last_seen)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {rows((r) => r.shadows).length === 0 && <p className="muted">Every request matched an operation of its contract.</p>}
              </>
            )}
            {tab === 'drift' && (
              <>
                <p className="muted small">
                  Fields, types and statuses the contract never declares, seen on real requests and responses, a payload of each operation
                  compared with its shape once a minute on each node.
                </p>
                <table className="table">
                  <thead>
                    <tr>
                      <th>Route</th>
                      <th>Operation</th>
                      <th>Where</th>
                      <th>What</th>
                      <th>Seen</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows((r) => r.drift).map((d, i) => (
                      <tr key={i}>
                        <td>{d.route.route_name}</td>
                        <td className="mono">{d.operation}</td>
                        <td className="mono">
                          {d.where} {d.sensitive && <Badge kind="negative">sensitive</Badge>}
                        </td>
                        <td className="small">
                          {(d.kind || '').replace(/_/g, ' ')}
                          {d.observed ? `: ${d.observed}` : ''}
                        </td>
                        <td className="small">
                          {fmtInt(d.count)} · {fmtRelative(d.last_seen)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {rows((r) => r.drift).length === 0 && <p className="muted">Nothing seen that the contracts do not declare.</p>}
              </>
            )}
          </Card>
        </>
      )}
    </div>
  );
}
