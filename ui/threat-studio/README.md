# Threat Studio

One console on top of the Otoroshi Threat Protection extension, organised by **who is protected**
rather than by which entity configures it. It is served by the extension at
`/extensions/cloud-apim/threat-studio` (backoffice session required) and has a "Back to Otoroshi"
button to return to the admin console.

- React 19, plain css (`src/styles/app.css`, light and dark themes), no ui library, no router library,
  hand drawn svg charts
- **it stores nothing of its own.** A workspace *is* a rule of the global preset table, which lives on
  the global configuration; the entities it points at are the ordinary entities of the extension,
  created live through the admin api (`/bo/api/proxy/apis/waf.extensions.cloud-apim.com/v1/...`)
- the analytics come from the otoroshi user analytics, through the queries the extension declares in
  `analytics/queries.scala` — every one of them takes a `route_ids` parameter, which is what makes a
  workspace (a *set* of routes) a thing that can be asked a question
- the one thing only the gateway can answer — which routes a rule claims — is computed in
  `src/main/scala/.../studio/studio.scala` and read from `GET …/studio/workspaces`

## Workspace mapping

| Studio concept | Otoroshi |
|---|---|
| the table | the `CloudApimSecuritySuiteGlobalPreset` slot of the global plugins |
| workspace | one rule of that table: `id`, `name`, `enabled`, `skip`, `targets`, `preset` |
| its scope | the rule's `targets`, resolved against `env.proxyState.allRoutes()` by the extension |
| its protection | the rule's `preset`, which is the route-level preset's configuration field for field |
| the entities it points at | `waf-configs`, `threat-policies`, `bot-policies`, … — shared, never owned |
| entities created from the studio | tagged `metadata.threat_studio_workspace = <rule id>` |
| theme | the `threat_studio_theme` preference of the backoffice user (`light`, `dark` or `system`) |

The order of the table is the configuration: it is read top to bottom and the first matching rule
wins. Everything that reorders goes through `moveWorkspace` and saves the table whole — a partial
update would let two clients reorder against each other.

## Entities

Created, edited and deleted from the studio. One form for both, so creation is reviewed in the shape
it will later be edited in, and nothing is written until it is confirmed.

- `src/lib/schemas.js` is the edited view of each entity: the fields that decide behaviour, in the
  order they are reasoned about. Keys are the entity's json keys; a dotted key reaches into a nested
  object (the CRS dials).
- `src/components/form.jsx` renders them. A `strings` field is a list of textareas rather than one —
  a SecLang rule may span lines, and the backoffice stores them as an array for that reason.
- `src/lib/create.js` only ever *seeds*: always from the entity's `_template`, so a field added to an
  entity is not silently dropped by a studio that built its own object. The two places it overrides
  the template are commented there, and both go the same way — a thing that can refuse traffic starts
  by observing.
- `src/components/entities.jsx` bundles list + editor + creator into `EntitySection`, because every
  page needs the same three wired the same way.

Deliberately not every field: the backoffice generates the complete form from the entity schema, and
**Full form** in the editor footer goes straight to it. The tuning assistant and learning mode are
not copied either — the studio surfaces their state and links to them.

## Development

```sh
npm install
npm run dev
```

Then open http://studio-dev.oto.tools:5174/extensions/cloud-apim/threat-studio while logged in the
local otoroshi (http://otoroshi.oto.tools:9999, override with `VITE_OTOROSHI_URL`). The otoroshi
session cookie is set on `.oto.tools`, so the api calls proxied by vite are authenticated. The
extension jar only needs to be rebuilt when the scala side changes.

## Build

```sh
npm run build
```

The bundle is written in `src/main/resources/cloudapim/extensions/waf/studio` and committed, so
`sbt assembly` never needs node. `../../rebuild.sh` does the build, the assembly and the dev reload
in one go. Run the build before committing front changes.
