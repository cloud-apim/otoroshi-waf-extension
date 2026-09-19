import { useState } from 'react';
import { EntityCreator } from './create';
import { EntityEditor } from './editor';
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

/**
 * `selectedId` marks the entity the workspace points at; `onSelect` is what makes that a choice
 * rather than a report.
 */
export function EntityList({
  state,
  plural,
  selectedId,
  onSelect,
  onCreate,
  onOpen,
  createLabel = 'Create one',
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
        {onCreate && (
          <button className="btn primary" style={{ marginTop: 14 }} onClick={onCreate}>
            <Icon name="plus" />
            {createLabel}
          </button>
        )}
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
            <tr key={e.id} onClick={onOpen ? () => onOpen(e) : undefined} style={onOpen ? { cursor: 'pointer' } : undefined}>
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
              <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }} onClick={(ev) => ev.stopPropagation()}>
                {onSelect && writable && selectedId !== e.id && (
                  <button className="btn sm" style={{ marginRight: 6 }} onClick={() => onSelect(e.id)}>
                    {selectLabel}
                  </button>
                )}
                {onOpen && (
                  <button className="btn sm" onClick={() => onOpen(e)}>
                    <Icon name="edit" />
                    {writable ? 'Edit' : 'View'}
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function SectionCard({ title, description, children, actions, onCreate, createLabel = 'New' }) {
  const create = onCreate && (
    <button className="btn sm primary" onClick={onCreate}>
      <Icon name="plus" />
      {createLabel}
    </button>
  );
  return (
    <Card className="flush" style={{ marginBottom: 18 }} title={title} description={description} actions={actions || create}>
      {children}
    </Card>
  );
}


/**
 * A kind of entity, whole: the list, the editor and the creator.
 *
 * Bundled because every page needs exactly the same three things wired the same way, and having each
 * page wire them itself is how they drift apart.
 */
export function EntitySection({
  plural,
  title,
  description,
  state,
  columns,
  selectedId,
  onSelect,
  selectLabel,
  emptyTitle,
  emptyBody,
  createLabel = 'New',
  writable = true,
  workspaceId,
  kind,
  onChanged,
  // optional controlled edition: when a parent passes `editing`/`onEditingChange`, opening the
  // editor is driven from there, so a button outside this section (the WAF page's "Edit rules")
  // opens the very same drawer rather than a second one
  editing: editingProp,
  onEditingChange,
}) {
  const [editingLocal, setEditingLocal] = useState(null);
  const editing = editingProp !== undefined ? editingProp : editingLocal;
  const setEditing = onEditingChange || setEditingLocal;
  const [creating, setCreating] = useState(false);

  const changed = (entity) => {
    state.reload();
    if (onChanged) onChanged(entity);
  };

  return (
    <>
      <SectionCard
        title={title}
        description={description}
        plural={plural}
        onCreate={writable ? () => setCreating(true) : undefined}
        createLabel={createLabel}
      >
        <EntityList
          state={state}
          plural={plural}
          columns={columns}
          selectedId={selectedId}
          onSelect={onSelect}
          selectLabel={selectLabel}
          onOpen={setEditing}
          onCreate={writable ? () => setCreating(true) : undefined}
          createLabel={createLabel}
          writable={writable}
          emptyTitle={emptyTitle}
          emptyBody={emptyBody}
        />
      </SectionCard>

      <EntityEditor
        plural={plural}
        entity={editing}
        open={!!editing}
        writable={writable}
        onClose={() => setEditing(null)}
        onSaved={changed}
        onDeleted={changed}
      />
      <EntityCreator
        plural={plural}
        open={creating}
        workspaceId={workspaceId}
        kind={kind}
        onClose={() => setCreating(false)}
        onCreated={changed}
      />
    </>
  );
}
