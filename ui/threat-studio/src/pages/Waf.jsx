import { useState } from 'react';
import { useStudio, useWorkspace } from '../App';
import { EntitySection } from '../components/entities';
import { Icon } from '../components/icons';
import {
  Badge,
  Card,
  Empty,
  ErrorAlert,
  Loading,
  PageHeader,
  Tabs,
  TextArea,
  useAsync,
  useConfirm,
  useToast,
} from '../components/ui';
import { canWrite } from '../lib/bootstrap';
import { Resources } from '../lib/entities';
import { fmtDate, fmtInt, fmtPercent } from '../lib/format';
import { Learning, Tuning } from '../lib/security';
import { PRESET_DEFAULTS, replaceWorkspace, saveTable } from '../lib/workspaces';

/**
 * The rule engine of this workspace, whole and in the studio.
 *
 * Three tabs, in the order a rollout works: the config itself, then tuning — turning a false
 * positive into a verified exclusion — then learning, which measures a window of real traffic and
 * says what arming would cost. None of it leaves the studio.
 */
export function WafPage() {
  const { workspace } = useWorkspace();
  const [tab, setTab] = useState('config');
  const preset = { ...PRESET_DEFAULTS, ...(workspace.preset || {}) };

  const TABS = [
    { value: 'config', label: 'Config' },
    { value: 'tuning', label: 'Tuning' },
    { value: 'learning', label: 'Learning' },
  ];

  return (
    <div className="content wide">
      <PageHeader title="WAF" description="The rule engine this workspace runs, the false positives to tune out, and what arming would cost." />
      <Tabs tabs={TABS} value={tab} onChange={setTab} />
      <div style={{ marginTop: 16 }}>
        {tab === 'config' && <ConfigTab configRef={preset.waf_config} enabled={preset.waf} />}
        {tab === 'tuning' && <TuningTab configRef={preset.waf_config} />}
        {tab === 'learning' && <LearningTab configRef={preset.waf_config} />}
      </div>
    </div>
  );
}

/* --------------------------------------------------------------------------- config */

function ConfigTab({ configRef, enabled }) {
  const { workspace, table } = useWorkspace();
  const studio = useStudio();
  const toast = useToast();
  const writable = canWrite();
  const preset = { ...PRESET_DEFAULTS, ...(workspace.preset || {}) };
  const [editing, setEditing] = useState(null);

  const configs = useAsync(() => Resources.wafConfigs.list(), []);
  const current = (configs.data || []).find((c) => c.id === configRef);

  const use = (id) =>
    saveTable(replaceWorkspace(table, workspace.id, (w) => ({ ...w, preset: { ...preset, waf_config: id, waf: true } })))
      .then(() => {
        studio.reload();
        toast.success('WAF config selected');
      })
      .catch(toast.error);

  return (
    <>
      <Card style={{ marginBottom: 18 }}>
        {!enabled ? (
          <p className="muted">The WAF section is switched off for this workspace, so no rule engine runs on its routes.</p>
        ) : !current ? (
          <p className="muted">No WAF config is selected, so the WAF section expands into nothing. Pick one below, or create one.</p>
        ) : (
          <div className="row between" style={{ gap: 12, flexWrap: 'wrap' }}>
            <div>
              <div className="row" style={{ gap: 8 }}>
                <b style={{ fontSize: 16 }}>{current.name}</b>
                {current.block ? <Badge kind="positive">blocking</Badge> : <Badge kind="warning">monitoring</Badge>}
              </div>
              <div className="muted small" style={{ marginTop: 4 }}>
                {current.block
                  ? 'Requests the ruleset reaches a deny on are refused.'
                  : 'Everything is inspected and nothing is refused. The "would have blocked" count is what arming will cost.'}
              </div>
            </div>
            <button className="btn sm" onClick={() => setEditing(current)}>
              <Icon name={writable ? 'edit' : 'eye'} />
              {writable ? 'Edit rules' : 'View rules'}
            </button>
          </div>
        )}
      </Card>

      <EntitySection
        plural="waf-configs"
        title="WAF configs"
        description="Ordinary entities of the extension. Several workspaces can point at the same one."
        state={configs}
        selectedId={configRef}
        onSelect={use}
        writable={writable}
        workspaceId={workspace.id}
        kind="waf"
        createLabel="New config"
        emptyTitle="No WAF config"
        emptyBody={<p className="muted">A config holds the SecLang rules and the rulesets it composes. The WAF section cannot expand without one.</p>}
        columns={[
          {
            key: 'block',
            label: 'Mode',
            render: (e) => (e.block ? <Badge kind="positive">blocking</Badge> : <Badge kind="warning">monitoring</Badge>),
          },
        ]}
        editing={editing}
        onEditingChange={setEditing}
      />
    </>
  );
}

