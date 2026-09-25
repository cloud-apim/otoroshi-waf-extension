import { useEffect, useState } from 'react';
import { describeRule, labelOf, lookupRule } from '../lib/rules';

export function useRule(id) {
  const [rule, setRule] = useState(null);
  useEffect(() => {
    let alive = true;
    setRule(null);
    if (id !== undefined && id !== null) lookupRule(id).then((r) => alive && setRule(r));
    return () => {
      alive = false;
    };
  }, [id]);
  return rule;
}

/** Many ids at once, as a map id -> rule; the map fills in as the lookups land. */
export function useRules(ids) {
  const key = [...new Set((ids || []).map(Number))].sort((a, b) => a - b).join(',');
  const [rules, setRules] = useState(() => new Map());
  useEffect(() => {
    let alive = true;
    const list = key ? key.split(',').map(Number) : [];
    Promise.all(list.map((id) => lookupRule(id).then((r) => [id, r]))).then((entries) => alive && setRules(new Map(entries)));
    return () => {
      alive = false;
    };
  }, [key]);
  return rules;
}

/**
 * A rule id with what it means. `inline` puts the label next to the id; otherwise the id stands alone
 * and the label is its tooltip, for the places with no room for it.
 */
export function RuleId({ id, msg, inline = false }) {
  const rule = useRule(id);
  const label = labelOf(rule, msg);
  const title = [label, describeRule(rule)].filter(Boolean).join('\n') || undefined;
  if (!inline) {
    return (
      <span className={`rule-id mono${rule && rule.plumbing ? ' plumbing' : ''}`} title={title}>
        {id}
      </span>
    );
  }
  return (
    <span className="rule-inline" title={title}>
      <span className={`mono${rule && rule.plumbing ? ' faint' : ''}`}>{id}</span>
      {label && <span className={`rule-label${rule && rule.plumbing ? ' faint' : ''}`}>{label}</span>}
    </span>
  );
}

/** Just what a rule is, as text: its message, or the catalog's label when it has none. */
export function RuleText({ id, msg, fallback = '—' }) {
  const rule = useRule(id);
  return labelOf(rule, msg) || fallback;
}
