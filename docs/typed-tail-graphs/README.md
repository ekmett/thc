# Frozen typed-tail graphs

[manifest.json](manifest.json) identifies the source/JAR mapping and archive
hashes for these frozen builds.
[Cycle and Map BGVs](selected-bgv.tar.xz) and
[application BGVs](focused-applications-bgv.tar.xz) retain original compiler
captures. Selected phase JSON preserves node IDs, positions, edges and blocks;
SVGs disclose omitted labels.

The `*-source` and `cycle-*` scripts retain capture-time paths and configuration.
Use [graph inspection](../graph-inspection.md) for new runs.
`tools/GraphInspect.java` and `tools/audit-call-packets.py` can inspect the saved
BGVs. These graphs describe static sites, not allocation rates or current-code
performance.
