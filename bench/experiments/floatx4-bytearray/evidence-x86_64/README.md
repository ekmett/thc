# Verified FloatX4 managed-memory checkpoint

Final runtime/test source: `4281921a0422404185212acbfb1cd7aadd85b3f6`.
Host: Linux x86-64, Intel i9-12900K; pinned GHC 9.14.1 and Graal distribution
25.3.4.1+1.1 / JDK25.0.4.1+1-LTS-jvmci-25.3-b22.
No production compiler policy, graph-budget increase, retry or postcompile
settling was introduced. General vector and transported State/vector ABIs
remain unsupported.

## Results and attribution

The initial `914ed462` checkpoint passed focused 15 and full 530 default + 530
dense-handoff tests, but its first graph campaign failed correctly. All 16
native-backed graph runs completed; independent offline inspection found eight
passing bytecode graphs and eight genuinely failing AST graphs. Kotlin's enum
`when` lowering retained a mutable switch-map array read, all family branches
and a default exception allocation/stack-trace call. AST loads additionally
retained vector carriers and FloatVector lane calls. Integer-branch packed
loads were not mistaken for Float memory evidence.

The minimal runtime fix `4281921` replaces the two AST enum-subject switches
with direct exact family-identity predicates and an explicit fail-closed fault.
It keeps the closed Int32/Word32/Float32 mapping and operand/State ordering.
Independent source and offline JVM-bytecode review confirmed six identity
comparisons and no switch-map/ordinal/array-load dispatch in this node. The
metadata-only enum mapping elsewhere is not claimed removed. No graph reader
gate was weakened. A new frozen campaign, not an offline reinterpretation of
the failed execution, verifies the corrected runtime.

Final focused 15 and full 530 default + 530 dense-handoff tests pass: 109
suites in each full mode, identical method sets, zero failures/errors/skips.
All 16 corrected-runtime Float captures pass the unchanged live-code gates:
eight packed loads with 32 live f32 lane products, eight packed stores with 32
exact L2F inputs, 1,024 installed-target comparisons, eight physical XMM
VMOVDQU32 loads and eight VMOVUPS stores. Each invocation receives fresh
64-byte storage; all bytes are checked and stores return the identical array.

The corrected runtime's first reader check exposed a separate metadata false
positive in four AST stores. Pinned compiler sources show that virtual frame
primitive long[] entries retain f32 values under Float tag 4. Reader-only
commit `1dff41f3a7bf04009e5eff7d35c8f368e60b082d` recognizes that exact
owner/tag/kind/snapshot/non-escape contract. It admits no live array or vector
payload, unknown tag, double value, object slot or mismatched snapshot. The
original failed checker and log are preserved; provenance-bound offline recheck
succeeded with all non-reader inputs and binaries unchanged and no guest replay.
The 42 reader tests include positive/default Float frame cases and hostile
width/tag/object/owner/escape/snapshot mutations.

Both established integer families were recaptured on the same runtime:
16 Int32 and 16 Word32 graphs pass on first check, each with 1,152 installed
comparisons and 16 physical XMM VMOVDQU32 accesses. Signed versus zero-extension
of all four load lanes remains explicit. Total corrected-runtime coverage is
48 accepted graph/LIR records and 3,328 installed-target comparisons.

Float graph evidence SHA-256:
`985e3a862662e8717baa1c884b114979f12a6b39e24925402ff37dfd8491eec1`.

The portable native/model corpus has 6,720 byte-identical rows across twelve
scalar entries. It rotates 16 raw encodings through every lane, covers all four
vector offsets and all 13 scalar offsets in 64-byte arrays, checks every output
byte, and preserves before/after snapshots across an aliasing mutation. These
are coupled rotations, not a pattern-by-offset Cartesian product. Observations
use existing Word32/Float scalar-array aliases; no scalar bitcast primop is
required. Exact movement covers signed zero, subnormal/normal boundaries,
infinities and selected signed quiet-NaN payloads.

