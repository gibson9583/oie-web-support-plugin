# OIE Web Support

Engine-side REST endpoints that back the [OIE web administrator](https://github.com/gibson9583/oie-web-client) —
shipped as a standard extension so the engine itself needs **no changes**. Endpoints
only; this plugin has no UI of its own.

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

Requires an OIE/Mirth Connect installation (or built engine tree) for the engine jars:

```bash
OIE_HOME=/path/to/oie mvn package     # -> target/websupport-<version>.zip
```

## Notes

- The vocabulary descriptions (HL7 v2, X12, NCPDP, DICOM) are resolved from the
  engine's own datatype classes by name at runtime — no compile-time coupling, and
  a data type that isn't installed simply contributes no descriptions.
- Static assets are served with the servlet container's MIME table
  (`ServletContext.getMimeType`), with `text/javascript; charset=utf-8` pinned for
  `.js`/`.mjs` so ES modules always execute.
- File serving is confined to each extension's `webadmin/` folder (canonicalized
  path containment; traversal and symlink escapes are rejected).

## License

MPL-2.0
