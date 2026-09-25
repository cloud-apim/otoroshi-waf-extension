import { useCallback, useEffect, useRef, useState } from 'react';
import { useWorkspace } from '../App';
import { Icon } from '../components/icons';
import { GeoRows, IpAddress } from '../components/ip';
import {
  Badge,
  Card,
  Drawer,
  ErrorAlert,
  Loading,
  PageHeader,
  Segmented,
  Select,
  Tabs,
  TextInput,
  useAsync,
} from '../components/ui';
import { NoExporter, PeriodPicker, RefreshControl, useTimeView } from '../components/widgets';
import { useQueryState } from '../lib/router';
import { fmtDate, fmtInt } from '../lib/format';
import { itemsOf, NoExporterError, PERIODS, Q, runQuery } from '../lib/analytics';

/**
 * The rows themselves.
 *
 * The aggregates say what is happening; this says what happened to one caller, and opening a row
 * shows the signals — the attribution that answers *why* the fabric judged them. A console that
 * cannot drill into that can only ever show that something happened.
 */

const CATEGORIES = [
  { value: '', label: 'Every detector' },
  { value: 'threat', label: 'Threat' },
  { value: 'honeypot', label: 'Honeypot' },
  { value: 'fail2ban', label: 'Fail2ban' },
  { value: 'challenge', label: 'Challenge' },
  { value: 'ban', label: 'Ban' },
];

const ACTIONS = [
  { value: '', label: 'Every action' },
  { value: 'log', label: 'Log' },
  { value: 'tarpit', label: 'Tarpit' },
  { value: 'challenge', label: 'Challenge' },
  { value: 'deny', label: 'Deny' },
  { value: 'ban', label: 'Ban' },
];

const OUTCOMES = [
  { value: 'all', label: 'All' },
  { value: 'enforced', label: 'Enforced' },
  { value: 'observed', label: 'Observed' },
];

function ActionBadge({ row }) {
  const enforced = row.enforced === true;
  const kind = !enforced ? '' : row.action === 'ban' || row.action === 'deny' ? 'negative' : 'warning';
  return (
    <Badge kind={kind} title={enforced ? 'This decision acted' : 'Recorded, and let through'}>
      {row.action || '—'}
      {!enforced && ' (observed)'}
    </Badge>
  );
}

