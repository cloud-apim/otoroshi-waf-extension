import { Fragment, useMemo } from 'react';
import { seriesColor } from './charts';
import { Icon } from './icons';
import { Empty, Segmented } from './ui';
import { fmtInt, fmtNumber, fmtPercent } from '../lib/format';
import { itemsOf, PERIODS } from '../lib/analytics';

export function PeriodPicker({ value, onChange }) {
  return <Segmented options={PERIODS.map((p) => ({ value: p.value, label: p.label.replace('Past ', '') }))} value={value} onChange={onChange} />;
}

/**
 * A number, with what it was over the period before.
 *
 * The comparison is the point: a security console that only shows today's count cannot tell a quiet
 * week from a detector that stopped firing.
 */
export function Kpi({ label, value, previous, format = fmtInt, invert = false, hint }) {
  const delta = previous === null || previous === undefined || previous === 0 ? null : (value - previous) / previous;
  const up = delta !== null && delta > 0;
  const good = delta === null ? null : invert ? !up : up;
  return (
    <div className="kpi" title={hint}>
      <div className="top">
        <span className="label">{label}</span>
      </div>
      <span className="value">{format(value)}</span>
      {delta !== null && Math.abs(delta) >= 0.005 && (
        <span className={`delta ${good ? 'up' : 'down'}`}>
          {up ? '+' : ''}
          {fmtPercent(delta, 0)} <span className="vs">vs previous</span>
        </span>
      )}
    </div>
  );
}

/**
 * A ranked list drawn as bars.
 *
 * Preferred to a bar chart for top-N: the labels here are ip addresses, rule ids and tags, which are
 * long, and a horizontal list keeps them readable at any width.
 */
export function Ranked({ items, format = fmtInt, empty = 'Nothing in this period', onPick, max = 10, labelFn, valueFn, renderLabel }) {
  // never name these `labelOf`/`valueOf`: `valueOf` is inherited from Object.prototype, so
  // destructuring it from props yields that method rather than undefined, and calling it throws
  const rows = (items || []).slice(0, max).map((i) => ({
    key: i.key,
    label: (labelFn ? labelFn(i) : i.label || i.key) || '(unknown)',
    value: Number(valueFn ? valueFn(i) : i.value) || 0,
  }));
  const top = Math.max(1, ...rows.map((r) => r.value));
  if (rows.length === 0) return <div className="chart-empty" style={{ height: 120 }}>{empty}</div>;
  return (
    <div className="ranked">
      {rows.map((r, idx) => (
        <div key={`${r.key}-${idx}`}>
          <div className="row">
            {onPick ? (
              <button className="name" title={renderLabel ? undefined : r.label} onClick={() => onPick(r)}>
                {renderLabel ? renderLabel(r) : r.label}
              </button>
            ) : (
              <span className="name" title={renderLabel ? undefined : r.label}>
                {renderLabel ? renderLabel(r) : r.label}
              </span>
            )}
            <span className="value">{format(r.value)}</span>
          </div>
          <div className="bar">
            <i style={{ width: `${Math.max(2, (r.value / top) * 100)}%`, background: seriesColor(0, r.label) }} />
          </div>
        </div>
      ))}
    </div>
  );
}

