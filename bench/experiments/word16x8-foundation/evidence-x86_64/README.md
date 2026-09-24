# Word16X8 x86-64 evidence — 2026-09-23

Captured on eak-quartus (Intel i9-12900K) with GHC 9.14.1 and pinned
Oracle Graal 25.3.4.1/JDK 25. Runtime and graph checker source is
`5bbdb040a26ef98c784c2772e5eae0466a534123`.

## Verified results

- Exactly six unsigned pack/unpack/broadcast/plus/minus/times primitives.
  Pinned GHC has no Word16X8 negate. Word16Rep/VecRep 8 Word16ElemRep remain
  distinct from Int16 representations despite equal-sized short storage.
- 5,116 genuine native/model rows, sixteen strict positive pre/post audits,
  two exact unsupported vector-formal controls, and four explicitly labeled
  signed/unsigned metadata mutation audits. Source/artifact hashes, native
  commands, toolchain and actual guest-root counts are in `input-provenance.json`.
- 474 JVM tests in 98 suites pass in each of default and dense-handoff modes,
  with zero failures, errors or skips. Both installDist runs pass; dense handoff
  forces all twelve tasks. Complete logs and all 196 XML reports are retained
  in two archives, with individual hashes/counts in `test-suites.json`.
- Each mode checks 40,928 compiled calls and 45,216 actual guest entries across
  the full corpus, pre/post Core, AST/bytecode and inlining on/off. Every measured
  call requires exact +1/+2 entry deltas, unchanged actual targets and valid
  last-tier code. Both handoff pools release their references after every call.
  There are no retries, postcompile settling or compiler-limit changes.
- The seven focused Word16 tests pass first time, including all 65,536 encodings
  in every lane and independent eleven-by-eleven boundary pairs per selected
  lane. Exhaustive coverage of all 2^32 binary operand pairs is not claimed.
- All twelve actual graph captures pass their first check: pre/post ×
  AST/bytecode × plus/minus/times. Each root checks 268 native answers twice
  after compilation (6,432 total). Graph runs disable entry counters; the
  instrumented JVM tests separately prove exact compiled-entry counts.
- All 96 result-connected short lanes require exact unsigned ZeroExtend
  16-to-64-bit conversions. Each graph has one live i16x8 arithmetic node and
  one corresponding physical XMM word instruction. Final allocated LIR contains
  four VPADDW, four VPSUBW and four VPMULLW across the captures. Multiplication
  is a direct packed short product, not the two-product byte expansion.
- No vector/carrier/payload allocation, intermediate lane box, field traffic or
  fallback call survives in these selected inlined graphs. The public Long
  result box remains. Six virtual byte arrays are precisely proved frame
  indexedTags deoptimization metadata, not vector data or continuing-execution
  allocations.
- Python checks pass with no skips: 65 auditor, eighteen vector, ten independent
  model/proof, and four primop-coverage tests. Twenty-nine reader mutation tests
  pass normally and under Python -O. The unsigned reader also rejects all twelve
  corresponding real signed Int16 graphs specifically for missing unsigned
  widening; `signed-capture-controls.json` retains input hashes and exact reasons.

Word16 classes, focused tests, both full regressions and the graph capture all
succeeded on their first runs. The graph has `checkerCorrection: null`; no guest
capture was replayed, reader gate relaxed, or original failure discarded.

This is not throughput, globally allocation-free, no-spill, vector ABI,
cross-platform or machine-code-disassembly evidence. Physical instruction checks
use final allocated-register LIR. Interpreted ShortVector fallbacks can allocate
private short arrays. The distinct durable carrier has eight final short fields:
sixteen bytes of lane payload, not total object size. Vector calling-convention
frontiers and existing scalar literal/aggregate guards remain unchanged.

## Retention and reproduction

`evidence.json` preserves original capture paths and SHA256 values. Each
`STAGE-BACKEND-ENTRY/graph.json.gz` decompresses to the exact parsed graph.
Final LIR, guest/parser logs and command/status files are copied unchanged.
Both actual Core exports are retained compressed. `SHA256SUMS` covers every
retained file except itself. Raw captures remain at
`build/word16x8-runtime-frozen` in the recorded checkout (about 274 MiB),
uncommitted. All 71 source, eleven runtime JAR and four JDK hashes remain equal
to the frozen snapshot after the forced handoff rebuild.

Fresh runs use `scripts/prepare-tests.sh`, `scripts/gradle.sh test installDist`,
the same full test with `JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true` and
`--rerun-tasks`, then `bash bench/experiments/word16x8-foundation/run-runtime.sh`
with a new output directory. Use the pinned environment and shared resource gate
for heavy jobs. No main/integration branch or completed Word8 evidence changed.