function DecisionDrawer({ id, onClose, period, scope }) {
  const detail = useAsync(
    () => (id ? runQuery(Q.decisionDetail, { period, scope, params: { id } }).then((r) => itemsOf(r)[0] || null) : Promise.resolve(null)),
    [id, period]
  );
  const raw = detail.data && detail.data.raw;
  const signals = (raw && raw.threat && raw.threat.signals) || [];
  return (
    <Drawer open={!!id} title="Security decision" onClose={onClose}>
      {detail.loading ? (
        <Loading />
      ) : detail.error ? (
        <ErrorAlert error={detail.error} />
      ) : !raw ? (
        <p className="muted">This decision is no longer in the retention window.</p>
      ) : (
        <>
          <dl className="kv">
            <dt>When</dt>
            <dd>{fmtDate(detail.data.ts)}</dd>
            <dt>Route</dt>
            <dd>{(raw.otoroshi && raw.otoroshi.route_name) || '—'}</dd>
            <dt>Source</dt>
            <dd>
              <IpAddress value={raw.source && raw.source.ip} />
            </dd>
            <GeoRows value={raw.source && raw.source.ip} />
            <dt>Detector</dt>
            <dd>{(raw.event && raw.event.category) || '—'}</dd>
            <dt>Action</dt>
            <dd>
              {(raw.event && raw.event.action) || '—'}{' '}
              {raw.decision && raw.decision.enforced ? <Badge kind="negative">enforced</Badge> : <Badge>observed</Badge>}
            </dd>
            <dt>Score</dt>
            <dd>{(raw.threat && raw.threat.score) ?? '—'}</dd>
            <dt>Incident</dt>
            <dd className="mono">{(raw.incident && raw.incident.id) || '—'}</dd>
          </dl>

          <h3 style={{ margin: '22px 0 8px', fontSize: 15 }}>Why</h3>
          {signals.length === 0 ? (
            <p className="muted small">This decision carries no signal: it was taken on the accumulated score alone.</p>
          ) : (
            <table className="table">
              <thead>
                <tr>
                  <th>Signal</th>
                  <th style={{ textAlign: 'right' }}>Weight</th>
                </tr>
              </thead>
              <tbody>
                {signals.map((s, i) => (
                  <tr key={i}>
                    <td>
                      <div>{s.kind || s.name || s.tag || 'signal'}</div>
                      {s.reason && <div className="faint small">{s.reason}</div>}
                    </td>
                    <td style={{ textAlign: 'right' }}>{s.weight ?? s.score ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          <h3 style={{ margin: '22px 0 8px', fontSize: 15 }}>Whole event</h3>
          <pre className="mono" style={{ maxHeight: 340, overflow: 'auto' }}>
            {JSON.stringify(raw, null, 2)}
          </pre>
        </>
      )}
    </Drawer>
  );
}

function WafTrailDrawer({ id, onClose, period, scope }) {
  const detail = useAsync(
    () => (id ? runQuery(Q.wafTrailDetail, { period, scope, params: { id } }).then((r) => itemsOf(r)[0] || null) : Promise.resolve(null)),
    [id, period]
  );
  const raw = detail.data && detail.data.raw;
  const matches = (raw && raw.events) || [];
  return (
    <Drawer open={!!id} title="WAF trail" onClose={onClose}>
      {detail.loading ? (
        <Loading />
      ) : detail.error ? (
        <ErrorAlert error={detail.error} />
      ) : !raw ? (
        <p className="muted">This trail is no longer in the retention window.</p>
      ) : (
        <>
          <dl className="kv">
            <dt>When</dt>
            <dd>{fmtDate(detail.data.ts)}</dd>
            <dt>Route</dt>
            <dd>{(raw.route && raw.route.name) || '—'}</dd>
            <dt>Mode</dt>
            <dd>{raw.blocking ? 'blocking' : 'monitoring'}</dd>
            <dt>Verdict</dt>
            <dd>
              {raw.block ? (
                raw.blocking ? (
                  <Badge kind="negative">blocked with {raw.block.status}</Badge>
                ) : (
                  <Badge kind="warning">would have blocked</Badge>
                )
              ) : (
                <Badge>let through</Badge>
              )}
            </dd>
          </dl>
          <h3 style={{ margin: '22px 0 8px', fontSize: 15 }}>Rules that matched</h3>
          {matches.length === 0 ? (
            <p className="muted small">No rule matched this request.</p>
          ) : (
            <table className="table">
              <thead>
                <tr>
                  <th>Rule</th>
                  <th>Message</th>
                  <th style={{ textAlign: 'right' }}>Phase</th>
                </tr>
              </thead>
              <tbody>
                {matches.map((m, i) => (
                  <tr key={i}>
                    <td className="mono">{m.rule_id}</td>
                    <td>{m.msg || '—'}</td>
                    <td style={{ textAlign: 'right' }}>{m.phase ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <h3 style={{ margin: '22px 0 8px', fontSize: 15 }}>Whole event</h3>
          <pre className="mono" style={{ maxHeight: 340, overflow: 'auto' }}>
            {JSON.stringify(raw, null, 2)}
          </pre>
        </>
      )}
    </Drawer>
  );
}

/**
 * A log, paged.
 *
 * The cursor is the pair `(ts, id)` and not the timestamp alone: a burst puts many rows on the same
 * millisecond, and paging on the instant would drop every row sharing the one a page ended on —
 * silently, and exactly on the traffic this log exists to show.
 */
function useLog(query, params, { period, scope, tick, onBusy, onLoaded }) {
  const [rows, setRows] = useState([]);
  const [next, setNext] = useState(null);
  const [state, setState] = useState({ loading: true, refreshing: false, error: null });
  const loaded = useRef(null);
  const key = JSON.stringify({ query, params, period, scope });

  const fetchPage = useCallback(
    (cursor) =>
      runQuery(query, {
        period,
        scope,
        params: {
          ...params,
          limit: 100,
          ...(cursor ? { before: cursor.before, before_id: cursor.beforeId } : {}),
        },
      }).then((r) => {
        const before = r && r.data && r.data.next_before;
        const beforeId = r && r.data && r.data.next_before_id;
        return {
          items: itemsOf(r),
          next: before && beforeId ? { before, beforeId } : null,
        };
      }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [key]
  );

  // a new `tick` on the same query is a refresh: the rows stay on screen until the first page comes
  // back and replaces them (and whatever "load more" had appended — the newest rows are the point)
  useEffect(() => {
    let alive = true;
    const silent = loaded.current === fetchPage;
    loaded.current = fetchPage;
    setState(silent ? (s) => ({ ...s, refreshing: true }) : { loading: true, refreshing: false, error: null });
    fetchPage()
      .then((p) => {
        if (!alive) return;
        setRows(p.items);
        setNext(p.next);
        setState({ loading: false, refreshing: false, error: null });
        if (onLoaded) onLoaded(Date.now());
      })
      .catch((error) => alive && setState({ loading: false, refreshing: false, error }));
    return () => {
      alive = false;
    };
  }, [fetchPage, tick]);

  const busy = state.loading || state.refreshing;
  useEffect(() => {
    if (onBusy) onBusy(busy);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [busy]);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => () => onBusy && onBusy(false), []);

  const more = () => {
    if (!next) return;
    setState((s) => ({ ...s, loading: true }));
    fetchPage(next)
      .then((p) => {
        setRows((r) => r.concat(p.items));
        setNext(p.next);
        setState({ loading: false, refreshing: false, error: null });
      })
      .catch((error) => setState({ loading: false, refreshing: false, error }));
  };

  return { rows, next, more, ...state };
}

function DecisionsLog({ period, scope, tick, onBusy, onLoaded, query, setQuery }) {
  const category = query.category || '';
  const action = query.action || '';
  const outcome = OUTCOMES.some((o) => o.value === query.outcome) ? query.outcome : 'all';
  const source = query.source || '';
  const setCategory = (v) => setQuery({ category: v });
  const setAction = (v) => setQuery({ action: v });
  const setOutcome = (v) => setQuery({ outcome: v === 'all' ? null : v });
  const setSource = (v) => setQuery({ source: v });
  const [open, setOpen] = useState(null);

  const params = {
    ...(category ? { category } : {}),
    ...(action ? { action } : {}),
    ...(outcome === 'all' ? {} : { enforced: outcome === 'enforced' }),
    ...(source.trim() ? { source: source.trim() } : {}),
  };
  const log = useLog(Q.decisionsLog, params, { period, scope, tick, onBusy, onLoaded });

  if (log.error instanceof NoExporterError) return <Card><NoExporter /></Card>;

  return (
    <>
      <div className="row" style={{ gap: 10, flexWrap: 'wrap', margin: '16px 0' }}>
        <Select value={category} onChange={setCategory} options={CATEGORIES} style={{ width: 'auto' }} />
        <Select value={action} onChange={setAction} options={ACTIONS} style={{ width: 'auto' }} />
        <Segmented options={OUTCOMES} value={outcome} onChange={setOutcome} />
        <TextInput value={source} onChange={setSource} placeholder="Filter by source address" style={{ maxWidth: 220 }} />
      </div>
      <Card className="flush">
        {log.error ? (
          <div style={{ padding: 20 }}>
            <ErrorAlert error={log.error} />
          </div>
        ) : log.rows.length === 0 && !log.loading ? (
          <div className="chart-empty" style={{ height: 160 }}>
            No decision in this period
          </div>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>When</th>
                  <th>Route</th>
                  <th>Source</th>
                  <th>Detector</th>
                  <th>Action</th>
                  <th style={{ textAlign: 'right' }}>Score</th>
                  <th>Signals</th>
                </tr>
              </thead>
              <tbody>
                {log.rows.map((row) => (
                  <tr key={row.id} style={{ cursor: 'pointer' }} onClick={() => setOpen(row.id)}>
                    <td className="faint" style={{ whiteSpace: 'nowrap' }}>{fmtDate(row.ts)}</td>
                    <td className="truncate">{row.route_name || row.route_id || '—'}</td>
                    <td>
                      <IpAddress value={row.from_ip} />
                    </td>
                    <td>{row.category || '—'}</td>
                    <td>
                      <ActionBadge row={row} />
                    </td>
                    <td style={{ textAlign: 'right' }}>{fmtInt(row.score)}</td>
                    <td className="truncate faint small">{(row.tags || []).join(', ') || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {log.loading && <div style={{ padding: 16 }}><Loading /></div>}
        {log.next && !log.loading && (
          <div style={{ padding: 14, textAlign: 'center' }}>
            <button className="btn sm" onClick={log.more}>
              Load more
            </button>
          </div>
        )}
      </Card>
      <DecisionDrawer id={open} onClose={() => setOpen(null)} period={period} scope={scope} />
    </>
  );
}

const TRAIL_FILTERS = [
  { value: '', label: 'All' },
  { value: 'matched', label: 'Matched a rule' },
  { value: 'blocked', label: 'Blocked' },
  { value: 'would_block', label: 'Would have blocked' },
];

function TrailLog({ period, scope, tick, onBusy, onLoaded, query, setQuery }) {
  const only = TRAIL_FILTERS.some((f) => f.value === query.only) ? query.only : '';
  const setOnly = (v) => setQuery({ only: v });
  const [open, setOpen] = useState(null);
  const log = useLog(Q.wafTrailLog, only ? { only } : {}, { period, scope, tick, onBusy, onLoaded });

  if (log.error instanceof NoExporterError) return <Card><NoExporter /></Card>;

  return (
    <>
      <div className="row" style={{ gap: 10, margin: '16px 0' }}>
        <Segmented options={TRAIL_FILTERS} value={only} onChange={setOnly} />
      </div>
      <Card className="flush">
        {log.error ? (
          <div style={{ padding: 20 }}>
            <ErrorAlert error={log.error} />
          </div>
        ) : log.rows.length === 0 && !log.loading ? (
          <div className="chart-empty" style={{ height: 160 }}>
            Nothing inspected in this period
          </div>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>When</th>
                  <th>Route</th>
                  <th>Mode</th>
                  <th>Verdict</th>
                  <th>Rules</th>
                  <th>Body</th>
                </tr>
              </thead>
              <tbody>
                {log.rows.map((row) => (
                  <tr key={row.id} style={{ cursor: 'pointer' }} onClick={() => setOpen(row.id)}>
                    <td className="faint" style={{ whiteSpace: 'nowrap' }}>{fmtDate(row.ts)}</td>
                    <td className="truncate">{row.route_name || row.route_id || '—'}</td>
                    <td>{row.blocking ? 'blocking' : 'monitoring'}</td>
                    <td>
                      {row.blocked ? (
                        row.blocking ? (
                          <Badge kind="negative">blocked {row.status}</Badge>
                        ) : (
                          <Badge kind="warning">would have blocked</Badge>
                        )
                      ) : (
                        <Badge>let through</Badge>
                      )}
                    </td>
                    <td className="mono faint small truncate" style={{ maxWidth: 420 }} title={(row.rule_ids || []).join(', ')}>
                      {(row.rule_ids || []).join(', ') || '—'}
                    </td>
                    <td className="faint small">
                      {row.oversize_rejected ? 'rejected as oversize' : row.truncated ? 'truncated' : 'whole'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {log.loading && <div style={{ padding: 16 }}><Loading /></div>}
        {log.next && !log.loading && (
          <div style={{ padding: 14, textAlign: 'center' }}>
            <button className="btn sm" onClick={log.more}>
              Load more
            </button>
          </div>
        )}
      </Card>
      <WafTrailDrawer id={open} onClose={() => setOpen(null)} period={period} scope={scope} />
    </>
  );
}

const TABS = [
  { value: 'decisions', label: 'Security decisions' },
  { value: 'waf', label: 'WAF trail' },
];

export function EventsPage() {
  const { workspace } = useWorkspace();
  const [query, setQuery] = useQueryState();
  const { period, refresh, setPeriod, setRefresh } = useTimeView('events', query, setQuery, '24h');
  const tab = TABS.some((t) => t.value === query.tab) ? query.tab : 'decisions';
  // each log drops the other's filters, so a tab change does not carry a filter the new log ignores
  const setTab = (value) => setQuery({ tab: value, category: null, action: null, outcome: null, source: null, only: null }, { push: true });
  const [tick, setTick] = useState(0);
  const [busy, setBusy] = useState(false);
  const [loadedAt, setLoadedAt] = useState(null);
  const scope = (workspace.claims || []).map((r) => r.id);
  const logProps = { period, scope, tick, onBusy: setBusy, onLoaded: setLoadedAt, query, setQuery };

  return (
    <div className="content wide">
      <PageHeader
        title="Events"
        description="Every decision one by one, newest first. Opening a row shows the signals behind it — the attribution that answers why."
      >
        <PeriodPicker value={period} onChange={setPeriod} />
        <RefreshControl
          {...refresh}
          onChange={setRefresh}
          onRefresh={() => setTick((t) => t + 1)}
          busy={busy}
          loadedAt={loadedAt}
        />
      </PageHeader>
      <Tabs tabs={TABS} value={tab} onChange={setTab} />
      {tab === 'decisions' ? <DecisionsLog {...logProps} /> : <TrailLog {...logProps} />}
    </div>
  );
}
