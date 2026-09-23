# Frozen typed-tail graph evidence

The source/JAR mapping and archive hashes are in [manifest.json](manifest.json). This evidence belongs to the measured `cb74882` build, not later working-tree changes.

- [Cycle graph review](cycle-review.md): old bytecode versus repaired bytecode and AST, including primitive phi/backedge proof and actual CFGs.
- [Focused application review](application-review.md): eliminated direct/captured application intermediates, and the surviving PAP construction boundary.
- [Map graph comparison](map-graph-comparison.md): ten matched roots and exact inlining/packet inventories.
- [Selected cycle and Map BGVs](selected-bgv.tar.xz) and [focused application BGVs](focused-applications-bgv.tar.xz): original compiler captures, verified after decompression.

Selected C-root and application-root JSON includes node IDs, source positions, all edges and scheduled blocks. The cycle SVGs preserve scheduled edges and selected compiler node labels; omitted debug/constant labels are disclosed on each image. Application SVGs come directly from the full selected CFG output.

`*-source.py`, `*-source.sh`, and `cycle-*.py`/`.sh` preserve analysis/capture source from the original working directories. Some use those original paths and are provenance, not standalone installed commands. The two JSON fixtures are self-contained Core inputs. With the recorded runtime installed, they can be run through `thc.ProbeKt` using an explicit `-Dthc.backend=ast` or `bytecode`; the capture logs retain compilation IDs and the original provenance files retain classpath and harness hashes. Existing `tools/GraphInspect.java` and `tools/audit-call-packets.py` read the BGVs and regenerate the graph/packet inventories.

All counts are static compiler sites. They do not substitute for runtime allocation measurements or imply execution frequency. The cycle and application checksums validate the selected input cycles; the separate semantic tests cover other control-flow and representation cases.
