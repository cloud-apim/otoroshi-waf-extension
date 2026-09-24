# Threat Studio screenshots

Regenerates the `studio-*.png` captures used by [`../docs/studio.mdx`](../docs/studio.mdx), by
driving a local Otoroshi with Playwright.

```sh
npm run setup     # once — installs Playwright and its Chromium build (~130 MB)
npm run shoot     # logs in, walks the studio, writes ../static/img/screenshots/studio-*.png
```

It signs in at `admin@otoroshi.io` / `password` against `http://otoroshi.oto.tools:9999`, forces the
**dark** theme, auto-detects the first workspace of
the global preset table, and captures the pages the docs reference.

Overrides, all via environment variables:

| | |
|---|---|
| `OTO_URL` | the gateway (default `http://otoroshi.oto.tools:9999`) |
| `OTO_USER` · `OTO_PASSWORD` | the backoffice credentials |
| `TS_THEME` | `dark` (default) or `light` |
| `TS_WS` | a workspace id, to skip auto-detection |

The captures are only as interesting as the data behind them: run some traffic through a route the
workspace governs first, or the analytics pages will be empty by design.
[`../../scripts/demo-traffic.js`](../../scripts/demo-traffic.js) does exactly that — `bun
scripts/demo-traffic.js --warm` from the repository root seeds a minute of mixed traffic and then
keeps a trickle going.
