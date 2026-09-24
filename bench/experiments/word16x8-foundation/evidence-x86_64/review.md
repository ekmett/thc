# Independent review scope

Two independent workers reviewed this slice without running guests or modifying
the frozen captures. The primary worker also verified retained graph/Core copies,
native provenance, full-suite reports and checksums.

The xhigh reviewer checked all runtime and test changes, build inputs and CI.
It verified exactly eight final short fields, constant lane assembly/extraction,
all eight AST/bytecode unsigned unpack operations, distinct signed/unsigned
representation proofs, unchanged scalar literal/aggregate guards, and unchanged
vector calling-convention boundaries. It reviewed both-direction signedness
controls, exact per-call compiled-entry counts, actual guest/host target
identities, last-tier validity and both argument/result pool release checks.

All 65,536 encodings are checked in every lane through bijective affine inputs;
arithmetic samples are correlated and explicitly not presented as all 2^32
operand pairs. The separate eleven-by-eleven boundary grid is independently
observed at each selected lane. Native cases require exactly 40,928 compiled
calls and 45,216 guest entries per mode. Its initial report audit verifies seven
focused passing tests and 474 default passing tests in 98 unique suites, the
actual testcase identities and log counts, and byte-identical archived XMLs.

Its final archive review verifies all 196 saved reports against both archives
byte-for-byte, including every report/archive hash and every XML attribute.
Each mode has 98 unique suites and 474 unique actual testcase identities, with
identical testcase sets and no failures/errors/skips. Full logs each have 474
PASSED lines. Handoff records the forced-rerun command and flag in both launch
and test JVM contexts and executes all twelve tasks, including test/installDist.
Focused copies record seven first-run passes. The reviewer also rechecks all
native hashes and oracle rows, compressed Core copies, frozen source blobs,
JAR/JDK snapshots, all 84 capture hash references, retained graph/LIR/log copies
and zero run/parse/check statuses. Final README and SHA256SUMS were prepared by
the primary worker afterward and are outside that independent sign-off.

The ultra reviewer reconstructed all 5,116 native/model results directly from
the Haskell lane formulas with explicit signed-64-bit overflow, Word64 conversion
and unsigned low-sixteen-bit reduction, without importing the fixture model or
preparer. It verified 268 scalar pairs across seven entries, forty independent
lane domains with 81 cases each, and 1,464 high-bit unsigned observations. The
checksum bounds 6,422,430 and 6,422,478 for the helper exclude result overflow.
Actual pre/post Core has six one-root and two two-root entries, no hidden
lets/lambdas, thirty exact vector proofs and two genuine narrow literal sites
per stage. It reconstructed the signed metadata mutations and exact 18/4 issue
counts independently, then verified all seventeen source/seven artifact hashes.

Its actual graph audit verifies all 84 per-record raw/parsed/LIR/log/command/
status hashes, graph selection, target and compilation identities. All 71 frozen
source hashes match committed blobs at 5bbdb040; eleven JARs, four JDK files and
the fresh native provenance match. Independent Value-edge traversal proves all
96 cut-to-ZeroExtend16-to64 chains reach the public box and Return. Each graph
has exactly one live i16x8 add/subtract/multiply and the corresponding physical
XMM instruction: four VPADDW, four VPSUBW and four VPMULLW across twelve captures.
Six bytecode graphs have only the precisely proved FrameWithoutBoxing.indexedTags
virtual byte array (node 109, length 28); AST graphs have none. No payload,
allocation, field, call or intermediate lane box is admitted by the other gates.

All twelve capture runs check 268 native answers twice after compilation,
totaling 6,432 comparisons with unchanged active targets and valid installed
last-tier entries. The checker passes first time, with no correction or replay.
Twenty-nine reader mutation tests pass normally and under Python -O. The reader
also rejects all twelve corresponding real signed Int16 captures for missing
unsigned widening; recorded input hashes and reasons are retained separately.
Evidence SHA256 is
`c066d9058166a19307f2e05e9e441f48dc2a76326daed9d2d4bd674a615f3802`.

These are bounded correctness, representation, compiled-execution and packed-x86
reviews. They do not establish throughput, globally allocation-free execution,
no spills, vector calling conventions or execution on other architectures.