/* --------------------------------------------------------------------------- tuning */

function Verdict({ preview }) {
  if (!preview) return null;
  const bad = !preview.compiles || !preview.effective || (preview.regressions || []).length > 0;
  return (
    <div className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
      {!preview.compiles ? (
        <Badge kind="negative">does not compile</Badge>
      ) : !preview.effective ? (
        <Badge kind="negative">changes nothing</Badge>
      ) : (
        <Badge kind="positive">stops the rule firing</Badge>
      )}
      {(preview.regressions || []).length > 0 ? (
        <Badge kind="negative">{preview.regressions.length} known attack{preview.regressions.length === 1 ? '' : 's'} no longer caught</Badge>
      ) : preview.compiles && preview.effective ? (
        <Badge kind="positive">no attack in the corpus slips through</Badge>
      ) : null}
    </div>
  );
}

function Proposal({ proposal, onApply, busy }) {
  const p = proposal;
  const preview = p.preview;
  const blocked = preview && (!preview.compiles || (preview.regressions || []).length > 0);
  return (
    <div className="card tight" style={{ marginBottom: 10 }}>
      <div className="row between" style={{ gap: 10, flexWrap: 'wrap' }}>
        <div style={{ minWidth: 0 }}>
          <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
            <b>{p.title}</b>
            {p.recommended && <Badge kind="positive">recommended</Badge>}
            <Badge>{p.reach}</Badge>
          </div>
          <div className="muted small" style={{ marginTop: 4 }}>{p.rationale}</div>
        </div>
        <button className="btn sm primary" disabled={busy} onClick={() => onApply(p)}>
          {blocked ? 'Apply anyway' : 'Apply'}
        </button>
      </div>
      <pre className="mono" style={{ marginTop: 10, whiteSpace: 'pre-wrap' }}>{p.seclang}</pre>
      <div style={{ marginTop: 8 }}>
        <Verdict preview={preview} />
      </div>
      {(p.no_longer_caught || p.still_caught) && (
        <div className="small muted" style={{ marginTop: 8 }}>
          {p.still_caught && <div>Still caught: {p.still_caught}</div>}
          {p.no_longer_caught && <div>No longer caught: {p.no_longer_caught}</div>}
        </div>
      )}
    </div>
  );
}

