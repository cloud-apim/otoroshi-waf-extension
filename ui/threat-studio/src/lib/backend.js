import { globalRouteOf, routeOf, STUDIO_ADMIN_PATH } from '../core/ops';
import { api, request } from './api';

// What the pages call: the operations of a workspace and of the gateway (core/ops.js), the workspaces
// themselves, and the preferences of the signed-in user. The edition decides how (main.jsx): the OSS studio
// installs the local backend below, another edition one that asks its own server.
export const backend = {};

export function installBackend(impl) {
  Object.assign(backend, impl);
}

// the admin api, through the backoffice session: what it does is checked against the rights of the signed-in
// user
const ADMIN_API = '/bo/api/proxy';

export function localBackend() {
  return {
    run: (name, wsId, input) => {
      const route = routeOf(name, wsId, input);
      return request(route.method, `${ADMIN_API}${STUDIO_ADMIN_PATH}${route.path}`, route.body);
    },
    runGlobal: (name, input) => {
      const route = globalRouteOf(name, input);
      return request(route.method, `${ADMIN_API}${route.path}`, route.body);
    },
    workspaces: {
      // the table, every rule with the routes the signed-in user may read and what they may do on it
      list: () => api.get(`${ADMIN_API}${STUDIO_ADMIN_PATH}/workspaces`),
    },
    // the preferences of the backoffice user (the theme), as json: `light` alone is no json
    prefs: { set: (key, value) => api.post(`/bo/api/me/preferences/${key}`, JSON.stringify(value)) },
  };
}
