import { useEffect, useState } from 'react';
import { useStudio, useWorkspace } from '../App';
import { Icon } from '../components/icons';
import {
  Badge,
  Card,
  Empty,
  Field,
  PageHeader,
  Select,
  TextInput,
  Toggle,
  useToast,
} from '../components/ui';
import { canWrite } from '../lib/bootstrap';
import { Link } from '../lib/router';
import { emptyTarget, replaceWorkspace, saveTable } from '../lib/workspaces';

/**
 * The selector, written out.
 *
 * Every target must match, and a workspace with no target at all claims every route the rules above
 * it have not taken. Both halves of a target use the predicate language of the platform, so what is
 * written here reads exactly like a predicate anywhere else in Otoroshi.
 */

const OPERATORS = [
  { value: 'Contains(x)', label: 'Contains — the value, or one element of an array' },
  { value: 'ContainedIn(a, b)', label: 'ContainedIn — one of a listed set' },
  { value: 'Regex(.*)', label: 'Regex' },
  { value: 'Wildcard(*.example.com)', label: 'Wildcard' },
  { value: 'StartsWith(x)', label: 'StartsWith' },
  { value: 'IsDefined()', label: 'IsDefined — the field is there' },
  { value: 'NotDefined()', label: 'NotDefined — the field is absent' },
  { value: 'Not(x)', label: 'Not — different from' },
  { value: 'ContainsNot(x)', label: 'ContainsNot' },
  { value: 'NotContainedIn(a, b)', label: 'NotContainedIn' },
];

const PATH_SUGGESTIONS = [
  { value: '$.tags', label: '$.tags — the route tags' },
  { value: '$.groups', label: '$.groups — the route groups' },
  { value: '$.id', label: '$.id — the route id' },
  { value: '$.name', label: '$.name — the route name' },
  { value: '$.metadata.env', label: '$.metadata.<key> — a metadata entry' },
];

function TargetRow({ target, onChange, onRemove, disabled }) {
  const mode = target.path ? 'path' : 'expression';
  return (
    <div className="card tight" style={{ marginBottom: 10 }}>
      <div className="row" style={{ gap: 10, alignItems: 'flex-start', flexWrap: 'wrap' }}>
        <Select
          value={mode}
          disabled={disabled}
          onChange={(v) =>
            onChange(v === 'path' ? { path: '$.tags', expression: null, value: target.value } : { path: null, expression: '${route.metadata.env}', value: target.value })
          }
          options={[
            { value: 'path', label: 'Route field' },
            { value: 'expression', label: 'Expression' },
          ]}
        />
        {mode === 'path' ? (
          <div className="grow" style={{ minWidth: 200 }}>
            <TextInput
              value={target.path || ''}
              disabled={disabled}
              onChange={(v) => onChange({ ...target, path: v })}
              placeholder="$.tags"
              list="path-suggestions"
            />
          </div>
        ) : (
          <div className="grow" style={{ minWidth: 200 }}>
            <TextInput
              value={target.expression || ''}
              disabled={disabled}
              onChange={(v) => onChange({ ...target, expression: v })}
              placeholder="${route.metadata.env}"
            />
          </div>
        )}
        <div className="grow" style={{ minWidth: 200 }}>
          <TextInput
            value={typeof target.value === 'string' ? target.value : JSON.stringify(target.value ?? '')}
            disabled={disabled}
            onChange={(v) => onChange({ ...target, value: v })}
            placeholder="Contains(public)"
          />
        </div>
        <button className="copy-btn" title="Remove" disabled={disabled} onClick={onRemove}>
          <Icon name="trash" />
        </button>
      </div>
      {mode === 'expression' && (
        <p className="faint small" style={{ marginTop: 8 }}>
          Presets expand before any plugin runs, so <code>${'{apikey…}'}</code> and <code>${'{user…}'}</code> are never
          resolved here. The expression language also has no accessor for tags or groups — use a route field for those.
        </p>
      )}
    </div>
  );
}

