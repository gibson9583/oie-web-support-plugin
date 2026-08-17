# Web Support

Installs the **OIE web administrator** in the engine's embedded web server,
together with message-tree serialization through the engine's own data type
serializers, server-side JavaScript validation with the engine's Rhino, and
serving of installed extensions' web UIs. No engine changes required.

## Why you want it

One install provides the browser administrator at `/oie-webadmin/` and enables:

- **Message trees** in the channel editor (byte-exact serialization + element
  descriptions for HL7 v2, X12, NCPDP, DICOM)
- **Validate Script** (the engine's real Rhino compiler, matching runtime behavior)
- **Plugin UIs served by the engine** — including this store's own interface

## The bootstrap note

If you are reading this *inside* the Community Store, Web Support is already
installed (it serves this very page). Updates flow through the store normally.

For a fresh engine, install Web Support once via the Swing Administrator and
restart. If a web client is already available through Node.js or Docker, its
**Extensions** page can install it too. After that, updates flow through the store
normally.
