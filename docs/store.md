# Web Support

Engine-side endpoints for the **OIE web administrator** — message-tree
serialization through the engine's own data type serializers, server-side
JavaScript validation with the engine's Rhino, and serving of installed
extensions' web UIs. No engine changes required.

## Companion: OIE Web Client

Web Support provides the APIs only. A separate **OIE Web Client** extension
(`oie-webadmin`) deploys the browser administrator into this engine and is listed
beside this one in the store. Install Web Support for the APIs, add **OIE Web
Client** to have the engine host the UI, or install both.

## Why you want it

Without this plugin the web administrator still works, but three things are off:

- **Message trees** in the channel editor (byte-exact serialization + element
  descriptions for HL7 v2, X12, NCPDP, DICOM)
- **Validate Script** (the engine's real Rhino compiler, matching runtime behavior)
- **Plugin UIs served by the engine** — including this store's own interface

## The bootstrap note

If you are reading this *inside* the Community Store, Web Support is already
installed (it serves this very page). Updates flow through the store normally.

For a fresh engine, install Web Support once via the Swing Administrator and
restart. If a web client is already available, its **Extensions** page can install
it too. After that, updates flow through the store normally.
