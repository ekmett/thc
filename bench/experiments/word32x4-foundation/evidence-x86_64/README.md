# Word32X4 x86-64 evidence — 2026-09-23

Captured on eak-quartus (Intel i9-12900K) with GHC 9.14.1 and pinned
Oracle Graal 25.3.4.1/JDK 25. Runtime and graph-reader source is
`b048144c75a775f6f1538bf7e1b04119f39a1163`.

## Verified results

- Six unsigned pack/unpack/broadcast/plus/minus/times primitives, as provided
  by pinned GHC. There is no unsigned vector negate. Word32Rep and
  VecRep 4 Word32ElemRep remain distinct from signed Int32 identities.
- 4,882 native/model rows, sixteen strict positive pre/post audits, two exact
  vector-formal negatives and four explicitly labeled signedness mutation
  audits. Tuple/vector mutations require exactly ten/four issues, respectively.
  Native commands, seventeen source hashes, seven artifact hashes, actual
  closure counts and precise lane proofs are retained in input-provenance.json.
- 481 JVM tests in 99 suites pass in each of default and dense-handoff modes,
  with zero failures, errors or skips. Both installDist runs pass; dense handoff
  forces all twelve tasks. Two archives preserve all 198 XML reports, with
  individual hashes, attributes and counts in test-suites.json.
- Each mode checks 39,056 compiled invocations and 46,512 exact guest entries
  across pre/post Core, AST/bytecode and inlining on/off. Every measured input
  requires exact +1/+2 entry deltas, stable actual guest and host target
  identities, valid last-tier code and empty argument/result handoff pools.
  No retries, postcompile settling or compiler-limit increases are used.
- Seven focused tests pass, including 65,536 constructed samples covering all
  encodings of each 16-bit half in every lane, and independent Cartesian
  power-neighborhood/boundary pairs using BigInteger products. Neither all
  2^32 Word32 values nor all 2^64 operand pairs are claimed to be exhausted.
- All twelve actual captures pass their first check: pre/post × AST/bytecode ×
  plus/minus/times. Each root checks 466 native answers twice after compilation,
  totaling 11,184 comparisons. Graph runs disable entry counters; instrumented
  JVM tests separately prove exact compiled-entry counts.
- All 48 packed result lanes feed exact ZeroExtend32-to-64 conversions and the
  public checksum. Each graph has one live i32x4 arithmetic node and one physical
  XMM instruction: four VPADDD, four VPSUBD and four VPMULLD across the captures.
- No local vector carrier, payload allocation, field traffic, fallback call or
  intermediate lane box survives in these selected inlined graphs. One public
  Long result box remains. Six bytecode graphs retain a precisely proved virtual
  FrameWithoutBoxing.indexedTags byte array (node109, length16), used only for
  deoptimization metadata. AST graphs have no such byte array.
- Python checks pass without skips: 65 auditor, twenty vector, ten model/proof
  and four primop-coverage tests. Thirty reader mutation tests pass normally and
  under Python -O; the ten model/proof tests also pass in both modes.

Classes, full fixture preparation, focused tests, both full regressions and
actual graph captures pass on their first runs. checkerCorrection is null.
No failed Word32 execution was retried, hidden or replaced.

This is bounded packed-code evidence, not throughput, globally allocation-free
execution, no spills, vector ABI, cross-platform native conformance or raw
machine-code disassembly. Physical instructions are checked in final allocated
LIR. Interpreted IntVector fallbacks can allocate private int arrays. The durable
carrier has four final int fields: sixteen bytes of lane payload, not object size.
Scalar literal/aggregate guards and vector calling-convention frontiers are unchanged.

## Signed comparison controls

signed-controls/ retains eight real historical Int32 add/subtract parsed graphs,
a report and an offline review script. The full Word32 reader rejects these at
their four-input host arity, versus this fixture's two-input arity. Separately,
all 32 actual result-connected lane chains use SignExtend32-to-64 and fail the
unsigned widening predicate. This is not claimed as an unsigned-specific
full-reader rejection, and there is no signed multiplication capture.

Those lossless gzip copies were packaged during this review from retained local
parsed graphs; original and compressed hashes are recorded. Historical published
evidence recorded graph summaries, not parsed-file hashes. The report clearly
distinguishes this later packaging from original capture provenance.

## Retention and reproduction

evidence.json preserves original paths and SHA256 values. Each
STAGE-BACKEND-ENTRY/graph.json.gz decompresses to the exact parsed graph.
Final LIR, command/status files and guest/parser logs are copied unchanged.
Both original Core exports are compressed losslessly. SHA256SUMS covers every
retained file except itself. Raw captures remain at build/word32x4-runtime-frozen
in the recorded checkout, about 227 MiB, uncommitted. All 73 source, eleven JAR
and four JDK hashes match the frozen snapshot after the forced dense rebuild.

Fresh runs use scripts/prepare-tests.sh, scripts/gradle.sh test installDist,
the same full test with JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true and --rerun-tasks,
and bench/experiments/word32x4-foundation/run-runtime.sh with a new output path.
Use the pinned environment and shared resource gate for heavy jobs. Parent owns
PR/integration; completed Word8 and Word16 branches remain frozen.
