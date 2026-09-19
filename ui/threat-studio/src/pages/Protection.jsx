import { useEffect, useState } from 'react';
import { useStudio, useWorkspace } from '../App';
import { Icon } from '../components/icons';
import {
  Badge,
  Card,
  ErrorAlert,
  Field,
  LinesInput,
  Loading,
  PageHeader,
  Segmented,
  Select,
  Toggle,
  useAsync,
  useConfirm,
  useToast,
} from '../components/ui';
import { canWrite } from '../lib/bootstrap';
import { Link } from '../lib/router';
import { Resources } from '../lib/entities';
import { enforcementOf, PRESET_DEFAULTS, replaceWorkspace, saveTable, SECTIONS } from '../lib/workspaces';

/**
 * The arming console.
 *
 * Every switch here changes what expands onto a fleet of routes at once, so the page leads with the
 * one question that matters — can any of this actually stop a request — rather than with the
 * switches. A section switched off expands into nothing at all, not into a disabled plugin.
 */
export function ProtectionPage() {
  const { workspace, table } = useWorkspace();
  const studio = useStudio();
  const toast = useToast();
  const confirm = useConfirm();
  const writable = canWrite();

  const [preset, setPreset] = useState({ ...PRESET_DEFAULTS, ...(workspace.preset || {}) });
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setPreset({ ...PRESET_DEFAULTS, ...(workspace.preset || {}) });
  }, [workspace.id, JSON.stringify(workspace.preset)]);

  const refs = useAsync(
    () =>
      Promise.all([Resources.threatPolicies.list(), Resources.botPolicies.list(), Resources.wafConfigs.list()]).then(
        ([policies, bots, configs]) => ({ policies, bots, configs })
      ),
    []
  );

  const saved = { ...PRESET_DEFAULTS, ...(workspace.preset || {}) };
  const dirty = JSON.stringify(preset) !== JSON.stringify(saved);
  const set = (patch) => setPreset((p) => ({ ...p, ...patch }));

  const before = enforcementOf({ ...workspace, preset: saved }, refs.data || {});
  const after = enforcementOf({ ...workspace, preset }, refs.data || {});
  const routes = (workspace.claims || []).length;

  const save = async () => {
    if (!before.armed && after.armed) {
      const ok = await confirm({
        title: 'Arm this workspace?',
        message: `${routes} route${routes === 1 ? '' : 's'} will start refusing traffic: ${after.reason}. Until now everything here only recorded.`,
        confirmLabel: 'Arm it',
        danger: true,
      });
      if (!ok) return;
    }
    setBusy(true);
    saveTable(replaceWorkspace(table, workspace.id, (w) => ({ ...w, preset })))
      .then(() => {
        studio.reload();
        toast.success('Protection saved');
      })
      .catch(toast.error)
      .finally(() => setBusy(false));
  };

  if (refs.loading) return <div className="content"><Loading /></div>;
  if (refs.error) return <div className="content"><ErrorAlert error={refs.error} /></div>;

  const options = (list, empty) => [{ value: '', label: empty }].concat(list.map((e) => ({ value: e.id, label: e.name })));

  if (workspace.skip) {
    return (
      <div className="content narrow">
        <PageHeader title="Protection" description="This workspace is an opt-out." />
        <Card>
          <p className="muted">
            It matches, lays down nothing at all and stops the search, so there is nothing to arm. Turn the opt-out off
            in <Link to={`/workspaces/${workspace.id}/scope`}>Scope</Link> to give it a protection.
          </p>
        </Card>
      </div>
    );
  }

  return (
    <div className="content narrow">
      <PageHeader
        title="Protection"
        description={`What every route this workspace governs receives, in the one order that makes the chain work.`}
      >
        {writable && dirty && (
          <button className="btn primary" onClick={save} disabled={busy}>
            Save
          </button>
        )}
      </PageHeader>

      <Card className="tight" style={{ marginBottom: 18 }}>
        <div className="row between" style={{ gap: 12, flexWrap: 'wrap' }}>
          <div>
            <div className="row" style={{ gap: 8 }}>
              {after.armed ? <Badge kind="positive">enforcing</Badge> : <Badge kind="warning">observing only</Badge>}
              <b>{after.reason}</b>
            </div>
            <div className="muted small" style={{ marginTop: 4 }}>
              On {routes} route{routes === 1 ? '' : 's'}.{' '}
              {after.armed
                ? 'Traffic matching these detectors is refused.'
                : 'Everything is recorded and every request still goes through.'}
            </div>
          </div>
          {dirty && before.armed !== after.armed && (
            <Badge kind={after.armed ? 'negative' : 'info'}>
              {after.armed ? 'saving will arm it' : 'saving will disarm it'}
            </Badge>
          )}
        </div>
      </Card>

      <Card title="Sections" description="A section switched off expands into nothing at all — not into a plugin sitting there doing nothing.">
        {SECTIONS.map((s) => (
          <div className="setting-row" key={s.key}>
            <div>
              <b>{s.label}</b>
              <div className="muted small">{s.help}</div>
            </div>
            <div className="row" style={{ gap: 12 }}>
              <Toggle value={!!preset[s.key]} onChange={(v) => set({ [s.key]: v })} disabled={!writable} />
              {s.key === 'reputation' && preset.reputation && (
                <Segmented
                  options={[
                    { value: 'block', label: 'Block' },
                    { value: 'monitor', label: 'Monitor' },
                  ]}
                  value={preset.reputation_mode}
                  onChange={(v) => set({ reputation_mode: v })}
                />
              )}
              {s.key === 'fail2ban' && preset.fail2ban && (
                <Segmented
                  options={[
                    { value: 'dry', label: 'Dry run' },
                    { value: 'armed', label: 'Armed' },
                  ]}
                  value={preset.fail2ban_dry_run ? 'dry' : 'armed'}
                  onChange={(v) => set({ fail2ban_dry_run: v === 'dry' })}
                />
              )}
              {s.key === 'waf' && preset.waf && !preset.waf_config && <Badge kind="warning">no config</Badge>}
            </div>
          </div>
        ))}
      </Card>

      <Card style={{ marginTop: 18 }} title="Entities" description="The configuration each section reads. They are ordinary entities of the extension, shared by every workspace that points at them.">
        <div className="setting-row top">
          <div>
            <b>Threat policy</b>
            <div className="muted small">
              Shared by the gate and the response. Empty means the built-in dry-run policy: everything is recorded,
              nothing is enforced.
            </div>
          </div>
          <div>
            <Select
              value={preset.threat_policy || ''}
              onChange={(v) => set({ threat_policy: v || null })}
              options={options(refs.data.policies, 'Built-in (dry run)')}
              disabled={!writable}
            />
            <Link className="small" to={`/workspaces/${workspace.id}/policy`} style={{ marginTop: 6, display: 'inline-block' }}>
              Manage policies
            </Link>
          </div>
        </div>
        <div className="setting-row top">
          <div>
            <b>WAF config</b>
            <div className="muted small">Required for the WAF section to expand at all.</div>
          </div>
          <div>
            <Select
              value={preset.waf_config || ''}
              onChange={(v) => set({ waf_config: v || null })}
              options={options(refs.data.configs, 'None — the WAF does not run')}
              disabled={!writable}
            />
            <Link className="small" to={`/workspaces/${workspace.id}/waf`} style={{ marginTop: 6, display: 'inline-block' }}>
              Manage WAF configs
            </Link>
          </div>
        </div>
        <div className="setting-row top">
          <div>
            <b>Bot policy</b>
            <div className="muted small">Empty means the first enabled bot policy.</div>
          </div>
          <div>
            <Select
              value={preset.bot_policy || ''}
              onChange={(v) => set({ bot_policy: v || null })}
              options={options(refs.data.bots, 'First enabled one')}
              disabled={!writable}
            />
            <Link className="small" to={`/workspaces/${workspace.id}/bots`} style={{ marginTop: 6, display: 'inline-block' }}>
              Manage bot policies
            </Link>
          </div>
        </div>
      </Card>

      <Card style={{ marginTop: 18 }} title="Path scoping" description="Applied to every plugin this workspace lays down. Leave empty to protect the whole route.">
        <div className="setting-row top">
          <div>
            <b>Only these paths</b>
            <div className="muted small">One pattern per line, for instance <code>/api/.*</code></div>
          </div>
          <LinesInput value={preset.include || []} onChange={(v) => set({ include: v })} rows={3} disabled={!writable} />
        </div>
        <div className="setting-row top">
          <div>
            <b>Except these paths</b>
            <div className="muted small">Health checks and the like</div>
          </div>
          <LinesInput value={preset.exclude || []} onChange={(v) => set({ exclude: v })} rows={3} disabled={!writable} />
        </div>
      </Card>

      {!writable && (
        <p className="faint small" style={{ marginTop: 14 }}>
          Editing the table needs a super admin: it lives on the global configuration.
        </p>
      )}
    </div>
  );
}
