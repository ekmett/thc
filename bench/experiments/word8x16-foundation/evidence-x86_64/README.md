# Word8X16 x86-64 evidence — 2026-09-23

Captured on eak-quartus (Intel i9-12900K) with GHC 9.14.1 and pinned
Oracle Graal 25.3.4.1/JDK 25. Runtime and graph checker source is
`cf0f03df51346bfdd96a104f952ad37eef99d88a`.

## Verified results

- Exactly six unsigned pack/unpack/broadcast/plus/minus/times primitives.
  Pinned GHC has no Word8X16 negate. Word8Rep/VecRep 16 Word8ElemRep remain
  distinct from signed representations despite equal-sized byte storage.
- 7,712 genuine native/model rows, sixteen strict positive pre/post audits, two
  exact unsupported vector-formal controls, and four explicitly labeled signed/
  unsigned metadata mutation audits. Source/artifact hashes, native commands,
  toolchain and actual guest-root counts are in `input-provenance.json`.
- 467 JVM tests in 97 suites pass in each of default and dense-handoff modes,
  with zero failures, errors or skips. Both `installDist` runs pass; dense
  handoff forces all twelve tasks. Full logs and all 194 XML reports are retained
  in two JUnit archives, with individual hashes/counts in `test-suites.json`.
- Each mode checks 61,696 compiled invocations and 64,512 actual guest entries
  across the full corpus, pre/post Core, AST/bytecode and inlining on/off.
  Every measured call requires the exact 1/2 entry delta, unchanged actual
  targets and valid last-tier code. Both handoff pools release their references
  after every call. No retries, postcompile settling or compiler-limit changes.
- All twelve graph captures pass on their first check: pre/post × AST/bytecode
  × plus/minus/times. Each root checks 176 native rows twice after compilation
  (4,224 total). These runs disable counters; instrumented JVM tests separately
  prove exact guest-entry counts.
- All 192 result-connected byte lanes require unsigned ZeroExtend 8-to-64-bit
  conversions. Addition/subtraction lower to XMM VPADDB/VPSUBB. Multiplication
  uses two i16x8 products inside 128-bit registers with exact low-byte masks,
  high-byte shifts and OR/byte reconstruction. Final allocated LIR contains
  four VPADDB, four VPSUBB and eight VPMULLW instructions across the captures.
- No surviving vector/carrier/payload allocation, lane boxing, field traffic or
  fallback call remains in these selected inlined graphs. The public Long result
  box remains. Six virtual byte arrays are precisely proven frame indexedTags
  deoptimization metadata, not vector data or continuing-execution allocations.
- Python checks pass: 65 auditor, sixteen vector, ten independent model/proof,
  four primop-coverage, and 34 graph-reader mutation tests (also under Python -O).
  The unsigned gate separately rejects all twelve corresponding real signed
  Int8 captures for missing unsigned lane extensions; the input hashes and exact
  rejection reasons are retained in `signed-capture-controls.json`.

This is not a throughput, globally allocation-free, no-spill, vector ABI,
cross-platform or machine-code-disassembly claim. Physical instruction checks
use final allocated-register LIR. Interpreted ByteVector fallbacks can allocate
private byte arrays; the distinct durable carrier itself has sixteen byte fields
(16 bytes of lane payload, not total object size). Vector calling-convention
frontiers remain unchanged.

## Preserved focused-test failure

The initial focused run had six passing tests and one wrong test expectation:
diagnostic mode deliberately defers an unresolved aggregate error until demand.
The test expected load-time rejection in both modes. Its correction requires
the exact default UnsupportedCore error, or the exact diagnostic deferred reason,
on-demand failure, exactly one trap, and released pools. Ordinary malformed
proofs still reject at load. Both strict compiled tests already passed in that
initial run. No runtime policy was changed to accommodate the failure.

`focused-initial.xml` and `test-focused-initial-word8x16.log` retain that failure.
The corrected focused run and both complete modes pass all seven Word8 tests.
The graph capture is separate and succeeded first time: `checkerCorrection` is
null, with no checker correction or guest replay.

## Retention and reproduction

`evidence.json` preserves original capture paths and SHA256 values. Each
`STAGE-BACKEND-ENTRY/graph.json.gz` decompresses to the exact parsed graph;
final LIR, guest/parser logs and command/status files are copied unchanged.
Both actual Core exports are retained compressed. `SHA256SUMS` covers every
retained file except itself. Raw 389 MB BGV/CFG/parsed material remains at
`build/word8x16-runtime-frozen` in the recorded checkout; it is not committed.
All 69 source, eleven runtime JAR and four JDK hashes remain equal to the frozen
snapshot after the forced handoff rebuild.

Fresh runs use `scripts/prepare-tests.sh`, `scripts/gradle.sh test installDist`,
the same full test with `JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true` and
`--rerun-tasks`, then `bash bench/experiments/word8x16-foundation/run-runtime.sh`
with a fresh output directory. Use the pinned environment and shared resource
gate around heavy jobs. No main or integration branch was modified.
