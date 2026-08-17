# OIE Web Support

The complete [OIE web administrator](https://github.com/gibson9583/oie-web-client),
shipped as one standard extension so the engine itself needs **no changes**. It
installs both the browser client and the engine-side APIs used for message trees,
JavaScript validation, and extension-provided web UIs.

## One extension

Each release has one artifact:

| Extension | Artifact | What it does |
|---|---|---|
| **Web Support** (`websupport`) | `websupport-<version>.zip` | Installs the supporting APIs and deploys `oie-webadmin.war` into this OIE server's embedded Jetty, served at `/oie-webadmin/`. |

Install it through the Swing Administrator (or the web client's Extensions page
when one is already available), then restart OIE. The service plugin copies
`oie-webadmin.war` to `<OIE_HOME>/webapps/` before Jetty scans web applications.
Open `https://<host>:8443/oie-webadmin/` after the restart.

The same client can still be run with Node.js or Docker. Installing Web Support
also makes the embedded copy available; it does not prevent those deployment
models.

Uninstalling **Web Support** removes its deployed WAR on the next engine restart
(the restart that finalizes the uninstall). If the engine is force-killed while
an uninstall is pending, delete `<OIE_HOME>/webapps/oie-webadmin.war` by hand.

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

1. Download `websupport-<version>.zip` from [Releases](../../releases).
2. In the web administrator: **Extensions → Install** (extension install uses only
   the engine's core REST API, so it works without this plugin). The classic Swing
   Administrator works too.
3. Restart the engine.

After that, plugin UIs (including the Community Store) light up, and this plugin
updates itself through the store like any other extension.

## Build

Requires an OIE/Mirth Connect installation (or built engine tree) for the engine
jars and a built web-client WAR. The WAR is mandatory:

```bash
cd ../oie-web-client
npm ci
npm run build:war

cd ../oie-web-support-plugin
OIE_HOME=/path/to/oie mvn package \
  -Dwebclient.war=../oie-web-client/web-administrator/dist/oie-webadmin.war

# target/websupport-<version>.zip
```

The web-client release workflow publishes `oie-webadmin.war`. The Web Support
release workflow downloads the release selected by `webclient.version` in
`pom.xml`, validates it, and records the exact client tag and SHA-256 in its
release notes. Keep that property equal to the Web Support version while the
release lines match, or change it when the projects version independently. There
is no source checkout, arbitrary commit ref, or repository variable to maintain.

## Notes

- The vocabulary descriptions (HL7 v2, X12, NCPDP, DICOM) are resolved from the
  engine's own datatype classes by name at runtime — no compile-time coupling, and
  a data type that isn't installed simply contributes no descriptions.
- Static assets are served with the servlet container's MIME table
  (`ServletContext.getMimeType`), with `text/javascript; charset=utf-8` pinned for
  `.js`/`.mjs` so ES modules always execute.
- File serving is confined to each extension's `webadmin/` folder (canonicalized
  path containment; traversal and symlink escapes are rejected).
- Web Support installs its WAR with a staged file and atomic replacement where
  supported. A read-only `webapps/` directory prevents WAR deployment; the APIs
  still load so the engine can start and the copy failure is logged.

## License

MPL-2.0