The 640 selected signaling-NaN native observations are separate from the
portable corpus, instrumented guest calls and finite graph inputs. Their exact
match on this pinned host is not a portable Java scalar-copy/pack guarantee or
an arithmetic NaN-payload guarantee. Direct carrier tests separately report
selected native-order byte movements; these observations remain host-qualified.
Big-endian conversion is implemented but not exercised by this campaign.

Current native provenance SHA-256:
`c9ce1949ff1ab8d9ae79d968fee1afb307e0671a23fde7afd15ef4cedc8541aa`.
The 19 declared preparation-source and nine artifact hashes, plus actual GHC
ELF and launcher hashes, verify. This is not the complete runtime inventory:
FloatX4.java, CoreVectors.kt and backend implementation are covered by the
separate 75-source graph snapshot, together with 11 installed JARs and four JDK
files. It is not a complete hash inventory of GHC's libraries/packages.

Each complete JVM mode checks 53,760 compiled native/model calls and 160,256
exact guest entries across pre/post Core, AST/bytecode and inlining on/off.
Every row checks active target identity/last-tier validity, exact counter deltas
and empty handoff pools. Ordinary automatic compilation settings apply to this
corpus. The separate 96 cold-transition cases suppress automatic compilation
to prove invalidation without partial effects and recovery without recompilation
for six operations, both backends and eight invalid size/index classes.
Native undefined invalid offsets are never used as an oracle.

Graph instrumentation is disabled: captures check unchanged installed target
validity, not per-call compiled-entry counters. The instrumented correctness
suites provide that separate proof. Public Long result boxes and fresh caller
arrays are intentional; no live private carrier/vector/payload allocation or
field traffic is allowed in accepted selected paths. Exact reconstruction-only
frame and interpreter metadata is distinguished from executable payloads.
No throughput, no-spill, universally allocation-free execution, foreign/Addr
memory, general vector ABI or non-x86 claim is made.

## Reading the package

`evidence.json` describes the corrected Float graph/LIR records. Gzip payloads
decompress to the exact hash-covered graph, Core, native/model and LIR bytes.
`regression-int32x4/` and `regression-word32x4/` retain the separate fresh
integer-family captures on the same corrected runtime; these do not rewrite
their historical feature evidence. Raw BGV/CFG originals remain in local build
directories and are hash-covered, not duplicated in the accepted compact set.

`first-runtime-capture/` preserves the original failed checker, all 16 graph/run
records, parsed graphs and raw CFG files, plus a sealed failure classification.
Its summary points to hash-verified local copies of all original runtime
sources and installed JARs retained before rebuilding. The original campaign
is not relabeled as successful or as a reader defect.

`validation/` contains exact commands, statuses, logs and JUnit XML archives.
`before-family-dispatch/` attributes the initial passing suites/Python checks
to `914ed462`; `first-focused/` retains the earlier 10-test carrier/proof run.
The initial malformed Python-negative test and sandbox Gradle wildcard-IP
bootstrap failure are preserved separately, as is the manual GHC 8.8.4
environment-selection rejection before coverage inventory execution.

`native/` contains current strict audits, source/artifact provenance, keyed
TSVs, separate signaling-NaN observations and full preparation history. Each
prepare-run archive retains its own provenance and all nine artifact copies,
not merely references to subsequently regenerated shared paths.

Python checks pass without skips, normally and under `-O`: 65 auditor, 23 generic
vector, 14 signed/15 unsigned/16 Float memory proof, 10 signed/11 unsigned/11 Float
byte-model, 9 byte-array, 4 coverage and 42 graph-reader tests: 220 per mode.
Reader unit tests are parser checks, not compiler execution evidence.

`package-verification.json` records archive/original equivalence and totals.
`SHA256SUMS` seals every other package file; its hashes cover compressed bytes,
while individual provenance records cover uncompressed originals. Neither is
a cryptographic signature.
