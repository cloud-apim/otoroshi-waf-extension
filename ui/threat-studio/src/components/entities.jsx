import { Icon } from './icons';
import { Badge, Card, Empty, ErrorAlert, Loading, StatusBadge } from './ui';

/**
 * The entities of the suite, listed.
 *
 * Deliberately not a second form for each of them: the backoffice already generates one from the
 * entity schema, and duplicating it here would mean two places to keep in step with the same
 * entities. What the studio adds is the wiring — which workspace uses which — and one click to the
 * form that already exists.
 */

export const BO_PATHS = {
  'waf-configs': 'wafconfigs',
  'waf-rulesets': 'wafrulesets',
  'threat-policies': 'threatpolicies',
  'bot-policies': 'botpolicies',
  'challenge-providers': 'challengeproviders',
  'honeypot-policies': 'honeypots',
  'threat-feeds': 'threatfeeds',
  'crowdsec-bouncers': 'crowdsecbouncers',
  'asn-databases': 'asndatabases',
};

export function boUrl(plural, id) {
  const path = BO_PATHS[plural];
  if (!path) return '/bo/dashboard';
  return id
    ? `/bo/dashboard/extensions/cloud-apim/waf/${path}/edit/${encodeURIComponent(id)}`
    : `/bo/dashboard/extensions/cloud-apim/waf/${path}`;
}

export function OpenInOtoroshi({ plural, id, label = 'Open' }) {
  return (
    <a className="btn sm" href={boUrl(plural, id)} target="_blank" rel="noreferrer" title="Open the full form in the Otoroshi admin console">
      <Icon name="external" />
      {label}
    </a>
  );
}

/**
 * `selectedId` marks the entity the workspace points at; `onSelect` is what makes that a choice
 * rather than a report.
 */
export function EntityList({
  state,
  plural,
  selectedId,
  onSelect,
  selectLabel = 'Use here',
  emptyTitle = 'Nothing yet',
  emptyBody,
  columns = [],
  writable = true,
}) {
  if (state.loading) return <Loading />;
  if (state.error) return <ErrorAlert error={state.error} />;
  const items = state.data || [];
  if (items.length === 0) {
    return (
      <Empty title={emptyTitle}>
        {emptyBody}
        <a className="btn primary" style={{ marginTop: 14 }} href={boUrl(plural)} target="_blank" rel="noreferrer">
          <Icon name="plus" />
          Create one in Otoroshi
        </a>
      </Empty>
    );
  }
  return (
    <div className="table-wrap">
      <table className="table">
        <thead>
          <tr>
            <th>Name</th>
            {columns.map((c) => (
              <th key={c.key}>{c.label}</th>
            ))}
            <th>State</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {items.map((e) => (
            <tr key={e.id}>
              <td>
                <div className="row" style={{ gap: 8 }}>
                  <span style={{ fontWeight: 500 }}>{e.name}</span>
                  {selectedId === e.id && <Badge kind="info">in use here</Badge>}
                </div>
                {e.description && <div className="faint small truncate">{e.description}</div>}
              </td>
              {columns.map((c) => (
                <td key={c.key}>{c.render ? c.render(e) : e[c.key] === undefined ? '—' : String(e[c.key])}</td>
              ))}
              <td>
                <StatusBadge enabled={e.enabled !== false} />
              </td>
              <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                {onSelect && writable && selectedId !== e.id && (
                  <button className="btn sm" style={{ marginRight: 6 }} onClick={() => onSelect(e.id)}>
                    {selectLabel}
                  </button>
                )}
                <OpenInOtoroshi plural={plural} id={e.id} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function SectionCard({ title, description, plural, children, actions }) {
  return (
    <Card
      className="flush"
      style={{ marginBottom: 18 }}
      title={title}
      description={description}
      actions={
        actions || (plural && (
          <a className="btn sm" href={boUrl(plural)} target="_blank" rel="noreferrer">
            <Icon name="plus" />
            New
          </a>
        ))
      }
    >
      {children}
    </Card>
  );
}
