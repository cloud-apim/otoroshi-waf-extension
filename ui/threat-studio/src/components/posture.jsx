import { Badge } from './ui';
import { Icon } from './icons';

/**
 * A posture, read across a row of marks.
 *
 * The distinction the whole console is built on shows up here: a mark is *on* when the section is
 * configured and *armed* when what it is configured to do can actually stop a request. A route with
 * every section on and nothing armed stops nothing, and reading that as "protected" is how a rollout
 * stalls for six months.
 */
export function PostureMarks({ posture }) {
  if (!posture) return null;
  const marks = [
    posture.gate && { label: 'Gate', cls: 'on' },
    posture.bots && { label: 'Bots', cls: 'on' },
    posture.reputation && {
      label: `Reputation · ${posture.reputation}`,
      cls: posture.reputation === 'block' ? 'armed' : 'observing',
    },
    posture.fail2ban && {
      label: `Fail2ban · ${posture.fail2ban}`,
      cls: posture.fail2ban === 'armed' ? 'armed' : 'observing',
    },
    posture.waf && {
      label: `WAF · ${posture.waf_blocking ? 'block' : 'monitor'}`,
      cls: posture.waf_blocking ? 'armed' : 'observing',
    },
    posture.response && {
      label: `Response · ${posture.policy_dry_run ? 'dry run' : 'armed'}`,
      cls: posture.policy_dry_run ? 'observing' : 'armed',
    },
  ].filter(Boolean);
  if (marks.length === 0) return <span className="faint small">nothing attached</span>;
  return (
    <span className="posture">
      {marks.map((m) => (
        <span key={m.label} className={`mark ${m.cls}`}>
          {m.label}
        </span>
      ))}
    </span>
  );
}

export function CoverageBadge({ posture }) {
  if (!posture) return null;
  if (posture.enforcing) return <Badge kind="positive">enforcing</Badge>;
  if (posture.covered) return <Badge kind="warning">observing</Badge>;
  return <Badge kind="negative">unprotected</Badge>;
}

/** Where a route's protection comes from — its own slots, the table, or nowhere. */
export function SourceBadge({ posture }) {
  if (!posture) return null;
  if (posture.self_managed) return <Badge title="This route carries its own fabric, so the table stands down on it">own slots</Badge>;
  if (posture.workspace) return <Badge kind="info">{posture.workspace_name || posture.workspace}</Badge>;
  if (posture.covered) return <Badge>own slots</Badge>;
  return <Badge kind="negative">none</Badge>;
}

export function Lint({ items }) {
  if (!items || items.length === 0) return null;
  const icon = { error: 'alert', warning: 'alert', info: 'info' };
  return (
    <div className="lint">
      {items.map((i, idx) => (
        <div key={idx} className={`item ${i.kind}`}>
          <Icon name={icon[i.kind] || 'info'} size={14} />
          <span>{i.text}</span>
        </div>
      ))}
    </div>
  );
}
