import { useState } from 'react';
import { useStudio, useWorkspace } from '../App';
import { EntitySection } from '../components/entities';
import { Icon } from '../components/icons';
import { Badge, Card, PageHeader, useAsync, useToast } from '../components/ui';
import { canWrite } from '../lib/bootstrap';
import { Resources } from '../lib/entities';
import { PRESET_DEFAULTS, replaceWorkspace, saveTable } from '../lib/workspaces';

const DENYING = new Set(['deny', 'ban']);

function tierReached(tiers, score) {
  return (tiers || [])
    .filter((t) => (t.min_score ?? 0) <= score)
    .sort((a, b) => (b.min_score ?? 0) - (a.min_score ?? 0))[0];
}

/**
 * What a *lone* WAF verdict reaches under a policy, read straight from its weights and tiers.
 *
 * This is the same arithmetic the gateway runs, mirrored on the client so the score→action
 * relationship is visible before a request ever hits it — the gap that let a `waf:blocked` event
 * only ever get logged.
 */
function wafReach(policy) {
  const tiers = policy.tiers || [];
  const matchWeight = policy.waf_match_weight ?? 45;
  const blockWeight = policy.waf_block_weight ?? 90;
  const decisive = !!policy.waf_block_decisive;
  return {
    match: tierReached(tiers, Math.min(100, matchWeight)),
    matchWeight,
    block: decisive ? tierReached(tiers, 100) : tierReached(tiers, Math.min(100, blockWeight)),
    blockWeight,
    decisive,
    hasDeny: tiers.some((t) => DENYING.has(t.action)),
  };
}

/**
 * The policy turns the accumulated score into one graded action.
 *
 * It is the single thing standing between a fully configured workspace and a workspace that stops
 * nothing, which is why its dry run is called out rather than left in a form.
 */
export function PolicyPage() {
  const { workspace, table } = useWorkspace();
  const studio = useStudio();
  const toast = useToast();
  const writable = canWrite();
  const preset = { ...PRESET_DEFAULTS, ...(workspace.preset || {}) };

  const [editing, setEditing] = useState(null);
  const policies = useAsync(() => Resources.threatPolicies.list(), []);
  const current = (policies.data || []).find((p) => p.id === preset.threat_policy);

  const use = (id) =>
    saveTable(replaceWorkspace(table, workspace.id, (w) => ({ ...w, preset: { ...preset, threat_policy: id } })))
      .then(() => {
        studio.reload();
        toast.success('Threat policy selected');
      })
      .catch(toast.error);

  const tiers = (current && (current.tiers || current.levels)) || [];
  const reach = current ? wafReach(current) : null;
  const armedIf = (t) => (!current?.dry_run && t && DENYING.has(t.action) ? 'armed' : 'observing');

  return (
    <div className="content wide">
      <PageHeader title="Threat policy" description="Where the accumulated score becomes an action: log, tarpit, challenge, deny, ban." />

      <Card style={{ marginBottom: 18 }}>
        {!current ? (
          <div>
            <div className="row" style={{ gap: 8 }}>
              <b style={{ fontSize: 16 }}>Built-in policy</b>
              <Badge kind="warning">dry run</Badge>
            </div>
            <p className="muted small" style={{ marginTop: 6 }}>
              No policy is selected, so the built-in one applies: every decision is recorded and nothing is ever
              enforced. That is the right default and the wrong end state.
            </p>
          </div>
        ) : (
          <div className="row between" style={{ gap: 12, flexWrap: 'wrap' }}>
            <div>
              <div className="row" style={{ gap: 8 }}>
                <b style={{ fontSize: 16 }}>{current.name}</b>
                {current.dry_run ? <Badge kind="warning">dry run</Badge> : <Badge kind="positive">enforcing</Badge>}
              </div>
              <div className="muted small" style={{ marginTop: 4 }}>
                {current.dry_run
                  ? 'Every tier is computed and reported, and nothing is applied.'
                  : 'Tiers apply for real on every route this workspace governs.'}
              </div>
              {tiers.length > 0 && (
                <div className="posture" style={{ marginTop: 10 }}>
                  {tiers.map((t, i) => (
                    <span key={i} className={`mark ${current.dry_run ? 'observing' : 'armed'}`}>
                      ≥ {t.min_score ?? t.score ?? t.from ?? '?'} → {t.action}
                    </span>
                  ))}
                </div>
              )}
            </div>
            <button className="btn sm" onClick={() => setEditing(current)}>
              <Icon name="edit" />
              Edit tiers
            </button>
          </div>
        )}
      </Card>

      {current && reach && (
        <Card
          style={{ marginBottom: 18 }}
          title="What a WAF verdict reaches here"
          description="The gateway weighs a WAF block and a WAF match by this policy's own numbers. Here is where each one lands on its own, before any other detector adds to it."
        >
          <div className="posture">
            <span className={`mark ${armedIf(reach.match)}`}>
              lone WAF match ({reach.matchWeight}) → {reach.match ? reach.match.action : 'nothing'}
            </span>
            <span className={`mark ${armedIf(reach.block)}`}>
              lone WAF block {reach.decisive ? '· decisive' : `(${reach.blockWeight})`} → {reach.block ? reach.block.action : 'nothing'}
            </span>
          </div>
          {!reach.hasDeny ? (
            <p className="muted small" style={{ marginTop: 12 }}>
              <Badge kind="warning">no denying tier</Badge> Nothing here denies or bans, so no verdict can refuse a
              request — only log, tarpit or challenge. Add a deny or ban tier for a block to be able to stop anything.
            </p>
          ) : reach.block && !DENYING.has(reach.block.action) ? (
            <p className="muted small" style={{ marginTop: 12 }}>
              <Badge kind="warning">a WAF block can't refuse on its own</Badge> Even a full WAF block only reaches
              “{reach.block.action}” here. Raise the WAF block weight to your deny/ban tier, or turn on “WAF block is
              decisive”, for the engine's own block to stop the request.
            </p>
          ) : (
            <p className="muted small" style={{ marginTop: 12 }}>
              A WAF block reaches “{reach.block.action}” on its own{current.dry_run ? ', once this policy is out of dry run' : ''}. A
              match escalates when another detector corroborates it.
            </p>
          )}
        </Card>
      )}

      <EntitySection
        plural="threat-policies"
        title="Threat policies"
        description="Shared by the threat gate and the threat response of every workspace that points at them."
        state={policies}
        selectedId={preset.threat_policy}
        onSelect={use}
        writable={writable}
        workspaceId={workspace.id}
        kind="policy"
        createLabel="New policy"
        emptyTitle="No threat policy"
        emptyBody={<p className="muted">Without one the built-in dry-run policy applies: everything is recorded, nothing is enforced.</p>}
        columns={[
          {
            key: 'dry_run',
            label: 'Mode',
            render: (e) => (e.dry_run ? <Badge kind="warning">dry run</Badge> : <Badge kind="positive">enforcing</Badge>),
          },
          { key: 'tiers', label: 'Tiers', render: (e) => (e.tiers || []).length },
        ]}
        editing={editing}
        onEditingChange={setEditing}
      />
    </div>
  );
}