export function ScopePage() {
  const { workspace, table } = useWorkspace();
  const studio = useStudio();
  const toast = useToast();
  const writable = canWrite();
  const [targets, setTargets] = useState(workspace.targets || []);
  const [skip, setSkip] = useState(!!workspace.skip);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setTargets(workspace.targets || []);
    setSkip(!!workspace.skip);
  }, [workspace.id, JSON.stringify(workspace.targets), workspace.skip]);

  const dirty =
    JSON.stringify(targets) !== JSON.stringify(workspace.targets || []) || skip !== !!workspace.skip;

  const save = () => {
    setBusy(true);
    saveTable(replaceWorkspace(table, workspace.id, (w) => ({ ...w, targets, skip })))
      .then(() => {
        studio.reload();
        toast.success('Scope saved');
      })
      .catch(toast.error)
      .finally(() => setBusy(false));
  };

  return (
    <div className="content narrow">
      <datalist id="path-suggestions">
        {PATH_SUGGESTIONS.map((p) => (
          <option key={p.value} value={p.value} />
        ))}
      </datalist>

      <PageHeader title="Scope" description="Which routes this workspace claims. Every target must match; a workspace with none claims every route the rules above it have not taken.">
        {writable && dirty && (
          <button className="btn primary" onClick={save} disabled={busy}>
            Save
          </button>
        )}
      </PageHeader>

      <Card className="tight" style={{ marginBottom: 18 }}>
        <div className="setting-row" style={{ paddingTop: 4 }}>
          <div>
            <b>Leave these routes alone</b>
            <div className="muted small">
              Match, lay down nothing at all, and stop the search. This is the opt-out: put such a workspace above the
              ones that protect.
            </div>
          </div>
          <div>
            <Toggle value={skip} onChange={setSkip} disabled={!writable} />
          </div>
        </div>
      </Card>

      <Card
        title="Targets"
        description="A route field is read off the route entity, so arrays stay arrays. An expression is resolved to a string and then compared."
        actions={
          writable && (
            <button className="btn sm" onClick={() => setTargets(targets.concat([emptyTarget()]))}>
              <Icon name="plus" />
              Add target
            </button>
          )
        }
      >
        {targets.length === 0 ? (
          <Empty title="No target">
            <p className="muted">
              This workspace claims <b>every route</b> the rules above it have not taken. That is the usual shape for the
              last row of the table, and a trap anywhere else — it shadows everything below it.
            </p>
          </Empty>
        ) : (
          targets.map((t, i) => (
            <TargetRow
              key={i}
              target={t}
              disabled={!writable}
              onChange={(next) => setTargets(targets.map((x, j) => (j === i ? next : x)))}
              onRemove={() => setTargets(targets.filter((_, j) => j !== i))}
            />
          ))
        )}

        <details style={{ marginTop: 12 }}>
          <summary className="muted small" style={{ cursor: 'pointer' }}>
            The vocabulary of values
          </summary>
          <ul className="muted small" style={{ marginTop: 10, paddingLeft: 18 }}>
            {OPERATORS.map((o) => (
              <li key={o.value} style={{ padding: '2px 0' }}>
                <code>{o.value}</code> — {o.label.split('—')[1] || o.label}
              </li>
            ))}
            <li style={{ padding: '2px 0' }}>
              <code>Size(n)</code>, <code>SizeGt(n)</code>, <code>SizeLt(n)</code> — on an array
            </li>
          </ul>
          <p className="faint small" style={{ marginTop: 8 }}>
            A target naming neither a field nor an expression, or carrying no value, matches nothing — never everything.
          </p>
        </details>
      </Card>

      <Card style={{ marginTop: 18 }} title="What it claims right now">
        <div className="row" style={{ gap: 10, flexWrap: 'wrap' }}>
          <Badge kind={(workspace.claims || []).length > 0 ? 'positive' : 'negative'}>
            {(workspace.claims || []).length} claimed
          </Badge>
          {(workspace.matches || []).length > (workspace.claims || []).length && (
            <Badge kind="warning">
              {(workspace.matches || []).length - (workspace.claims || []).length} matched but taken above
            </Badge>
          )}
          <Link className="btn sm" to={`/workspaces/${workspace.id}/routes`}>
            See the routes
          </Link>
        </div>
        {dirty && (
          <p className="faint small" style={{ marginTop: 10 }}>
            These counts are for the scope as saved. Save to resolve the new one.
          </p>
        )}
      </Card>
    </div>
  );
}
