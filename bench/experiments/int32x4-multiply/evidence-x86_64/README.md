# Signed Int32X4 multiplication evidence — 2026-09-23

Captured on eak-quartus (Intel i9-12900K), Linux x86-64, using GHC 9.14.1 and
Oracle GraalVM 25.3.4.1/JDK 25. Runtime and original graph-reader revision:
`597ed24ee8835606437a7cdeb313e28b726d4762`.

## Verified results

- Only `timesInt32X4#` is added: two exact signed vectors, low-32-bit products,
  four primitive int carrier fields, and signed widening on unpack. Existing
  scalar-literal, aggregate, vector ABI and compiler policies are unchanged.
- 1,722 fresh native/model rows: three 466-pair scalar/helper entries and four
  complete 9-by-9 independent lane grids. These include 144 negative single-lane
  observations. Eight strict positive pre/post audits pass; genuine vector
  formals remain rejected, and unsigned tuple/vector mutations require exactly
  ten/four issues per stage. Seventeen source and seven artifact hashes retain
  native provenance, literal sites, exact proofs and actual 1/1/2/2 root counts.
- Six focused JVM tests pass. Full default and dense-handoff runs each pass
  487 tests in 100 suites, without failures, errors or skips. Both installDist
  runs pass; dense handoff forces all twelve tasks. Both complete XML archives
  preserve all 200 reports; test-suites.json records their hashes and attributes.
- Each mode checks 13,776 compiled calls and 21,232 exact guest entries across
  pre/post Core, AST/bytecode and inlining on/off. Every measured call requires
  exact +1/+2 increments, stable active guest/host target identities, valid
  last-tier code, and released argument/result handoff pools. No retries,
  settling calls or compiler-limit changes are used.
- Arithmetic tests include 65,536 constructed samples with both 16-bit halves
  covered bijectively in every lane, plus per-lane Cartesian boundary and
  power-neighborhood pairs with independent BigInteger product reduction.
  Neither the entire 32-bit domain nor all binary operand pairs are exhausted.
- Four actual pre/post × AST/bytecode captures pass their first check. Each
  checks 466 native results twice after compilation, totaling 3,728 comparisons.
  All sixteen result lanes use exact SignExtend32-to-64 chains connected to
  the public checksum. Each graph has one live i32x4 MulNode and one physical
  XMM VPMULLD in final allocated-register LIR.
- No local carrier, vector payload allocation, field traffic, residual call or
  intermediate lane box survives in those selected inlined graphs. One public
  Long box remains. Both bytecode graphs retain only the precisely proved
  virtual FrameWithoutBoxing.indexedTags byte array, node109 length16, within
  the byte-array exception; AST graphs have none. Frame metadata is not a
  surviving vector payload. These are not globally allocation-free claims.
- Python checks pass normally and under `-O`, without skips: 65 auditor,
  22 vector, ten model/proof, four primop-coverage and thirty reader tests.
- All 73 source hashes match the frozen Git blobs. Eleven runtime JAR and four
  JDK hashes remain unchanged after the forced rebuild. Offline integrity review
  verifies all 28 capture artifact hashes and exact compressed graph/Core copies.

Classes, native preparation, focused tests, both full regressions and graph
capture/check pass on their first attempts. `checkerCorrection` is null.
One offline Python wrapper named nonexistent `test-coverage-report.py` after
three suites had passed. Its original script, command, log and exit2 remain in
python-checks/. The separately named completion wrapper ran only unattempted
checks with the correct `test-primop-coverage.py` path. No native/JVM execution,
source/test change or gate adjustment was made in response.

Graph captures disable entry counters; the exact counts above come from the
separate instrumented runtime tests. This evidence does not establish throughput,
no spills, vector calling conventions, cross-platform native conformance or
raw machine-code disassembly. Interpreted IntVector fallbacks can allocate private
int arrays; a public Long result can allocate. The carrier's sixteen bytes of
lane payload are not its complete object size.

## Opposite-signedness controls

unsigned-controls/report.json references four original committed Word32
multiplication graphs with the same two-input host arity. All pass their original
unsigned checker and fail the full signed checker specifically at the required
SignExtend32-to-64, not an earlier arity check. All sixteen actual result-connected
unsigned lanes use ZeroExtend32-to-64. These are not signed native executions.

The report records original compressed and decoded hashes; graph payloads remain
in the existing Word32 evidence directory instead of being duplicated here.
review-unsigned-controls.py is explicitly a review-time reconstruction of the
original ephemeral offline command. A later root invocation reproduced the exact
report hash; it neither changes inputs nor executes guests.

## Retention and reproduction

evidence.json retains original paths and hashes. Each STAGE-BACKEND-timesCase
directory preserves the exact final LIR, guest/parser logs, commands/statuses,
and losslessly compressed selected graph. Both original Core exports are retained
compressed. Raw BGV/CFG remain under build/int32x4-multiply-runtime-frozen in the
recorded checkout, approximately 77 MiB, uncommitted. SHA256SUMS covers every
retained file except itself. Supplemental scripts accept `--root CHECKOUT` and
read the retained originals without guest execution.

Fresh runs use the pinned environment
`/home/ekmett/thc-benchmarks/2026-09-23-083914/environment.sh`. Run complete
scripts/prepare-tests.sh, then scripts/gradle.sh test installDist. Repeat the full
test/build with JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true and --rerun-tasks.
Capture with bench/experiments/int32x4-multiply/run-runtime.sh and a fresh output
directory. Exact recorded Gradle arguments and environment are in test-suites.json.

These results describe the recorded revision; later integration changes require
their own validation.
