# Independent review scope

Two independent workers reviewed this slice without executing guests or changing
the frozen captures. Their findings were also checked by the primary worker for
this slice.

The xhigh reviewer checked the runtime, exact representations/literals, primitive
carrier layout, sixteen lane mappings, strict compiled-entry/target/pool checks,
build inputs and CI wiring. It verified all 192 full-suite XML hashes and archived
copies, suite/log counts, forced handoff flag, 17 native source and 7 artifact
hashes, compressed Core/oracle copies and runtime/JDK snapshot hashes. It then
independently checked both reader exceptions against the actual graph/LIR data
and reviewed their fail-closed mutation controls. No concrete finding remained.

The ultra reviewer reconstructed all 9,168 native/model rows independently from
the Haskell lane algebra and checksum weights, including signed machine overflow
and every per-lane edge grid. It inspected actual pre/post Core closures, residual
helper calls and exact sixteen-lane tuple results rather than relying on reported
root counts. It authored the graph reader and narrowly scoped correction.

Its final evidence audit verified all 112 per-record raw/parsed/LIR/log/command/
status hashes, compilation identities, target chains, all sixteen connected lane
cuts, and all recorded instruction counts: four VPADDB, eight VPSUBB and eight
VPMULLW across sixteen captures. It verified 67 frozen source blobs, 11 runtime
JARs, four JDK files and the two allowed reader-only differences, archived original
failure and successful offline recheck. Corrected evidence SHA256 is
`f5326fcbec1741930982769555caa4e6c9f266dfb574d33acc64b3f0bfd79222`.

These are bounded correctness/representation/compiled-execution and packed-x86
reviews. They do not establish performance, a globally allocation-free ABI,
no spills, vector calling conventions or execution on other architectures.
