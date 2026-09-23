# DoubleX2 x86_64 validation

Runtime/model/fixture/test revision: `efede84ddd2e915fbfe719b61ed5a601c12c81bb`.
Final graph-capture revision: `ea6ac3280a627328d1d1e867377f7970536ea04e`.
The latter adds native import/provenance tooling and AArch64 evidence only. A
content comparison verified that runtime, model, tests, compiler sources and
fixture hashes remain unchanged. Historical capture revisions are not rewritten
when this evidence is committed.

Host: eak-quartus, Intel i9-12900K, Linux x86_64; GHC9.14.1 and Oracle Graal
25.3.4.1/JDK25. Exact tool metadata, native commands and all source/JAR/native
hashes are in the retained provenance and runtime snapshot.

## Results

- Fresh GHC native output matches all 3,390 integer-model rows. All 13 positive
  entries pass strict audits at both genuine Core stages; the vector-formal
  control is specifically rejected at both stages.
- Full default and handoff suites each pass **360 tests in 73 suites**, zero
  failures/errors/skips. installDist passes. XML hashes and full-run log hashes
  are in validation-summary.json; the five-test DoubleX2 suite XMLs are retained.
- Each ABI mode checks 13,560 compiled invocations against the exact stage-derived
  counts, totaling 45,732 guest-root entries. This uses normal guest inlining;
  it is not a no-inlining/residual-call claim. No postcompile settling/retries.
- All 12 pre/post × AST/bytecode × add/subtract/multiply production graphs pass.
  Each uses 225 native rows in two postcompile passes: 5,400 result/active-target/
  installed-validity checks. Counters are deliberately disabled in graph captures;
  the separate JVM test supplies the compiled-entry evidence.
- All final LIRs contain physical **XMM V128_DOUBLE VADDPD, VSUBPD or VMULPD**
  for their selected operation. AST graphs have 39 nodes, bytecode 64. No temporary
  vector/wrapper/backing-array allocation, intermediate floating box, field
  traffic or guest invocation survives before lowering; no fused multiply/add
  appears in final LIR. One public Long result allocation remains.
- Python tests pass: 5 model, 10 vector-contract, 41 auditor, 4 primitive inventory;
  no skips after fresh fixture preparation.

The native model and exact contracts/count proof received independent ultra and
xhigh review. No blocking source findings were found. The model review additionally
checked adjacent-representation rounding midpoints and Int conversion boundaries.

## Reproduction

With the pinned GHC/Graal tools selected, use a clean checkout of the recorded
source revision (or this evidence-only descendant):

```sh
sh scripts/prepare-tests.sh
scripts/gradle.sh --offline --no-daemon --max-workers=4 test installDist
# Preserve build/test-results/test before the second run.
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true scripts/gradle.sh --offline --no-daemon --max-workers=4 test installDist --rerun-tasks
bench/experiments/doublex2-foundation/run-runtime.sh build/doublex2-runtime-fresh
```

The offline flag assumes dependency caches are populated. Original host runs used
the shared resource_run.py build-directory lease. Fresh graph output is required;
do not reuse a directory containing previous graphs. Rechecking an existing raw
capture with runtime-audit.py preserves its historical sourceRevision while
requiring identical source/JAR content.

`SHA256SUMS` verifies retained files. Run logs and final LIR are unmodified.
evidence.json omits repeated graph properties and makes copied artifact paths
relative; graph identity/counts, outcome fields and raw BGV/CFG hashes remain.
Its retention record identifies the exact original evidence.json hash. Large raw
graphs, installed JARs, native binaries and full-suite XML/logs remain in the
original worktree build directory and can be regenerated with the commands above.

## Reporting erratum and limits

The unchanged graph auditor's compiledEntryCounter text incorrectly says
SimdFloatVectorTest. The actual instrumented suite is **SimdDoubleVectorTest**,
as demonstrated by both retained XMLs. This minor label typo does not affect any
gate; original generated wording is preserved and the correction recorded here.

No vector ABI expansion, cross-host bitwise arithmetic-NaN identity, throughput,
whole-program zero allocation or spill-free code claim is made. The independent
AArch64 evidence is retained separately; this directory reports x86 execution.
