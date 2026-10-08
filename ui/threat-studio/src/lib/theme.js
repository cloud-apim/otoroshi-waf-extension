import { useEffect, useState } from 'react';
import { backend } from './backend';
import { bootstrap } from './bootstrap';

/**
 * The theme preference: `light`, `dark` or `system`.
 *
 * Kept in two places on purpose. `localStorage` is what the page reads before React mounts, so the
 * first paint is already the right one and a reload never flashes; the otoroshi preference of the
 * backoffice user is what makes the choice follow them to another browser. The local copy wins on
 * read, because it is the one that cannot fail.
 *
 * The preference is saved through the backend (lib/backend.js), which sends it as the json the
 * preference endpoint parses: posting the bare word silently 500s and the choice is lost on reload.
 */

export const STORAGE_KEY = 'threat_studio_theme';
export const THEMES = ['light', 'dark', 'system'];

function systemTheme() {
  return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

function stored() {
  try {
    const value = window.localStorage.getItem(STORAGE_KEY);
    return THEMES.includes(value) ? value : null;
  } catch (e) {
    return null;
  }
}

function remember(value) {
  try {
    window.localStorage.setItem(STORAGE_KEY, value);
  } catch (e) {
    // private browsing, or site data blocked: the otoroshi preference below still carries it
  }
}

function initialPreference() {
  return stored() || (THEMES.includes(bootstrap.theme) ? bootstrap.theme : 'system');
}

export function useTheme() {
  const [preference, setPreference] = useState(initialPreference);
  const [system, setSystem] = useState(systemTheme);

  useEffect(() => {
    if (!window.matchMedia) return;
    const media = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = () => setSystem(systemTheme());
    media.addEventListener('change', onChange);
    return () => media.removeEventListener('change', onChange);
  }, []);

  // the server knows a preference this browser has never seen: adopt it once, rather than letting
  // the two drift apart
  useEffect(() => {
    if (!stored() && THEMES.includes(bootstrap.theme)) remember(bootstrap.theme);
  }, []);

  const theme = preference === 'system' ? system : preference;

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
  }, [theme]);

  const choose = (value) => {
    setPreference(value);
    bootstrap.theme = value;
    remember(value);
    backend.prefs.set(STORAGE_KEY, value).catch(() => {});
  };

  return { theme, preference, choose };
}
