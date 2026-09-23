# Int8X16 x86-64 evidence — 2026-09-23

Captured on eak-quartus (Intel i9-12900K) with GHC 9.14.1 and pinned
Oracle Graal 25.3.4.1/JDK 25. Runtime source is
`7f175a300a6e08b2057fa4fcced428570f505b0d`; reader-only correction is
`e019a6ddc421addc9d3298b042df662ecf849056`.

## Verified results

- 9,168 genuine native/model rows, 18 strict positive pre/post audits and two
  exact unsupported vector-formal controls. Source/artifact hashes, toolchain,
  commands and actual guest-root counts are in `input-provenance.json`.
- 460 JVM tests in 96 suites pass in each of default and dense-handoff modes,
  with zero failures, errors or skips. Both `installDist` runs pass; dense
  handoff forces all 12 tasks. Full logs and all 192 XML reports are retained
  in the two JUnit archives, with individual hashes/counts in `test-suites.json`.
- Each mode checks 73,344 compiled invocations and 76,128 actual guest entries
  across the full corpus, pre/post Core, AST/bytecode and inlining on/off.
  Every measured call requires the exact 1/2 entry delta, unchanged actual
  targets and valid last-tier code. Both handoff pools release their references
  after every call. No retries, postcompile settling or limit changes.
- All sixteen graph captures pass: pre/post × AST/bytecode × plus/minus/times/
  negate. Each root checks 174 native rows twice after compilation (5,568 total).
  These runs disable counters; instrumented tests separately prove exact entries.
- Actual result-connected i8x16 arithmetic observes all sixteen lanes and lowers
  to XMM `VPADDB`/`VPSUBB`. Byte multiplication uses two i16x8 products inside
  128-bit registers, with exact low-byte masks, high-byte shifts and OR/byte
  reconstruction; final allocated LIR contains exactly two XMM `VPMULLW`.
- No surviving vector/carrier/payload allocation, lane boxing, field traffic or
  fallback call remains in the selected inlined graphs. The allowed public Long
  result box remains. The bytecode frame-tag records described below are virtual
  deoptimization metadata, not vector data or continuing-execution allocations.

This is not a throughput, globally allocation-free, no-spill, vector ABI,
cross-platform or machine-code-disassembly claim. Physical instruction checks
use final allocated-register LIR. Interpreted ByteVector fallbacks can allocate
private byte arrays; the durable runtime carrier itself has sixteen byte fields.

## Preserved reader failure and offline correction

All sixteen original guest runs succeeded exactly once. The initial checker
then rejected negation's `V128_BYTE(V256_BYTE)` spelling as a wider operation.
The actual instruction has XMM destination, both XMM operands and `size: XMM`;
the first operand is a 128-bit view of the immediately preceding wider zero.
The corrected reader verifies that exact matching-register zero definition and
full operand/instruction widths. It still rejects actual YMM/ZMM arithmetic,
nonzero/undefined casts and unrelated uses of wider register views.

Independent inspection also found the old stamp filter misclassified bytecode
`FrameWithoutBoxing.indexedTags` metadata. The correction proves the exact
virtual frame type, field list, indexedTags slot, matching Object/long arrays,
complete initial tag constants, shared FrameState mappings, and state-only use
closure. Unknown owners, vector payloads, materialization and escape still fail.
Thirty-two reader mutation tests pass normally and under Python `-O`.

The offline `--recheck-after-checker-fix` returned exit 0 and
`Int8X16 actual-Core graph gate passed: x86_64, 16 records`.
No guest run, warmup, compilation or runtime policy was repeated or changed.
Original checker/test sources, failed checker log/command/status=1, corrected
recheck log/command/status=0 and both checker hashes remain retained. Only those
two reader files differ from the frozen snapshot; runtime, probe, runner, JARs
and JDK files still match. `checkerCorrection` records that distinction.

## Retention and reproduction

`evidence.json` preserves original capture paths and SHA256 values. Each
`STAGE-BACKEND-ENTRY/graph.json.gz` decompresses to the exact parsed graph;
final LIR, guest/parser logs and command/status files are copied unchanged.
Both actual Core exports are retained compressed. `SHA256SUMS` covers every
retained file except itself. Raw 501 MB BGV/CFG/parsed material remains at
`build/int8x16-runtime-frozen` in the recorded checkout; it is not committed.

Fresh runs use `scripts/prepare-tests.sh`, `scripts/gradle.sh test installDist`,
the same full test with `JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true` and
`--rerun-tasks`, then `bash bench/experiments/int8x16-foundation/run-runtime.sh`
with a fresh output directory. Use the pinned environment and shared resource
gate around heavy jobs. No main or integration branch was modified.