function CandidateDrawer({ candidate, configRef, onClose, onApplied }) {
  const toast = useToast();
  const confirm = useConfirm();
  const [busy, setBusy] = useState(false);
  const proposals = useAsync(
    () => (candidate ? Tuning.propose({ sample_id: candidate.id, config_ref: configRef }) : Promise.resolve(null)),
    [candidate && candidate.id]
  );

  const apply = async (p) => {
    const preview = p.preview;
    const risky = preview && (!preview.compiles || (preview.regressions || []).length > 0);
    if (risky) {
      const ok = await confirm({
        title: 'Apply this exclusion anyway?',
        message: !preview.compiles
          ? 'It does not compile cleanly, so it may not do what it says.'
          : `It stops ${preview.regressions.length} known attack(s) in the corpus being caught in the same input. Only do this if that input is genuinely safe on this route.`,
        danger: true,
        confirmLabel: 'Apply',
      });
      if (!ok) return;
    }
    setBusy(true);
    Tuning.apply({ sample_id: candidate.id, config_ref: configRef, seclang: p.seclang, kind: p.kind, reason: 'tuned from Threat Studio', force: risky })
      .then((r) => {
        if (r && r.error) throw new Error(r.error);
        toast.success('Exclusion applied');
        onApplied();
        onClose();
      })
      .catch(toast.error)
      .finally(() => setBusy(false));
  };

  if (!candidate) return null;
  return (
    <div className="drawer-backdrop" onClick={onClose}>
      <div className="drawer wide" onClick={(e) => e.stopPropagation()}>
        <div className="drawer-head">
          <h2>Rule {candidate.rule_id}</h2>
          <button className="copy-btn" onClick={onClose} title="Close">
            <Icon name="x" />
          </button>
        </div>
        <div className="drawer-body">
          <dl className="kv">
            <dt>Rule</dt>
            <dd>{candidate.rule_id} — {candidate.msg || '—'}</dd>
            <dt>On</dt>
            <dd className="mono">{candidate.method} {candidate.path}</dd>
            <dt>Seen</dt>
            <dd>{fmtInt(candidate.count)} times, last {fmtDate(candidate.last_seen)}</dd>
          </dl>
          <p className="muted small" style={{ marginTop: 12 }}>
            Every proposal below was run before being shown: the verdict is measured, not described. Pick the narrowest
            one that stops the rule firing without letting a known attack through.
          </p>
          <div style={{ marginTop: 14 }}>
            {proposals.loading ? (
              <Loading />
            ) : proposals.error ? (
              <ErrorAlert error={proposals.error} />
            ) : (
              ((proposals.data && proposals.data.proposals) || []).map((p, i) => (
                <Proposal key={i} proposal={p} onApply={apply} busy={busy} />
              ))
            )}
            {proposals.data && (proposals.data.proposals || []).length === 0 && (
              <p className="muted">No exclusion could be generated for this match.</p>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function TuningTab({ configRef }) {
  const writable = canWrite();
  const [open, setOpen] = useState(null);
  const matches = useAsync(() => Tuning.matches(), []);

  const candidates = ((matches.data && matches.data.matches) || []).filter((m) => !configRef || m.config_ref === configRef);

  return (
    <>
      <Card
        className="flush"
        title="Tuning candidates"
        description="Rules that fired on traffic somebody thinks is legitimate. Open one to see the exclusions the assistant generated, each already run."
        actions={
          <button className="btn sm" onClick={() => matches.reload()}>
            <Icon name="refresh" />
            Refresh
          </button>
        }
      >
        {matches.loading ? (
          <div style={{ padding: 20 }}><Loading /></div>
        ) : matches.error ? (
          <div style={{ padding: 20 }}><ErrorAlert error={matches.error} /></div>
        ) : candidates.length === 0 ? (
          <div className="chart-empty" style={{ height: 160 }}>
            Nothing recent. {(matches.data && matches.data.scope) || ''}
          </div>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Rule</th>
                  <th>Where</th>
                  <th style={{ textAlign: 'right' }}>Seen</th>
                  <th>Last</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {candidates.map((m) => (
                  <tr key={m.key} style={{ cursor: writable ? 'pointer' : 'default' }} onClick={writable ? () => setOpen(m) : undefined}>
                    <td>
                      <div className="mono">{m.rule_id}</div>
                      <div className="faint small truncate">{m.msg}</div>
                    </td>
                    <td className="truncate small">{m.method} {m.path}</td>
                    <td style={{ textAlign: 'right' }}>{fmtInt(m.count)}</td>
                    <td className="faint small" style={{ whiteSpace: 'nowrap' }}>{fmtDate(m.last_seen)}</td>
                    <td style={{ textAlign: 'right' }}>
                      {writable && (
                        <button className="btn sm" onClick={(e) => { e.stopPropagation(); setOpen(m); }}>
                          Tune
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      {matches.data && matches.data.scope && (
        <p className="faint small" style={{ marginTop: 10 }}>{matches.data.scope}</p>
      )}
      <CandidateDrawer
        candidate={open}
        configRef={configRef}
        onClose={() => setOpen(null)}
        onApplied={() => matches.reload()}
      />
    </>
  );
}

/* --------------------------------------------------------------------------- learning */

function Stat({ label, value, hint }) {
  return (
    <Card className="tight">
      <div className="kpi">
        <span className="label">{label}</span>
        <span className="value">{value}</span>
        {hint && <span className="vs">{hint}</span>}
      </div>
    </Card>
  );
}

function LearningReport({ configRef, onApplied }) {
  const toast = useToast();
  const [picked, setPicked] = useState({});
  const [busy, setBusy] = useState(false);
  const report = useAsync(() => Learning.report({ config_ref: configRef }), [configRef]);

  if (report.loading) return <Loading />;
  if (report.error) return <ErrorAlert error={report.error} />;
  const r = report.data;
  if (!r || r.error) return <p className="muted">{(r && r.error) || 'No report yet — stop the window to read one.'}</p>;

  const impact = r.impact || {};
  const accepted = (r.exclusions || []).filter((e) => e.accepted);
  const chosen = accepted.filter((e) => picked[e.entry.key]);

  const apply = () => {
    setBusy(true);
    Learning.apply({ config_ref: configRef, keys: chosen.map((e) => e.entry.key), reason: 'applied from a learning run in Threat Studio' })
      .then((res) => {
        if (res && res.error) throw new Error(res.error);
        toast.success(`Applied ${res.count} exclusion${res.count === 1 ? '' : 's'}`);
        setPicked({});
        onApplied();
      })
      .catch(toast.error)
      .finally(() => setBusy(false));
  };

  return (
    <>
      <Card style={{ marginBottom: 18 }}>
        <div className="row between" style={{ gap: 12, flexWrap: 'wrap' }}>
          <div>
            <b style={{ fontSize: 16 }}>Verdict</b>
            <p className="muted" style={{ marginTop: 4 }}>{r.verdict}</p>
          </div>
        </div>
        {r.mode && !r.mode.arming_estimate_reliable && (
          <div className="alert" style={{ marginTop: 12 }}>{r.mode.note}</div>
        )}
      </Card>

      <div className="grid c4" style={{ marginBottom: 18 }}>
        <Stat label="Requests" value={fmtInt((r.run || {}).requests)} />
        <Stat label="Matched a rule" value={fmtInt((r.run || {}).matched)} />
        <Stat label="Would have blocked" value={fmtInt(impact.would_block)} hint={impact.would_block_rate !== undefined ? fmtPercent(impact.would_block_rate, 1) + ' of requests' : undefined} />
        <Stat label="Resolved by these" value={fmtInt(impact.resolved)} hint={`${fmtInt(impact.residual)} would remain`} />
      </div>

      <div className="grid c2" style={{ marginBottom: 18 }}>
        {r.paranoia && (
          <Card title="Paranoia level" description={r.paranoia.rationale}>
            <div className="row" style={{ gap: 10 }}>
              <Badge>current {r.paranoia.current}</Badge>
              {r.paranoia.recommended != null && r.paranoia.recommended !== r.paranoia.current && (
                <Badge kind="info">suggested {r.paranoia.recommended}</Badge>
              )}
            </div>
          </Card>
        )}
        {r.threshold && (
          <Card title="Anomaly threshold" description={r.threshold.rationale}>
            <div className="row" style={{ gap: 10 }}>
              <Badge>current {r.threshold.current}</Badge>
              {r.threshold.recommended != null && r.threshold.recommended !== r.threshold.current && (
                <Badge kind="info">suggested {r.threshold.recommended}</Badge>
              )}
            </div>
          </Card>
        )}
      </div>

      <Card
        className="flush"
        title={`Proposed exclusions (${accepted.length})`}
        description="Only the ones the report verified are listed. Tick the ones to write, each into a ruleset in the right order."
        actions={
          <button className="btn sm primary" disabled={busy || chosen.length === 0} onClick={apply}>
            Apply {chosen.length || ''}
          </button>
        }
      >
        {accepted.length === 0 ? (
          <div className="chart-empty" style={{ height: 120 }}>No verified exclusion to apply.</div>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th style={{ width: 30 }} />
                  <th>Rule</th>
                  <th>Where</th>
                  <th style={{ textAlign: 'right' }}>Matches</th>
                </tr>
              </thead>
              <tbody>
                {accepted.map((e) => (
                  <tr key={e.entry.key}>
                    <td>
                      <input
                        type="checkbox"
                        checked={!!picked[e.entry.key]}
                        onChange={(ev) => setPicked((p) => ({ ...p, [e.entry.key]: ev.target.checked }))}
                      />
                    </td>
                    <td>
                      <div className="mono">{e.entry.rule_id}</div>
                      <div className="faint small truncate">{e.entry.msg}</div>
                    </td>
                    <td className="truncate small">{e.entry.method} {e.entry.path}</td>
                    <td style={{ textAlign: 'right' }}>{fmtInt(e.entry.count)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}

function LearningTab({ configRef }) {
  const toast = useToast();
  const confirm = useConfirm();
  const writable = canWrite();
  const running = useAsync(() => Learning.running(), []);
  const [busy, setBusy] = useState(false);
  const [reportKey, setReportKey] = useState(0);

  if (!configRef) {
    return (
      <Card>
        <Empty title="No WAF config">
          <p className="muted">Select a WAF config in the Config tab before measuring a learning window.</p>
        </Empty>
      </Card>
    );
  }

  const isRunning = ((running.data && running.data.running) || []).includes(configRef);
  const node = running.data && running.data.node;

  const act = (fn, message, confirmOpts) => async () => {
    if (confirmOpts && !(await confirm(confirmOpts))) return;
    setBusy(true);
    fn({ config_ref: configRef })
      .then(() => {
        toast.success(message);
        running.reload();
        setReportKey((k) => k + 1);
      })
      .catch(toast.error)
      .finally(() => setBusy(false));
  };

  return (
    <>
      <Card style={{ marginBottom: 18 }}>
        <div className="row between" style={{ gap: 12, flexWrap: 'wrap' }}>
          <div>
            <div className="row" style={{ gap: 8 }}>
              <b style={{ fontSize: 16 }}>Learning window</b>
              {isRunning ? <Badge kind="info">running</Badge> : <Badge>stopped</Badge>}
            </div>
            <div className="muted small" style={{ marginTop: 4 }}>
              {isRunning
                ? `Measuring on node ${node}. Leave the config in monitoring while it runs, then stop it to read the report.`
                : 'Measure a window of real traffic, then decide about arming from what it would have cost.'}
            </div>
          </div>
          {writable && (
            <div className="row" style={{ gap: 8 }}>
              {isRunning ? (
                <>
                  <button className="btn" disabled={busy} onClick={act(Learning.stop, 'Window stopped')}>
                    Stop &amp; report
                  </button>
                  <button
                    className="btn danger"
                    disabled={busy}
                    onClick={act(Learning.discard, 'Window discarded', {
                      title: 'Discard this window?',
                      message: 'The measurements are dropped and nothing is kept.',
                      danger: true,
                      confirmLabel: 'Discard',
                    })}
                  >
                    Discard
                  </button>
                </>
              ) : (
                <button className="btn primary" disabled={busy} onClick={act(Learning.start, 'Window started')}>
                  Start a window
                </button>
              )}
            </div>
          )}
        </div>
      </Card>

      {isRunning ? (
        <Card>
          <p className="muted">
            A window is running. Its report reads what the traffic did — stop it above when you have measured enough.
          </p>
        </Card>
      ) : (
        <LearningReport key={reportKey} configRef={configRef} onApplied={() => setReportKey((k) => k + 1)} />
      )}
    </>
  );
}
