import { useStudio, useWorkspace } from '../App';
import { Icon } from '../components/icons';
import { Badge, Card, Loading, PageHeader, Segmented, useAsync, useToast } from '../components/ui';
import { canWrite } from '../lib/bootstrap';
import { Link } from '../lib/router';
import { Resources } from '../lib/entities';
import { fmtInt } from '../lib/format';
import { PRESET_DEFAULTS, replaceWorkspace, saveTable } from '../lib/workspaces';

/**
 * Reputation is a workspace decision only in its mode.
 *
 * Which feeds, which ASN databases and which CrowdSec bouncers are consulted is not per route: every
 * enabled one is always asked. Saying otherwise on this page would be a comfortable lie, so what it
 * shows is the mode — the part that is a choice here — and then what the install will consult.
 */
export function ReputationPage() {
  const { workspace, table } = useWorkspace();
  const studio = useStudio();
  const toast = useToast();
  const writable = canWrite();
  const preset = { ...PRESET_DEFAULTS, ...(workspace.preset || {}) };

  const sources = useAsync(
    () =>
      Promise.all([Resources.threatFeeds.list(), Resources.asnDatabases.list(), Resources.crowdsecBouncers.list()]).then(
        ([feeds, asn, crowdsec]) => ({ feeds, asn, crowdsec })
      ),
    []
  );

  const set = (patch) =>
    saveTable(replaceWorkspace(table, workspace.id, (w) => ({ ...w, preset: { ...preset, ...patch } })))
      .then(() => {
        studio.reload();
        toast.success('Saved');
      })
      .catch(toast.error);

  const enabled = (list) => (list || []).filter((e) => e.enabled !== false).length;

  return (
    <div className="content narrow">
      <PageHeader title="IP reputation" description="Score the caller against every enabled source. What is a choice here is whether that score can deny on its own." />

      <Card style={{ marginBottom: 18 }}>
        <div className="setting-row" style={{ paddingTop: 4 }}>
          <div>
            <b>Mode</b>
            <div className="muted small">
              <code>monitor</code> scores and reports without ever denying on its own, leaving the decision to the threat
              response. <code>block</code> lets reputation refuse a caller by itself.
            </div>
          </div>
          <div>
            {preset.reputation ? (
              <Segmented
                options={[
                  { value: 'block', label: 'Block' },
                  { value: 'monitor', label: 'Monitor' },
                ]}
                value={preset.reputation_mode}
                onChange={(v) => writable && set({ reputation_mode: v })}
              />
            ) : (
              <Badge>section off</Badge>
            )}
          </div>
        </div>
      </Card>

      <Card title="What this workspace will consult" description="Every enabled source, always. None of these is per route — see them under Install.">
        {sources.loading ? (
          <Loading />
        ) : sources.error ? (
          <p className="muted">Could not read the sources.</p>
        ) : (
          <>
            <div className="setting-row" style={{ paddingTop: 4 }}>
              <div>
                <b>Threat feeds</b>
                <div className="muted small">Address ranges from threat intelligence, refreshed on a schedule</div>
              </div>
              <div className="row between">
                <span>
                  <b>{fmtInt(enabled(sources.data.feeds))}</b> <span className="faint">enabled of {sources.data.feeds.length}</span>
                </span>
                <Link className="btn sm" to="/feeds">
                  Manage
                </Link>
              </div>
            </div>
            <div className="setting-row">
              <div>
                <b>ASN databases</b>
                <div className="muted small">Address to network, classified — hosting, VPN, mobile</div>
              </div>
              <div className="row between">
                <span>
                  <b>{fmtInt(enabled(sources.data.asn))}</b> <span className="faint">enabled of {sources.data.asn.length}</span>
                </span>
                <Link className="btn sm" to="/asn">
                  Manage
                </Link>
              </div>
            </div>
            <div className="setting-row">
              <div>
                <b>CrowdSec bouncers</b>
                <div className="muted small">The community decision list, both directions</div>
              </div>
              <div className="row between">
                <span>
                  <b>{fmtInt(enabled(sources.data.crowdsec))}</b> <span className="faint">enabled of {sources.data.crowdsec.length}</span>
                </span>
                <Link className="btn sm" to="/crowdsec">
                  Manage
                </Link>
              </div>
            </div>
          </>
        )}
      </Card>

      <p className="faint small" style={{ marginTop: 14 }}>
        Wanting different feeds on different routes is the case the preset does not cover: compose the reputation plugin
        by hand on those routes instead, and the table will stand down on them.
      </p>
    </div>
  );
}
