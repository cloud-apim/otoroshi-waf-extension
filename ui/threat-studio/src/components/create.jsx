import { useEffect, useState } from 'react';
import { EntityEditor } from './editor';
import { Badge, ErrorAlert, Field, Loading, Modal, Select, useToast } from './ui';
import { listChallengePresets, listFeedCatalog, seedChallengeFromPreset, seedFeedFromCatalog, seedFor } from '../lib/create';

/**
 * Creating an entity, in the form it will later be edited in.
 *
 * Two of them are picked from something the extension already knows how to build — a feed from the
 * catalog, a challenge provider from a preset — so those get one question first and then the same
 * editor, seeded. Everything else goes straight to the editor on a template.
 *
 * Nothing is written until Create: a half-filled entity is never left behind.
 */

function CatalogPicker({ onClose, onPicked }) {
  const [catalog, setCatalog] = useState(null);
  const [category, setCategory] = useState('');
  const [choice, setChoice] = useState('');

  useEffect(() => {
    listFeedCatalog()
      .then(setCatalog)
      .catch(() => setCatalog({ entries: [], categories: [] }));
  }, []);

  const all = (catalog && catalog.entries) || [];
  const entries = all.filter((e) => !category || e.category === category);
  const entry = all.find((e) => e.id === choice);

  return (
    <Modal
      open
      title="Add a threat feed"
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn" onClick={() => onPicked(null)}>
            Start from blank
          </button>
          <button className="btn primary" disabled={!choice} onClick={() => onPicked(choice)}>
            Continue
          </button>
        </>
      }
    >
      <p className="muted" style={{ marginBottom: 16 }}>
        The catalog carries curated sources with their parser, refresh interval, weight and signal tag already set. You
        can change any of it on the next screen.
      </p>
      {catalog === null ? (
        <Loading />
      ) : (
        <>
          <Field label="Category">
            <Select
              value={category}
              onChange={(v) => {
                setCategory(v);
                setChoice('');
              }}
              options={[{ value: '', label: 'Every category' }].concat(
                (catalog.categories || []).map((c) => ({ value: c, label: c }))
              )}
            />
          </Field>
          <Field label="Source">
            <Select value={choice} onChange={setChoice} placeholder="Pick a source" options={entries.map((e) => ({ value: e.id, label: e.name }))} />
          </Field>
        </>
      )}
      {entry && (
        <>
          <p className="muted small">{entry.description}</p>
          <div className="row" style={{ gap: 8, marginTop: 10, flexWrap: 'wrap' }}>
            <Badge>{entry.category}</Badge>
            <Badge title="What a match from this source contributes to the score">weight {entry.weight}</Badge>
            <Badge>{entry.licence}</Badge>
          </div>
          {entry.notes && <p className="faint small" style={{ marginTop: 10 }}>{entry.notes}</p>}
          {(entry.requires_auth || entry.manual_url) && (
            <div className="alert" style={{ marginTop: 12 }}>
              <b>It will arrive disabled.</b>{' '}
              {entry.requires_auth
                ? entry.auth_hint || 'This source needs credentials before it can fetch anything.'
                : 'This source has no stable url: set yours before enabling it.'}
            </div>
          )}
        </>
      )}
    </Modal>
  );
}

function PresetPicker({ onClose, onPicked }) {
  const [presets, setPresets] = useState(null);
  const [choice, setChoice] = useState('pow');

  useEffect(() => {
    listChallengePresets()
      .then((r) => setPresets((r && r.entries) || []))
      .catch(() => setPresets([]));
  }, []);

  const preset = (presets || []).find((p) => p.id === choice);

  return (
    <Modal
      open
      title="New challenge provider"
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn primary" onClick={() => onPicked(choice === 'pow' ? null : choice)}>
            Continue
          </button>
        </>
      }
    >
      <p className="muted" style={{ marginBottom: 16 }}>
        What a challenge tier serves when the threat response reaches it.
      </p>
      {presets === null ? (
        <Loading />
      ) : (
        <Field label="Kind">
          <Select
            value={choice}
            onChange={setChoice}
            options={[{ value: 'pow', label: 'Proof of work (built in)' }].concat(
              presets.map((p) => ({ value: p.id, label: `${p.name} — ${p.origin}` }))
            )}
          />
        </Field>
      )}
      {choice === 'pow' && (
        <p className="muted small">
          A puzzle the browser solves, with its difficulty scaled by the threat score. No vendor, no key, nothing to
          sign up for — and the only kind that works with no third party involved.
        </p>
      )}
      {preset && (
        <>
          <p className="muted small">{preset.description}</p>
          {preset.notes && <div className="alert" style={{ marginTop: 12 }}>{preset.notes}</div>}
          <div className="alert" style={{ marginTop: 12 }}>
            <b>It will arrive disabled.</b> A vendor provider needs its site key and secret before it can verify
            anything; enabled without them it would serve a challenge nobody can pass.
          </div>
        </>
      )}
    </Modal>
  );
}

const PICKERS = { 'threat-feeds': CatalogPicker, 'challenge-providers': PresetPicker };

export function EntityCreator({ plural, open, onClose, onCreated, workspaceId, kind }) {
  const toast = useToast();
  const [seed, setSeed] = useState(null);
  const [error, setError] = useState(null);
  const Picker = PICKERS[plural];
  const [picked, setPicked] = useState(false);

  useEffect(() => {
    if (!open) {
      setSeed(null);
      setPicked(false);
      setError(null);
      return;
    }
    if (Picker) return; // the picker decides what to seed from
    seedFor(plural, { workspaceId, kind }).then(setSeed).catch(setError);
  }, [open, plural]);

  if (!open) return null;
  if (error) return <Modal open title="Could not start" onClose={onClose}><ErrorAlert error={error} /></Modal>;

  if (Picker && !picked) {
    return (
      <Picker
        onClose={onClose}
        onPicked={(choice) => {
          setPicked(true);
          const seeding = !choice
            ? seedFor(plural, { workspaceId, kind })
            : plural === 'threat-feeds'
              ? seedFeedFromCatalog(choice)
              : seedChallengeFromPreset(choice);
          seeding.then(setSeed).catch((e) => {
            toast.error(e);
            onClose();
          });
        }}
      />
    );
  }

  if (!seed) return <Modal open title="Preparing…" onClose={onClose}><Loading /></Modal>;

  return (
    <EntityEditor
      plural={plural}
      entity={seed}
      open
      mode="create"
      onClose={onClose}
      onSaved={(entity) => onCreated && onCreated(entity)}
    />
  );
}
