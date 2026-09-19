import { useEffect, useMemo, useState } from 'react';
import { FormFields } from './form';
import { Icon } from './icons';
import { Badge, Drawer, ErrorAlert, Loading, useConfirm, useToast } from './ui';
import { compileConfig, createEntity } from '../lib/create';
import { schemaOf } from '../lib/schemas';

/* a dotted key reaches into a nested object, which is how the CRS dials are stored */
function get(obj, path) {
  return path.split('.').reduce((o, k) => (o === null || o === undefined ? undefined : o[k]), obj);
}

function set(obj, path, value) {
  const [head, ...rest] = path.split('.');
  if (rest.length === 0) return { ...obj, [head]: value };
  return { ...obj, [head]: set(obj[head] || {}, rest.join('.'), value) };
}

/** The form works on flat keys; the entity keeps its shape. */
function flatten(entity, fields) {
  return fields.reduce((acc, f) => ({ ...acc, [f.key]: get(entity, f.key) }), {});
}

function unflatten(entity, values) {
  return Object.entries(values).reduce((acc, [key, value]) => set(acc, key, value), entity);
}

function CompileBadge({ entity }) {
  const [state, setState] = useState(null);
  useEffect(() => {
    let alive = true;
    setState('running');
    compileConfig({ rules: entity.rules || [], rulesets: entity.rulesets || [], crs: entity.crs || {} })
      .then((r) => alive && setState(r))
      .catch(() => alive && setState(null));
    return () => {
      alive = false;
    };
  }, [JSON.stringify([entity.rules, entity.rulesets, entity.crs])]);

  if (state === 'running') return <Badge>compiling…</Badge>;
  if (!state) return null;
  if (!state.done) return <Badge kind="negative" title={state.error}>does not compile</Badge>;
  if ((state.missing_rulesets || []).length > 0) {
    return (
      <Badge kind="warning" title={`These rulesets do not exist: ${state.missing_rulesets.join(', ')}`}>
        {state.missing_rulesets.length} missing ruleset{state.missing_rulesets.length === 1 ? '' : 's'}
      </Badge>
    );
  }
  if (state.crs_ignored) return <Badge kind="warning" title={state.warning}>CRS dials ignored</Badge>;
  return <Badge kind="positive">compiles</Badge>;
}

/**
 * Editing an entity of the suite, in place.
 *
 * The form is an edited view of the entity rather than a generated one — see `lib/schemas.js`. What
 * it does not cover stays reachable through the full backoffice form, and the button for that is in
 * the footer rather than hidden, because an edited view is only honest if the whole one is one click
 * away.
 */
export function EntityEditor({ plural, entity, open, onClose, onSaved, onDeleted, writable = true, mode = 'edit' }) {
  const schema = schemaOf(plural);
  const toast = useToast();
  const confirm = useConfirm();
  const fields = useMemo(() => (schema ? schema.sections.flatMap((s) => s.fields) : []), [schema]);
  const [values, setValues] = useState({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (entity) setValues(flatten(entity, fields));
    setError(null);
  }, [entity && entity.id, open]);

  if (!open || !entity) return null;
  if (!schema) return null;

  const creating = mode === 'create';
  const draft = unflatten(entity, values);
  const dirty = JSON.stringify(draft) !== JSON.stringify(entity);
  const named = !!(draft.name || '').trim();

  const save = () => {
    setBusy(true);
    setError(null);
    const write = creating ? createEntity(plural, draft) : schema.resource().update(draft);
    write
      .then(() => {
        toast.success(creating ? `${schema.label} created` : `${schema.label} saved`);
        onClose();
        if (onSaved) onSaved(draft);
      })
      .catch((e) => {
        setError(e);
        toast.error(e);
      })
      .finally(() => setBusy(false));
  };

  const remove = async () => {
    const ok = await confirm({
      title: `Delete ${entity.name}?`,
      message:
        'Anything pointing at it keeps its reference and reports it as missing rather than failing — but it will protect less than it claims until the reference is fixed.',
      danger: true,
      confirmLabel: 'Delete',
    });
    if (!ok) return;
    setBusy(true);
    schema
      .resource()
      .delete(entity.id)
      .then(() => {
        toast.success(`${schema.label} deleted`);
        onClose();
        if (onDeleted) onDeleted(entity);
      })
      .catch(toast.error)
      .finally(() => setBusy(false));
  };

  return (
    <Drawer
      open
      className="wide"
      title={
        <span className="row" style={{ gap: 10 }}>
          {creating ? `New ${schema.label.toLowerCase()}` : entity.name}
          {plural === 'waf-configs' && <CompileBadge entity={draft} />}
        </span>
      }
      onClose={onClose}
      footer={
        <>
          <div className="row" style={{ gap: 8 }}>
            {writable && !creating && (
              <button className="btn danger" onClick={remove} disabled={busy}>
                <Icon name="trash" />
                Delete
              </button>
            )}
          </div>
          <div className="row" style={{ gap: 8 }}>
            <button className="btn" onClick={onClose} disabled={busy}>
              {creating ? 'Cancel' : dirty ? 'Discard' : 'Close'}
            </button>
            {writable && (
              <button className="btn primary" onClick={save} disabled={busy || !named || (!creating && !dirty)}>
                {busy ? (creating ? 'Creating…' : 'Saving…') : creating ? 'Create' : 'Save'}
              </button>
            )}
          </div>
        </>
      }
    >
      {error && <ErrorAlert error={error} />}
      <div>
        <div className="mono faint small" style={{ marginBottom: 12 }}>
          {entity.id}
        </div>
        {creating && (
          <p className="faint small" style={{ marginTop: -6, marginBottom: 14 }}>
            Nothing is written until you create it. Everything not shown here takes the entity defaults, and stays
            reachable in the full form afterwards.
          </p>
        )}
        {schema.sections.map((section) => (
          <div className="form-section" key={section.title}>
            <h3>{section.title}</h3>
            {section.description && <p>{section.description}</p>}
            <FormFields fields={section.fields} values={values} onChange={setValues} />
          </div>
        ))}
      </div>
    </Drawer>
  );
}
