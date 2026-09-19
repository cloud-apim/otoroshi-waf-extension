import { Badge, Card, ErrorAlert, Loading, PageHeader, useAsync } from '../components/ui';
import { Donut, NoExporter } from '../components/widgets';
import { fmtDate, fmtInt } from '../lib/format';
import { itemsOf, NoExporterError, Q, runQuery } from '../lib/analytics';
import { Security } from '../lib/security';

function seconds(v) {
  if (v === null || v === undefined) return '—';
  if (v >= 86400) return `${Math.round(v / 86400)} d`;
  if (v >= 3600) return `${Math.round(v / 3600)} h`;
  if (v >= 60) return `${Math.round(v / 60)} min`;
  return `${v} s`;
}

/**
 * Whether the numbers everywhere else can be trusted.
 *
 * Bans, fail2ban counters and incident correlation all live in the shared state. On a leader/worker
 * cluster without a dedicated redis, every node keeps its own — so an empty ban list means "this node
 * saw nothing" rather than "nothing happened", and every other page silently under-reports. That
 * distinction is the reason this page exists.
 */
export function ClusterPage() {
  const status = useAsync(() => Security.status(), []);
  const nodes = useAsync(() => runQuery(Q.byNode, { period: '7d' }).catch((e) => ({ __error: e })), []);

  const s = status.data;
  const shared = s && s.shared_state;
  const nodesResult = nodes.data && !nodes.data.__error ? nodes.data : null;
  const nodesError = nodes.data && nodes.data.__error;

  return (
    <div className="content">
      <PageHeader title="Cluster & state" description="Where the suite keeps what it has to remember, and whether that reaches every node." />

      {status.loading ? (
        <Card>
          <Loading />
        </Card>
      ) : status.error ? (
        <ErrorAlert error={status.error} />
      ) : (
        <>
          {shared && shared.warning && (
            <div className="alert error" style={{ marginBottom: 18 }}>
              {shared.warning}
            </div>
          )}

          <div className="grid c2" style={{ marginBottom: 18 }}>
            <Card title="Shared state" description="Bans, counters and incident correlation all read and write here.">
              <div className="setting-row" style={{ paddingTop: 4 }}>
                <div>
                  <b>Reaches every node</b>
                  <div className="muted small">
                    Without it, everything below is what this one node has seen.
                  </div>
                </div>
                <div>
                  {shared && shared.distributed ? (
                    <Badge kind="positive">distributed</Badge>
                  ) : (
                    <Badge kind="negative">this node only</Badge>
                  )}
                </div>
              </div>
              <div className="setting-row">
                <div>
                  <b>Dedicated store</b>
                  <div className="muted small">A <code>security.redis-uri</code> of its own</div>
                </div>
                <div>{shared && shared.dedicated_redis ? <Badge kind="positive">configured</Badge> : <Badge>none</Badge>}</div>
              </div>
              <div className="setting-row">
                <div>
                  <b>This node</b>
                </div>
                <div className="mono small">{s.node}</div>
              </div>
            </Card>

            <Card title="What it holds" description="Read live, on this node.">
              <div className="setting-row" style={{ paddingTop: 4 }}>
                <div>
                  <b>Bans</b>
                </div>
                <div>{fmtInt(s.bans && s.bans.bans)}</div>
              </div>
              <div className="setting-row">
                <div>
                  <b>Allowlist</b>
                </div>
                <div>{fmtInt(s.allowlist && (s.allowlist.entries ?? s.allowlist.size))}</div>
              </div>
              <div className="setting-row">
                <div>
                  <b>Incident window</b>
                  <div className="muted small">How long decisions about one caller are correlated</div>
                </div>
                <div>{seconds(s.incidents && s.incidents.window_seconds)}</div>
              </div>
              <div className="setting-row">
                <div>
                  <b>Last refresh</b>
                </div>
                <div className="faint small">{s.bans && s.bans.last_refresh ? fmtDate(s.bans.last_refresh) : '—'}</div>
              </div>
            </Card>
          </div>

          <Card title="Cross-request ledger" description="What composes signals seen at different moments into one judgement." style={{ marginBottom: 18 }}>
            <div className="setting-row" style={{ paddingTop: 4 }}>
              <div>
                <b>Enabled</b>
              </div>
              <div>{s.ledger && s.ledger.enabled ? <Badge kind="positive">on</Badge> : <Badge>off</Badge>}</div>
            </div>
            <div className="setting-row">
              <div>
                <b>Window</b>
              </div>
              <div>{seconds(s.ledger && s.ledger.window_seconds)}</div>
            </div>
            <div className="setting-row">
              <div>
                <b>Ban threshold</b>
                <div className="muted small">Accumulated score at which the ledger bans by itself</div>
              </div>
              <div>{fmtInt(s.ledger && s.ledger.ban_threshold)}</div>
            </div>
            <div className="setting-row">
              <div>
                <b>Ban duration</b>
              </div>
              <div>{seconds(s.ledger && s.ledger.ban_duration_seconds)}</div>
            </div>
          </Card>

          <Card title="Decisions by node" description="Over the past week. An even spread is what a healthy cluster looks like; one node holding everything usually means the shared state is not shared.">
            {nodes.loading ? (
              <Loading />
            ) : nodesError instanceof NoExporterError ? (
              <NoExporter />
            ) : nodesError ? (
              <ErrorAlert error={nodesError} />
            ) : (
              <Donut items={itemsOf(nodesResult)} />
            )}
          </Card>

          {(s.policies || []).length > 0 && (
            <Card style={{ marginTop: 18 }} title="Threat policies on this install">
              <div className="posture">
                {s.policies.map((p) => (
                  <span key={p.id} className={`mark ${p.dry_run ? 'observing' : 'armed'}`}>
                    {p.name} · {p.dry_run ? 'dry run' : 'enforcing'}
                  </span>
                ))}
              </div>
            </Card>
          )}
        </>
      )}
    </div>
  );
}
