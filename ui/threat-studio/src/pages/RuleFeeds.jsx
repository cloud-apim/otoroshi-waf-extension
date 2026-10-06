import { EntitySection } from '../components/entities';
import { Icon } from '../components/icons';
import { Badge, Card, PageHeader, useAsync, useToast } from '../components/ui';
import { Resources } from '../lib/entities';
import { RuleFeeds } from '../lib/security';

/**
 * Rule packs and virtual patches a feed delivers (WAF-2, WAF-3).
 *
 * Each pack of an installed version is a managed WAF ruleset, listed with the others: a WAF config
 * references it like any ruleset, and a new version replaces its rules under the same id.
 */
export function RuleFeedsPage() {
  const toast = useToast();
  const feeds = useAsync(() => Resources.ruleFeeds.list(), []);
  const status = useAsync(() => RuleFeeds.status(), []);

  const stateOf = (id) => (((status.data && status.data.feeds) || []).find((f) => f.id === id) || {}).state;

  const act = (action, feed) =>
    RuleFeeds[action]({ id: feed.id })
      .then((r) => {
        if (r.done) toast.success(`${feed.name}: ${action} done`);
        else toast.error(r.error || `${action} failed`);
        status.reload();
        feeds.reload();
      })
      .catch(toast.error);

  return (
    <div className="content wide">
      <PageHeader
        title="Rule feeds"
        description="Signed rule packs and virtual patches, installed once this gateway has checked them."
      />
      <Card style={{ marginBottom: 18 }}>
        <p className="muted">
          A version is installed only when its signature verifies with a trusted key, every pack compiles here, and every
          pack passes its own tests against this engine. It never goes back to an older version on its own; a rollback
          puts the previous one back and holds there until a newer version comes.
        </p>
      </Card>
      <EntitySection
        plural="rule-feeds"
        title="Feeds"
        state={feeds}
        createLabel="New feed"
        emptyTitle="No rule feed"
        emptyBody={<p className="muted">A feed is a URL serving a signed bundle of rule packs, and the keys it is signed with.</p>}
        columns={[
          {
            key: 'active',
            label: 'Installed',
            render: (e) => {
              const st = stateOf(e.id);
              return st && st.active ? `${st.active.version} · ${(st.active.packs || []).length} packs` : 'nothing yet';
            },
          },
          {
            key: 'pending',
            label: 'Waiting',
            render: (e) => {
              const st = stateOf(e.id);
              return st && st.pending ? <Badge kind="info">{st.pending.version}</Badge> : '-';
            },
          },
          {
            key: 'error',
            label: 'Last check',
            render: (e) => {
              const st = stateOf(e.id);
              if (!st || !st.last_check_at) return 'never';
              return st.last_error ? <Badge kind="warning" title={st.last_error}>refused</Badge> : <Badge kind="positive">ok</Badge>;
            },
          },
          {
            key: 'actions',
            label: '',
            render: (e) => (
              <span className="row" style={{ gap: 4 }}>
                <button className="copy-btn" title="Refresh now" onClick={() => act('refresh', e)}>
                  <Icon name="refresh" />
                </button>
                <button className="copy-btn" title="Promote the waiting version" onClick={() => act('promote', e)}>
                  <Icon name="check" />
                </button>
                <button className="copy-btn" title="Roll back to the previous version" onClick={() => act('rollback', e)}>
                  <Icon name="arrowLeft" />
                </button>
              </span>
            ),
          },
        ]}
      />
    </div>
  );
}
