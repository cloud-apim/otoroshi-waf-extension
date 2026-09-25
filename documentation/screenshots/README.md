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
| `TS_ONLY` | a regex on the capture names, to redo only some: `TS_ONLY='activity\|events' npm run shoot` |

Every Activity tab and the Events page are captured on the **past hour**, picked in the period
selector like a user would, with auto reload switched off: that is where freshly generated demo
traffic is, where a week would average it into the quiet before it. The Geography tab is captured
flat, as a globe, and with its top country selected; the Events page with a decision and a WAF trail
opened.

The captures are only as interesting as the data behind them: run some traffic through a route the
workspace governs first, or the analytics pages will be empty by design.
[`../../scripts/demo-traffic.js`](../../scripts/demo-traffic.js) does exactly that — `bun
scripts/demo-traffic.js --warm` from the repository root seeds a minute of mixed traffic and then
keeps a trickle going.
