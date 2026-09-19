import { useStudio, useWorkspace } from '../App';
import { EntitySection, OpenInOtoroshi } from '../components/entities';
import { Icon } from '../components/icons';
import { Badge, Card, ErrorAlert, Loading, PageHeader, useAsync, useToast } from '../components/ui';
import { canWrite } from '../lib/bootstrap';
import { Resources } from '../lib/entities';
import { fmtDate, fmtInt } from '../lib/format';
import { Learning, Tuning } from '../lib/security';
import { PRESET_DEFAULTS, replaceWorkspace, saveTable } from '../lib/workspaces';

/**
 * The rule engine of this workspace, and the two things that make arming it survivable: what is
 * firing on legitimate traffic, and whether a measured window says it can be armed at all.
 */
export function WafPage() {
  const { workspace, table } = useWorkspace();
  const studio = useStudio();
  const toast = useToast();
  const writable = canWrite();
  const preset = { ...PRESET_DEFAULTS, ...(workspace.preset || {}) };

  const configs = useAsync(() => Resources.wafConfigs.list(), []);
  const tuning = useAsync(() => Tuning.matches().catch(() => null), []);
  const learning = useAsync(() => Learning.running().catch(() => null), []);

  const current = (configs.data || []).find((c) => c.id === preset.waf_config);
  const candidates = ((tuning.data && tuning.data.matches) || []).filter(
    (m) => !preset.waf_config || m.config_ref === preset.waf_config
  );
  const isLearning = ((learning.data && learning.data.running) || []).includes(preset.waf_config);

  const use = (id) => {
    saveTable(replaceWorkspace(table, workspace.id, (w) => ({ ...w, preset: { ...preset, waf_config: id, waf: true } })))
      .then(() => {
        studio.reload();
        toast.success('WAF config selected');
      })
      .catch(toast.error);
  };

  return (
    <div className="content wide">
      <PageHeader
        title="WAF"
        description="The rule engine this workspace runs, and what tuning it would take to arm it."
      />

      <Card style={{ marginBottom: 18 }}>
        {!preset.waf ? (
          <p className="muted">
            The WAF section is switched off for this workspace, so no rule engine runs on its routes.
          </p>
        ) : !current ? (
          <p className="muted">
            No WAF config is selected, so the WAF section expands into nothing. Pick one below.
          </p>
        ) : (
          <div className="row between" style={{ gap: 12, flexWrap: 'wrap' }}>
            <div>
              <div className="row" style={{ gap: 8 }}>
                <b style={{ fontSize: 16 }}>{current.name}</b>
                {current.block ? <Badge kind="positive">blocking</Badge> : <Badge kind="warning">monitoring</Badge>}
                {isLearning && <Badge kind="info">learning window running</Badge>}
              </div>
              <div className="muted small" style={{ marginTop: 4 }}>
                {current.block
                  ? 'Requests the ruleset reaches a deny on are refused.'
                  : 'Everything is inspected and nothing is refused. The "would have blocked" count is what arming will cost.'}
              </div>
            </div>
            <OpenInOtoroshi plural="waf-configs" id={current.id} label="Edit rules" />
          </div>
        )}
      </Card>

      <div className="grid c2" style={{ marginBottom: 18 }}>
        <Card
          title="Tuning candidates"
          description="Rules that fired on traffic somebody thinks is legitimate. The starting point of every tuning session."
          actions={
            <a className="btn sm" href="/bo/dashboard/extensions/cloud-apim/waf/tuning" target="_blank" rel="noreferrer">
              <Icon name="external" />
              Assistant
            </a>
          }
        >
          {tuning.loading ? (
            <Loading />
          ) : !tuning.data ? (
            <p className="muted small">The tuning store is not reachable from this node.</p>
          ) : candidates.length === 0 ? (
            <p className="muted">
              Nothing recent.{' '}
              <span className="faint">{(tuning.data && tuning.data.scope) || ''}</span>
            </p>
          ) : (
            <>
              <div className="table-wrap">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Rule</th>
                      <th>Where</th>
                      <th style={{ textAlign: 'right' }}>Seen</th>
                      <th>Last</th>
                    </tr>
                  </thead>
                  <tbody>
                    {candidates.slice(0, 8).map((m) => (
                      <tr key={m.key}>
                        <td>
                          <div className="mono">{m.rule_id}</div>
                          <div className="faint small truncate">{m.msg}</div>
                        </td>
                        <td className="truncate small">
                          {m.method} {m.path}
                        </td>
                        <td style={{ textAlign: 'right' }}>{fmtInt(m.count)}</td>
                        <td className="faint small" style={{ whiteSpace: 'nowrap' }}>
                          {fmtDate(m.last_seen)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <p className="faint small" style={{ marginTop: 10 }}>
                {(tuning.data && tuning.data.scope) || ''}
              </p>
            </>
          )}
        </Card>

        <Card
          title="Learning mode"
          description="Measure a window of real traffic, then decide about arming from what it would have cost."
          actions={
            <a className="btn sm" href="/bo/dashboard/extensions/cloud-apim/waf/learning" target="_blank" rel="noreferrer">
              <Icon name="external" />
              Open
            </a>
          }
        >
          {learning.loading ? (
            <Loading />
          ) : !preset.waf_config ? (
            <p className="muted small">Select a WAF config first.</p>
          ) : isLearning ? (
            <p className="muted">
              A window is running on <b>{current ? current.name : preset.waf_config}</b> on node{' '}
              <span className="mono">{learning.data.node}</span>. Stop it from the learning page to read its report.
            </p>
          ) : (
            <p className="muted">
              No window running on this config. Start one before arming a ruleset that has never seen this traffic.
            </p>
          )}
        </Card>
      </div>

      <EntitySection
        plural="waf-configs"
        title="WAF configs"
        description="Ordinary entities of the extension. Several workspaces can point at the same one."
        state={configs}
        selectedId={preset.waf_config}
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
      />
    </div>
  );
}
