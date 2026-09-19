import { useState } from 'react';
import { Icon } from './icons';
import { Field, LinesInput, NumberInput, Select, TextArea, TextInput, Toggle, useAsync } from './ui';

/**
 * A declarative form over an entity.
 *
 * The entities of the suite are plain json, and the backoffice already generates a form for each of
 * them from its schema. What this adds is not another generator but an *edited* view: the fields
 * that matter, in the order they are reasoned about, with the help text that says what the number
 * actually does. Anything it does not cover is one click away in the full form.
 */

function Row({ field, children, top }) {
  return (
    <div className={`setting-row ${top ? 'top' : ''}`}>
      <div>
        <b>{field.label}</b>
        {field.help && <div className="muted small">{field.help}</div>}
      </div>
      <div style={{ minWidth: 0 }}>{children}</div>
    </div>
  );
}

/** A list of multi-line entries, which is what a SecLang rule needs: one rule may span lines. */
function Strings({ value, onChange, placeholder, rows = 2, addLabel = 'Add' }) {
  const items = value || [];
  return (
    <div>
      {items.map((item, i) => (
        <div key={i} className="row" style={{ gap: 6, alignItems: 'flex-start', marginBottom: 6 }}>
          <TextArea
            className="mono grow"
            rows={Math.max(rows, String(item || '').split('\n').length)}
            value={item}
            placeholder={placeholder}
            onChange={(v) => onChange(items.map((x, j) => (j === i ? v : x)))}
          />
          <div className="row" style={{ gap: 2 }}>
            <button
              className="copy-btn"
              title="Move up"
              disabled={i === 0}
              onClick={() => {
                const next = items.slice();
                next.splice(i - 1, 0, next.splice(i, 1)[0]);
                onChange(next);
              }}
            >
              <Icon name="arrowUp" />
            </button>
            <button className="copy-btn" title="Remove" onClick={() => onChange(items.filter((_, j) => j !== i))}>
              <Icon name="trash" />
            </button>
          </div>
        </div>
      ))}
      <button className="btn sm" onClick={() => onChange(items.concat(['']))}>
        <Icon name="plus" />
        {addLabel}
      </button>
    </div>
  );
}

/** A list of sub-objects — threat tiers, bot rules, canary tokens, ASN categories. */
function Objects({ value, onChange, fields, addLabel = 'Add', empty }) {
  const items = value || [];
  return (
    <div>
      {items.length === 0 && empty && <p className="faint small" style={{ marginBottom: 8 }}>{empty}</p>}
      {items.map((item, i) => (
        <div key={i} className="card tight" style={{ marginBottom: 8 }}>
          <div className="row" style={{ gap: 8, alignItems: 'flex-end', flexWrap: 'wrap' }}>
            {fields.map((f) => (
              <div key={f.key} style={{ minWidth: f.width || 120, flex: f.grow ? '1 1 160px' : '0 0 auto' }}>
                <label className="small muted" style={{ display: 'block', marginBottom: 3 }}>
                  {f.label}
                </label>
                <FieldInput
                  field={f}
                  value={item[f.key]}
                  onChange={(v) => onChange(items.map((x, j) => (j === i ? { ...x, [f.key]: v } : x)))}
                />
              </div>
            ))}
            <button className="copy-btn" title="Remove" onClick={() => onChange(items.filter((_, j) => j !== i))}>
              <Icon name="trash" />
            </button>
          </div>
        </div>
      ))}
      <button className="btn sm" onClick={() => onChange(items.concat([{ ...(fields.reduce((a, f) => ({ ...a, [f.key]: f.default ?? '' }), {})) }]))}>
        <Icon name="plus" />
        {addLabel}
      </button>
    </div>
  );
}

/** Ids of other entities, picked rather than typed. */
function Refs({ value, onChange, loader, placeholder }) {
  const options = useAsync(() => loader(), []);
  const items = value || [];
  const all = options.data || [];
  const remaining = all.filter((e) => !items.includes(e.id));
  return (
    <div>
      {items.map((id, i) => {
        const entity = all.find((e) => e.id === id);
        return (
          <div key={id} className="row between" style={{ gap: 8, padding: '4px 0' }}>
            <span className="row" style={{ gap: 8, minWidth: 0 }}>
              <span className="rank">{i + 1}</span>
              <span className="truncate">{entity ? entity.name : id}</span>
              {!entity && <span className="badge negative">missing</span>}
            </span>
            <div className="row" style={{ gap: 2 }}>
              <button
                className="copy-btn"
                title="Move up"
                disabled={i === 0}
                onClick={() => {
                  const next = items.slice();
                  next.splice(i - 1, 0, next.splice(i, 1)[0]);
                  onChange(next);
                }}
              >
                <Icon name="arrowUp" />
              </button>
              <button className="copy-btn" title="Remove" onClick={() => onChange(items.filter((x) => x !== id))}>
                <Icon name="trash" />
              </button>
            </div>
          </div>
        );
      })}
      {remaining.length > 0 && (
        <Select
          value=""
          placeholder={placeholder || 'Add…'}
          onChange={(v) => v && onChange(items.concat([v]))}
          options={remaining.map((e) => ({ value: e.id, label: e.name }))}
        />
      )}
    </div>
  );
}

