# OIE Web Client

Deploys the **OIE web administrator** — a browser-based replacement for the
Swing Administrator — into this OIE server. On restart the engine's embedded
Jetty serves it at `https://<host>:8443/oie-web-client/`; no Node process,
reverse proxy, or extra port is required.

## What it installs

This extension carries `oie-web-client.war` and copies it into
`<OIE_HOME>/webapps/` before Jetty scans web applications. The client discovers
its own context and the sibling engine API at runtime, so it needs no configured
server URL.

## Install it alongside Web Support

Two separate extensions, installed independently:

- **OIE Web Client** (this package) — hosts the browser UI on the engine.
- **Web Support** — the REST endpoints the UI uses for byte-exact message trees,
  JavaScript validation, and engine-served plugin UIs.

The client runs without Web Support, but message trees, Validate Script, and
engine-served plugin UIs are disabled until Web Support is installed. On a fresh
engine, install both through the Swing Administrator and restart once.

## Removing it

Uninstalling this extension removes its deployed WAR on the next engine restart —
the same restart that finalizes the uninstall. If the engine is force-killed
(rather than shut down cleanly) while an uninstall is pending, delete
`<OIE_HOME>/webapps/oie-web-client.war` by hand.

## Other ways to run the client

The same web client also runs standalone from source or the published Docker
image, which add capabilities a static WAR intentionally omits — multiple engine
targets, a user-entered engine URL, Node-managed TLS, and confidential-client
OIDC. See the [web client repository](https://github.com/gibson9583/oie-web-client).
