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
| `POST /elements/_responseVariables` | Calls installed rules' and steps' `getResponseVariables()` against the supplied unsaved settings, returning `{ responseVariables: string[] }`. |
| `GET /webplugins` | Lists installed, enabled extensions that ship a web UI half (`webadmin/plugin.json`). |
| `GET /webplugins/{extension}/{path}` | Serves a static file from an extension's `webadmin/` folder, so a plugin's browser UI follows the engine it is installed on. |

The web administrator probes for these endpoints at session start — engine-native
first (an engine built with them), then this plugin — and degrades gracefully
(no message trees, no server-side validation, no engine-served plugin UIs) when
neither is present.

Response-variable discovery accepts the engine's XML list or equivalent class-keyed
JSON (`{"list":{"your.plugin.CustomStep":{"@version":"4.6.0", ...}}}`).
The caller supplies enabled source elements and all destination elements, matching
Swing's inclusion rules. Empty lists and providers returning no variables are valid;
invalid elements or a failing provider fail the request rather than return incomplete
results. No channel is saved and no generated JavaScript is evaluated. This endpoint
requires an updated Web Support plugin; clients must handle its absence separately
from a successful empty result.

## Vocabulary descriptions from datatype extensions

Swing obtains message-tree descriptions from each datatype's `getVocabulary()`.
To expose the same vocabulary on the server, an enabled extension can declare it
in its existing `webadmin/plugin.json`:

```json
{
  "id": "datatype-edifact",
  "vocabularies": {
    "EDIFACT": "com.mirth.connect.plugins.datatypes.edifact.EDIFACTVocabulary"
  }
}
```

Merge `vocabularies` into the existing manifest; retain its other fields. Each
key must match the installed datatype's plugin point name. Each class must be a
`MessageVocabulary` subclass in the engine's SHARED libraries with a public
`(String version, String type)` constructor and a matching `getDataType()`.
The constructor receives that message's serializer metadata, as in Swing.
No Swing client plugin is instantiated on the server.

The existing HL7 v2, X12, NCPDP and DICOM mappings remain authoritative. Extension
discovery includes only engine-enabled extensions; a manifest with
`"enabled": false` contributes no vocabulary. Missing, invalid, unavailable or
conflicting declarations fall back to bare labels without preventing message
serialization. Manifests are read on each request and vocabulary instances are
never shared, so changes in enabled discovery or declarations do not leave stale
results. Installing updated SHARED libraries still requires the engine's normal
restart. An extension without this declaration keeps its existing behavior.

This uses the existing serialization endpoint and response shape; no client API
version change is required.

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

### Signed build (YubiKey)

Requires a YubiKey holding a code-signing certificate and the OpenSC PKCS#11
library. One-time setup: copy `yubikey-pkcs11.cfg.example` to
`yubikey-pkcs11.cfg` and adjust the library path for your system, and place your
certificate chain in `certchain.pem` at the repo root (both files stay
untracked).

```bash
OIE_HOME=/path/to/oie mvn clean package -Psigning \
  -Dsigning.storepass=<yubikey-pin> \
  -Dwebclient.war=/path/to/oie-webadmin.war
```

Omit `-Dsigning.storepass` to read the PIN from the `YUBIKEY_PIN` environment
variable instead. Only `websupport-shared.jar` is signed; the WAR is left
byte-identical to the web client's published release asset so the release
workflow's digest check still holds. Verify with
`jarsigner -verify target/websupport-shared.jar`.

The web-client release workflow publishes `oie-webadmin.war`. The Web Support
release workflow downloads the release selected by `webclient.version` in
`pom.xml`, validates it, and records the exact client tag and SHA-256 in its
release notes. Keep that property equal to the Web Support version while the
release lines match, or change it when the projects version independently. There
is no source checkout, arbitrary commit ref, or repository variable to maintain.

## Notes

- Vocabulary descriptions use built-in mappings or enabled extensions' manifest
  declarations, loading SHARED classes at runtime without compile-time coupling.
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
