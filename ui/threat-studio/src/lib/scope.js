import { createContext, useContext } from 'react';
import { backend } from './backend';
import { Resources } from './entities';
import { hasPermission } from './platform';

/**
 * Where the entities a page works on come from.
 *
 * In a workspace, the kinds a preset can name come from the workspace: its own entities and the ones that belong
 * to no workspace, each with what the workspace knows of it (`_studio`: whether it is its own, how much of its use
 * is the workspace's, whether a rule feed manages it). Everything else — feeds, reputation sources, alerts — is
 * the gateway's, and comes from it.
 */

// what a preset can name, directly or through another entity
export const WORKSPACE_KINDS = [
  'waf-configs',
  'waf-rulesets',
  'threat-policies',
  'challenge-providers',
  'bot-policies',
  'malware-scanners',
  'api-contracts',
];

const withStudio = (item) =>
  item && item.entity ? { ...item.entity, _studio: { ownership: item.ownership, usage: item.usage, managed: !!item.managed } } : item;

// what the studio knows of an entity is not part of it
const bare = (entity) => {
  const { _studio, ...rest } = entity || {};
  return rest;
};

export function workspaceResource(wsId, plural) {
  const run = (name, input = {}) => backend.run(name, wsId, { kind: plural, ...input });
  return {
    plural,
    idField: 'id',
    workspace: wsId,
    list: () => run('entities.list').then((items) => (Array.isArray(items) ? items.map(withStudio) : [])),
    get: (id) => run('entities.get', { eid: id }).then(withStudio),
    template: () => run('entities.template'),
    create: (entity) => run('entities.create', { body: bare(entity) }).then((r) => r && r.entity),
    update: (entity) => run('entities.update', { eid: entity.id, body: bare(entity) }).then((r) => r && r.entity),
    delete: (id) => run('entities.delete', { eid: id }),
    // a copy of a shared entity made the workspace's own, in place of the original in the preset when `use`
    fork: (id, opts = {}) => run('entities.fork', { eid: id, body: opts }).then((r) => r && r.entity),
    usage: (id) => run('entities.usage', { eid: id }),
  };
}

const BY_PLURAL = Object.fromEntries(Object.values(Resources).filter((r) => r.plural).map((r) => [r.plural, r]));

/** Every entity of a kind, the gateway's: what its administrators read and change. */
export function globalResource(plural) {
  const resource = BY_PLURAL[plural];
  if (!resource) throw new Error(`the studio does not know the entities '${plural}'`);
  return {
    ...resource,
    create: (entity) => resource.create(bare(entity)),
    update: (entity) => resource.update(bare(entity)),
  };
}

export const workspaceScope = (wsId) => (plural) =>
  WORKSPACE_KINDS.includes(plural) ? workspaceResource(wsId, plural) : globalResource(plural);

const EntityScope = createContext(globalResource);

export const EntityScopeProvider = EntityScope.Provider;

/** `(plural) => resource`: the entities of a kind, as the page shown reaches them. */
export const useEntities = () => useContext(EntityScope);

/** Whether an entity shown in a workspace is the workspace's own to change. */
export const isOwn = (entity) => !entity || !entity._studio || entity._studio.ownership === 'workspace';

/**
 * Whether the signed-in user changes an entity in place: its own, or any for an administrator of the gateway, who
 * changes a shared entity for everything that uses it (the editor says so).
 */
export const changesInPlace = (entity) => isOwn(entity) || hasPermission('admin');

/** Where an entity is written: the workspace for its own, the gateway for a shared one. */
export const writerOf = (resource, entity) => (isOwn(entity) || !resource.workspace ? resource : globalResource(resource.plural));
