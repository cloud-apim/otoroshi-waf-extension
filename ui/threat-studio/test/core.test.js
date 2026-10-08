// The operations registry and the secrets of the entities (src/core), run with `npm test` (node --test).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { GLOBAL_PERMISSIONS, globalOps, globalRouteOf, MODULES_ADMIN_PATH, ops, PERMISSIONS, routeOf, STUDIO_ADMIN_PATH } from '../src/core/ops.js';
import { maskSecrets, SECRET_FIELDS_BY_KIND, SECRET_SENTINEL } from '../src/core/secrets.js';

const SCALA = '../../../src/main/scala/com/cloud/apim/otoroshi/extensions/waf';
const read = (path) => readFileSync(new URL(`${SCALA}/${path}`, import.meta.url), 'utf8');

// the routes of the studio admin api, read from its source: `route("GET", "/workspaces/:id/routes")`
const STUDIO = [...read('studio/api.scala').matchAll(/route\("(GET|POST|PUT|PATCH|DELETE)", "([^"]+)"/g)].map(([, method, path]) => ({
  method,
  path: `${STUDIO_ADMIN_PATH}${path}`,
}));

// the module routes, served on the admin api under `/api` + their backoffice path
const MODULES = ['security/module.scala', 'tuning/module.scala', 'learning/module.scala', 'feeds/module.scala', 'reputation/module.scala']
  .flatMap((file) => {
    const source = read(file);
    const base = (source.match(/val basePath\s*=\s*s?"([^"]+)"/) || [])[1];
    return [...source.matchAll(/method\s*=\s*"(\w+)",\s*path\s*=\s*s"\$basePath\/([^"]+)"/g)].map(([, method, action]) => ({
      method,
      path: `/api${base}/${action}`,
    }));
  })
  .concat(
    [...read('extension.scala').matchAll(/method\s*=\s*"(\w+)",\s*path\s*=\s*"(\/extensions\/cloud-apim\/extensions\/waf\/utils\/[^"]+)"/g)].map(
      ([, method, path]) => ({ method, path: `/api${path}` })
    )
  );

// a route matches a path when every segment does, a `:param` on either side matching anything
const matches = (route, method, path) => {
  if (route.method !== method) return false;
  const a = route.path.split('/');
  const b = path.split('/');
  return a.length === b.length && a.every((s, i) => s === b[i] || s.startsWith(':') || b[i].startsWith(':'));
};

test('every operation declares a known access', () => {
  Object.entries(ops).forEach(([name, op]) => assert.ok(PERMISSIONS.includes(op.access), name));
  Object.entries(globalOps).forEach(([name, op]) => assert.ok(GLOBAL_PERMISSIONS.includes(op.access), name));
});

test('every operation of a workspace is served by a route of the studio admin api', () => {
  assert.ok(STUDIO.length > 40, 'the routes of api.scala are read');
  Object.entries(ops).forEach(([name, op]) => {
    const path = `${STUDIO_ADMIN_PATH}/workspaces/:ws${op.path}`;
    assert.ok(STUDIO.some((r) => matches(r, op.method, path)), `${name}: ${op.method} ${path}`);
  });
});

test('every operation of the gateway is served by the studio api, a module route, or the admin api of otoroshi', () => {
  assert.ok(MODULES.length > 40, 'the module routes are read');
  Object.entries(globalOps).forEach(([name, op]) => {
    if (op.path.startsWith(STUDIO_ADMIN_PATH)) assert.ok(STUDIO.some((r) => matches(r, op.method, op.path)), `${name}: ${op.method} ${op.path}`);
    else if (op.path.startsWith(MODULES_ADMIN_PATH)) assert.ok(MODULES.some((r) => matches(r, op.method, op.path)), `${name}: ${op.method} ${op.path}`);
    else assert.ok(op.path.startsWith('/apis/') || op.path === '/api/analytics/_query', `${name}: ${op.path}`);
  });
});

