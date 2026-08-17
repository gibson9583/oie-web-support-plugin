# OIE Web Support

Engine-side REST endpoints that back the [OIE web administrator](https://github.com/gibson9583/oie-web-client) —
shipped as a standard extension so the engine itself needs **no changes**. Each
release also ships a companion **OIE Web Client** extension that deploys the
browser administrator into the engine, for administrators who want it hosted
there rather than run from source or Docker.

## Two extensions, installed independently

Each release ships two separate extensions — install either or both:

| Extension (id) | Artifact | What it does |
|---|---|---|
| **Web Support** (`websupport`) | `websupport-<version>.zip` | The engine-side REST APIs the web administrator uses — message-tree serialization, JavaScript validation, and plugin-UI serving. No UI. |
| **OIE Web Client** (`oie-webadmin`) | `websupport-web-client-<version>.zip` | Deploys the browser administrator into this OIE server's embedded Jetty (served at `/oie-web-client/`). Carries `oie-web-client.war`. |

They are distinct extensions with distinct `plugin.xml` paths, so the Community
Store tracks and updates them independently. Install through the Swing
Administrator (or the web client's Extensions page when one is already
available), then restart OIE.

- **Just the APIs** (you run the client from source, Docker, or a separately
  managed WAR): install **Web Support**.
- **Engine-hosted client**: install **OIE Web Client**. Its service plugin copies
  `oie-web-client.war` to `<OIE_HOME>/webapps/` before Jetty scans web
  applications. Open `https://<host>:8443/oie-web-client/` after the restart.
- **Both** (a fresh engine that should host the client with full functionality):
  install both, then restart once.

Uninstalling **OIE Web Client** removes its deployed WAR on the next engine
restart (the restart that finalizes the uninstall). If the engine is force-killed
while an uninstall is pending, delete `<OIE_HOME>/webapps/oie-web-client.war` by
hand.

## What it provides

All endpoints live under `/api/extensions/websupport` and require only an
authenticated session:

| Endpoint | Purpose |
|---|---|
| `POST /datatypes/_serialize?dataType=&props=` | Serializes a message through the engine's **own data type serializers** — output byte-identical to the runtime `msg`/`tmp` — plus the data type's vocabulary descriptions for message-tree annotation. |
| `POST /javascript/_validate` | Compiles a script with the engine's **Rhino**, returning `{ error: string\|null }` — validation that matches runtime compilation exactly. |
| `GET /webplugins` | Lists installed, enabled extensions that ship a web UI half (`webadmin/plugin.json`). |
| `GET /webplugins/{extension}/{path}` | Serves a static file from an extension's `webadmin/` folder, so a plugin's browser UI follows the engine it is installed on. |

The web administrator probes for these endpoints at session start — engine-native
first (an engine built with them), then this plugin — and degrades gracefully
(no message trees, no server-side validation, no engine-served plugin UIs) when
neither is present.

## Install — do this first

This plugin is the one piece that cannot install itself through the Community
Store, because the store's own UI is served *by* it. The bootstrap order:

1. Download the extension ZIP(s) you want from [Releases](../../releases) — see
   the two extensions above.
2. In the web administrator: **Extensions → Install** (extension install uses only
   the engine's core REST API, so it works without this plugin). The classic Swing
   Administrator works too.
3. Restart the engine.

After that, plugin UIs (including the Community Store) light up, and this plugin
updates itself through the store like any other extension.

## Build

Requires an OIE/Mirth Connect installation (or built engine tree) for the engine jars:

```bash
OIE_HOME=/path/to/oie mvn package     # -> target/websupport-<version>.zip
```

To also build the **OIE Web Client** extension, build the web-client WAR and pass it to Maven:

```bash
cd ../oie-web-client
npm ci
npm run build:war

cd ../oie-web-support-plugin
OIE_HOME=/path/to/oie mvn package \
  -Dwebclient.war=../oie-web-client/web-administrator/dist/oie-web-client.war

# target/websupport-<version>.zip
# target/websupport-web-client-<version>.zip
```

The release workflow performs that cross-repository build automatically. It
bundles the web-client commit pinned in [`web-client.ref`](web-client.ref) so
releases are reproducible; override it per-run with the `WEB_CLIENT_REF`
repository variable (a web-client tag or full commit SHA).

## Notes

- The vocabulary descriptions (HL7 v2, X12, NCPDP, DICOM) are resolved from the
  engine's own datatype classes by name at runtime — no compile-time coupling, and
  a data type that isn't installed simply contributes no descriptions.
- Static assets are served with the servlet container's MIME table
  (`ServletContext.getMimeType`), with `text/javascript; charset=utf-8` pinned for
  `.js`/`.mjs` so ES modules always execute.
- File serving is confined to each extension's `webadmin/` folder (canonicalized
  path containment; traversal and symlink escapes are rejected).
- The OIE Web Client extension installs its WAR with a staged file and atomic
  replacement where supported; a read-only `webapps/` directory prevents the WAR
  deployment but never affects the Web Support APIs (a separate extension).

## License

MPL-2.0
