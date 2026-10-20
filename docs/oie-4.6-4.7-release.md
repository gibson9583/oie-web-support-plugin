# 1.1.1 release preparation

- Plugin version: 1.1.0 → 1.1.1.
- Declared engine support is limited to OIE 4.5.2, 4.6.0, and 4.7.0, in every supplied engine descriptor.
- Compile-time engine baseline: 4.5.2. Compilation and declared runtime support are separate.
- Catalog engine bounds: 4.5.2 through 4.7.0, inclusive. Publishing workflows retain both bounds from the tagged manifest.
- Maven, engine descriptor, catalog, and Web Administrator package versions are synchronized where supplied. Web Administrator API requirements are independent of engine release numbers and remain unchanged.

This is release preparation. Runtime compatibility on the final OIE 4.7.0 tag is pending. Before publishing, build one ZIP and test that exact ZIP on each declared engine: installation, restart/loading, functionality, permissions/error paths, and upgrade from 1.1.0. Include Swing/Web Administrator and source/destination surfaces where supplied. Retain existing database and cluster upgrade instructions.

Web Support now compiles against pinned stock OIE 4.5.2 libraries and still bundles web client 1.1.0. The compile version is independent of the catalog minimum. Its 23 Java tests pass with those 4.5.2 libraries; running the complete bundled browser client on 4.5.2 still requires runtime validation.