test('the route of an operation takes its params, its query and its body from the input', () => {
  const route = routeOf('entities.update', 'ws 1', { kind: 'waf-configs', eid: 'a/b', query: { x: true, y: false, z: null }, body: { name: 'x' } });
  assert.deepEqual(route, { method: 'PUT', path: '/workspaces/ws%201/entities/waf-configs/a%2Fb?x=true', body: { name: 'x' } });
  assert.throws(() => routeOf('entities.update', 'ws', { kind: 'waf-configs' }), /needs 'eid'/);
  assert.throws(() => routeOf('nope', 'ws'), /unknown operation/);
  assert.throws(() => routeOf('constructor', 'ws'), /unknown operation/);
  assert.deepEqual(globalRouteOf('workspaces.setScope', { ws: 'ws_a', body: { skip: true } }), {
    method: 'PUT',
    path: `${STUDIO_ADMIN_PATH}/workspaces/ws_a/scope`,
    body: { skip: true },
  });
  assert.equal(globalRouteOf('entities.list', { kind: 'threat-feeds' }).path, '/apis/waf.extensions.cloud-apim.com/v1/threat-feeds');
});

test('an id never takes the route of an operation above its own', () => {
  assert.throws(() => routeOf('entities.delete', 'ws', { kind: 'waf-configs', eid: '..' }), /invalid 'eid'/);
  assert.throws(() => routeOf('entities.delete', 'ws', { kind: 'waf-configs', eid: '.' }), /invalid 'eid'/);
  assert.throws(() => routeOf('workspace.get', '..'), /invalid 'workspace'/);
  assert.throws(() => globalRouteOf('workspaces.delete', { ws: '..' }), /invalid 'ws'/);
});

test('the secrets of an entity are masked, vault references and empty values are not', () => {
  const items = [
    { entity: { id: 'c1', secret: 's3cr3t', secret_key: '${vault://env/K}', site_key: 'public' }, ownership: 'workspace' },
    { entity: { id: 'c2', secret: '' }, ownership: 'shared' },
  ];
  assert.deepEqual(maskSecrets('entities.list', items, { kind: 'challenge-providers' }), [
    { entity: { id: 'c1', secret: SECRET_SENTINEL, secret_key: '${vault://env/K}', site_key: 'public' }, ownership: 'workspace' },
    { entity: { id: 'c2', secret: '' }, ownership: 'shared' },
  ]);
});

test('a url keeps where it goes and loses what its query carries', () => {
  const feed = { id: 'f', url: 'https://feeds.example/list.txt?key=abc&format=csv', headers: { Key: 'abc' } };
  const masked = maskSecrets('entities.get', feed, { kind: 'threat-feeds' }, { global: true });
  assert.equal(masked.url, `https://feeds.example/list.txt?key=${SECRET_SENTINEL}&format=${SECRET_SENTINEL}`);
  assert.equal(masked.headers.Key, SECRET_SENTINEL);
  const alert = { id: 'a', channel: { kind: 'slack', url: 'https://hooks.slack.com/services/T/B/x' } };
  assert.equal(maskSecrets('entities.get', alert, { kind: 'alert-rules' }, { global: true }).channel.url, SECRET_SENTINEL);
  const honeypot = { id: 'h', canaries: [{ value: 'AKIAFAKE', where: 'header' }] };
  assert.equal(maskSecrets('entities.get', honeypot, { kind: 'honeypot-policies' }, { global: true }).canaries[0].value, SECRET_SENTINEL);
});

test('a field named like a secret is masked even when nobody declared it', () => {
  const entity = { entity: { id: 'x', some_token: 'abc', nested: { password: 'p' } } };
  const masked = maskSecrets('entities.get', entity, { kind: 'waf-configs' });
  assert.equal(masked.entity.some_token, SECRET_SENTINEL);
  assert.equal(masked.entity.nested.password, SECRET_SENTINEL);
});

test('a reveal is left as it is', () => {
  const crowdsec = { id: 'b', api_key: 'k' };
  assert.equal(maskSecrets('entities.reveal', crowdsec, { kind: 'crowdsec-bouncers' }, { global: true }).api_key, 'k');
  assert.equal(maskSecrets('entities.get', crowdsec, { kind: 'crowdsec-bouncers' }, { global: true }).api_key, SECRET_SENTINEL);
});

test('every kind with secrets is a kind of the suite', () => {
  const kinds = ['challenge-providers', 'alert-rules', 'crowdsec-bouncers', 'geo-databases', 'threat-feeds', 'rule-feeds', 'honeypot-policies'];
  assert.deepEqual(Object.keys(SECRET_FIELDS_BY_KIND).sort(), kinds.sort());
});
