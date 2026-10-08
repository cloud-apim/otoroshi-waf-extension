// What differs between the editions of Threat Studio, set at startup (main.jsx): the links of the console, the
// pages an edition adds, and what the signed-in user can do (`can`: what the pages offer, the api still decides).
export const platform = {
  edition: 'oss',
  features: {},
  // what the user can do on the gateway as a whole (`admin:read`, `admin`)
  permissions: [],
  // what the user can do on a workspace: the permissions the api gives with it (see PERMISSIONS in core/ops.js)
  can: (permission, workspace) => {
    const granted = (workspace && workspace.permissions) || [];
    return (Array.isArray(permission) ? permission : [permission]).some((p) => granted.includes(p));
  },
  // pages added to the workspace menu: { id, label, icon, component, permission, after }
  pages: [],
  // pages outside of the workspaces: { path, component, permission } (a global permission, see `permissions`)
  routes: [],
  // elements added to the top bar, before the theme menu
  topbar: [],
  // shown next to the logo
  experimental: false,
  // the Otoroshi admin console (routes, data exporters, danger zone, entity forms), none when the studio is
  // served on its own
  links: { admin: null, logout: null, docs: 'https://cloud-apim.github.io/otoroshi-waf-extension/' },
};

export function installPlatform(values) {
  Object.assign(platform, values, { links: { ...platform.links, ...((values && values.links) || {}) } });
}

// a page of the Otoroshi admin console, when there is one
export const adminLink = (path) => (platform.links.admin ? `${platform.links.admin}${path}` : null);

export const hasPermission = (permission) => platform.permissions.includes(permission);

// The workspace the pages shown are about, for what is looked up deep in a page without being handed one (the
// rule catalog, the location of an address): set by the workspace shell, cleared when it goes.
let current = null;

export const setCurrentWorkspace = (id) => {
  current = id || null;
};

export const currentWorkspace = () => current;
