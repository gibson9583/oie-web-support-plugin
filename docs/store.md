# Web Support

Engine-side endpoints for the **OIE web administrator** — message-tree
serialization through the engine's own data type serializers, server-side
JavaScript validation with the engine's Rhino, and serving of installed
extensions' web UIs. No engine changes required; no UI of its own.

## Why you want it

Without this plugin the web administrator still works, but three things are off:

- **Message trees** in the channel editor (byte-exact serialization + element
  descriptions for HL7 v2, X12, NCPDP, DICOM)
- **Validate Script** (the engine's real Rhino compiler, matching runtime behavior)
- **Plugin UIs served by the engine** — including this store's own interface

## The bootstrap note

If you are reading this *inside* the Community Store, Web Support is already
installed (it serves this very page). Updates flow through the store normally.

For a fresh engine, install `websupport-<version>.zip` once via the web
administrator's **Extensions** page (or the Swing Administrator) and restart —
it is the only extension that ever needs a manual install.