function JsonField({ value, onChange, rows = 6 }) {
  const [text, setText] = useState(() => JSON.stringify(value ?? {}, null, 2));
  const [error, setError] = useState(null);
  return (
    <div>
      <TextArea
        className="mono"
        rows={rows}
        value={text}
        onChange={(v) => {
          setText(v);
          try {
            onChange(JSON.parse(v));
            setError(null);
          } catch (e) {
            setError('invalid json');
          }
        }}
      />
      {error && <div className="error small">{error}</div>}
    </div>
  );
}

export function FieldInput({ field, value, onChange }) {
  switch (field.type) {
    case 'bool':
      return <Toggle value={!!value} onChange={onChange} />;
    case 'number':
      return <NumberInput value={value} onChange={onChange} min={field.min} max={field.max} placeholder={field.placeholder} />;
    case 'select':
      return <Select value={value ?? ''} onChange={onChange} options={field.options} placeholder={field.placeholder} />;
    case 'textarea':
      return <TextArea value={value} onChange={onChange} rows={field.rows || 4} placeholder={field.placeholder} className={field.mono ? 'mono' : ''} />;
    case 'lines':
      return <LinesInput value={value} onChange={onChange} rows={field.rows || 4} placeholder={field.placeholder} />;
    case 'numbers':
      // stored as numbers, edited as lines: a list of AS numbers written back as strings is refused
      return (
        <LinesInput
          value={(value || []).map(String)}
          onChange={(lines) => onChange(lines.map((l) => Number(l)).filter((n) => Number.isFinite(n)))}
          rows={field.rows || 4}
          placeholder={field.placeholder}
        />
      );
    case 'strings':
      return <Strings value={value} onChange={onChange} placeholder={field.placeholder} rows={field.rows} addLabel={field.addLabel} />;
    case 'objects':
      return <Objects value={value} onChange={onChange} fields={field.fields} addLabel={field.addLabel} empty={field.empty} />;
    case 'refs':
      return <Refs value={value} onChange={onChange} loader={field.loader} placeholder={field.placeholder} />;
    case 'ref':
      return <RefField value={value} onChange={onChange} field={field} />;
    case 'json':
      return <JsonField value={value} onChange={onChange} rows={field.rows} />;
    case 'secret':
      return <TextInput value={value} onChange={onChange} placeholder={field.placeholder} type="password" autoComplete="new-password" />;
    default:
      return <TextInput value={value} onChange={onChange} placeholder={field.placeholder} />;
  }
}

function RefField({ value, onChange, field }) {
  const options = useAsync(() => field.loader(), []);
  return (
    <Select
      value={value ?? ''}
      onChange={(v) => onChange(v || null)}
      placeholder={field.placeholder || 'None'}
      options={(options.data || []).map((e) => ({ value: e.id, label: e.name }))}
    />
  );
}

const WIDE = new Set(['strings', 'objects', 'lines', 'numbers', 'json', 'textarea', 'refs']);

export function FormFields({ fields, values, onChange }) {
  return (
    <>
      {fields
        .filter((f) => !f.when || f.when(values))
        .map((f) => (
          <Row key={f.key} field={f} top={WIDE.has(f.type)}>
            <FieldInput field={f} value={values[f.key]} onChange={(v) => onChange({ ...values, [f.key]: v })} />
          </Row>
        ))}
    </>
  );
}

/** The same fields, stacked rather than in two columns — for a creation modal. */
export function FormStack({ fields, values, onChange }) {
  return (
    <>
      {fields
        .filter((f) => !f.when || f.when(values))
        .map((f) =>
          f.type === 'bool' ? (
            <div className="setting-row" key={f.key} style={{ paddingTop: 4 }}>
              <div>
                <b>{f.label}</b>
                {f.help && <div className="muted small">{f.help}</div>}
              </div>
              <Toggle value={!!values[f.key]} onChange={(v) => onChange({ ...values, [f.key]: v })} />
            </div>
          ) : (
            <Field key={f.key} label={f.label} hint={f.help}>
              <FieldInput field={f} value={values[f.key]} onChange={(v) => onChange({ ...values, [f.key]: v })} />
            </Field>
          )
        )}
    </>
  );
}