/** A donut, and its legend as a list — the share is what is read, the count is the detail. */
export function Donut({ items, size = 132, format = fmtInt, empty = 'Nothing in this period' }) {
  const rows = (items || []).map((i, idx) => ({ ...i, value: Number(i.value) || 0, idx }));
  const total = rows.reduce((a, r) => a + r.value, 0);
  const arcs = useMemo(() => {
    if (total <= 0) return [];
    const r = size / 2 - 10;
    const c = size / 2;
    let angle = -Math.PI / 2;
    return rows.map((row) => {
      const sweep = (row.value / total) * Math.PI * 2;
      const from = angle;
      angle += sweep;
      const large = sweep > Math.PI ? 1 : 0;
      const x1 = c + r * Math.cos(from);
      const y1 = c + r * Math.sin(from);
      const x2 = c + r * Math.cos(angle);
      const y2 = c + r * Math.sin(angle);
      // a single slice cannot be drawn as an arc, it closes on itself
      const d = sweep >= Math.PI * 2 - 0.0001
        ? `M${c - r},${c} a${r},${r} 0 1,0 ${r * 2},0 a${r},${r} 0 1,0 ${-r * 2},0`
        : `M${x1},${y1} A${r},${r} 0 ${large},1 ${x2},${y2}`;
      return { ...row, d };
    });
  }, [JSON.stringify(rows), total, size]);

  if (total <= 0) return <div className="chart-empty" style={{ height: 120 }}>{empty}</div>;
  return (
    <div className="row" style={{ gap: 20, alignItems: 'center', flexWrap: 'wrap' }}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-hidden="true">
        {arcs.map((a) => (
          <path key={a.key} d={a.d} fill="none" stroke={seriesColor(a.idx, a.key)} strokeWidth={16} strokeLinecap="butt" />
        ))}
      </svg>
      <div className="grow" style={{ minWidth: 160 }}>
        {rows.map((r) => (
          <div key={r.key} className="row between" style={{ gap: 12, padding: '3px 0' }}>
            <span className="row" style={{ gap: 7, minWidth: 0 }}>
              <i
                style={{ width: 9, height: 9, borderRadius: 2, background: seriesColor(r.idx, r.key), flexShrink: 0 }}
              />
              <span className="truncate">{r.key}</span>
            </span>
            <span className="row" style={{ gap: 8 }}>
              <b>{format(r.value)}</b>
              <span className="faint small">{fmtPercent(r.value / total, 0)}</span>
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

const WEEK = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

/**
 * Hour of day across weekday.
 *
 * Folded over the whole period on purpose: the question is whether the traffic has a shape a human
 * audience keeps, and that only appears once the calendar is collapsed.
 */
export function HourGrid({ data, format = fmtInt }) {
  const values = (data && data.values) || [];
  const flat = values.flat().map(Number).filter((v) => v > 0).sort((a, b) => a - b);
  if (flat.length === 0) return <div className="chart-empty" style={{ height: 160 }}>Nothing in this period</div>;
  const q = (f) => flat[Math.min(flat.length - 1, Math.floor(flat.length * f))];
  const thresholds = [q(0.25), q(0.5), q(0.75)];
  const level = (v) => (!v ? 0 : 1 + thresholds.filter((t) => v > t).length);
  return (
    <div className="table-wrap">
      <div className="hgrid" style={{ minWidth: 560 }}>
        <span />
        {Array.from({ length: 24 }, (_, h) => (
          <span key={`h${h}`} className="h">
            {h % 3 === 0 ? h : ''}
          </span>
        ))}
        {(data.y || WEEK).map((day, row) => (
          <Fragment key={day}>
            <span className="d">{day}</span>
            {Array.from({ length: 24 }, (_, h) => {
              const v = Number((values[row] || [])[h]) || 0;
              return <span key={`${day}-${h}`} className={`c l${level(v)}`} title={`${day} ${h}:00 — ${format(v)}`} />;
            })}
          </Fragment>
        ))}
      </div>
    </div>
  );
}

/** A `Table` shaped result, rendered with its own columns. */
export function DataTable({ res, columns, empty = 'Nothing in this period', onPick, max = 50 }) {
  const items = (Array.isArray(res) ? res : itemsOf(res)).slice(0, max);
  if (items.length === 0) return <div className="chart-empty" style={{ height: 120 }}>{empty}</div>;
  return (
    <div className="table-wrap">
      <table className="table">
        <thead>
          <tr>
            {columns.map((c) => (
              <th key={c.key} style={c.align === 'right' ? { textAlign: 'right' } : undefined}>
                {c.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {items.map((row, idx) => (
            <tr
              key={row.key || idx}
              onClick={onPick ? () => onPick(row) : undefined}
              style={onPick ? { cursor: 'pointer' } : undefined}
            >
              {columns.map((c) => (
                <td key={c.key} style={c.align === 'right' ? { textAlign: 'right' } : undefined}>
                  {c.render ? c.render(row) : c.format ? c.format(row[c.key]) : row[c.key] === null || row[c.key] === undefined ? '—' : String(row[c.key])}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function ShareBar({ part, total, label }) {
  const ratio = total > 0 ? part / total : 0;
  return (
    <div>
      <div className="row between small" style={{ marginBottom: 4 }}>
        <span className="muted">{label}</span>
        <span>
          <b>{fmtNumber(part)}</b> <span className="faint">of {fmtNumber(total)}</span> · {fmtPercent(ratio, 0)}
        </span>
      </div>
      <div className="bar" style={{ height: 6, borderRadius: 3, background: 'var(--selected)', overflow: 'hidden' }}>
        <i style={{ display: 'block', height: '100%', width: `${Math.round(ratio * 100)}%`, background: 'var(--chart-1)' }} />
      </div>
    </div>
  );
}

/** The one empty state that is not an error: the exporter that stores the events is not set up. */
export function NoExporter() {
  return (
    <Empty title="No analytics exporter">
      <p className="muted">
        Otoroshi stores analytics events in PostgreSQL through a data exporter. Without one, the suite still emits its
        events and still enforces everything it is configured to — there is simply nothing to query here.
      </p>
      <p className="muted" style={{ marginTop: 10 }}>
        Everything that reads live state instead — coverage, bans, incidents, tuning and learning — works without it.
      </p>
      <a className="btn primary" style={{ marginTop: 14 }} href="/bo/dashboard/exporters" target="_blank" rel="noreferrer">
        <Icon name="external" />
        Configure a data exporter
      </a>
    </Empty>
  );
}
