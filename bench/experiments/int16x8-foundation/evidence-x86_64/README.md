# Int16X8 x86-64 evidence — 2026-09-23

Captured on eak-quartus (Intel i9-12900K) with GHC9.14.1 and pinned
Oracle Graal25.3.4.1/JDK25. Runtime source is
`1dc0a88db5e19fe4dffb72092b308ade104936b4`; diagnostic reader correction is
`f5863be` (full revision in `evidence.json`).

## Verified results

- 6,032 genuine native/model rows,18 strict positive pre/post audits and2 exact
  unsupported vector-formal controls. Native inputs and source/artifact hashes
  are recorded in `input-provenance.json`; native output is `oracle.tsv`.
- 453 JVM tests in95 suites pass in each of default and dense-handoff modes,
  with zero failures/errors/skips. Full logs, focused six-test XML reports and
  all suite counts/hashes are retained. `installDist` passes in both modes.
- Each mode checks48,256 compiled invocations and52,544 actual guest entries
  for the entire6032-row corpus across pre/post,AST/bytecode,and inlining on/off.
  Every call requires exact1/2 entry delta, unchanged active targets and valid
  last-tier code; no settling/retry/limit changes. Both handoff pools release
  references after every invocation.
- All16 graph captures pass: pre/post × AST/bytecode × plus/minus/times/negate.
  Each capture checks268 native rows twice after compilation (8576 total).
  These graph runs disable counters; the instrumented tests above separately
  prove per-call compiled execution.
- Actual result-connected eight-lane i16 arithmetic and all eight output cuts
  survive to128-bit XMM `VPADDW`, `VPSUBW`, and `VPMULLW`. Negate uses a genuine
  vector NegateNode and XMM subtraction from zero. No surviving vector/carrier
  or primitive-array allocations, lane boxes, field traffic or fallback calls.

The public Long result box remains allowed. Dense carriers and interpreted
ShortVector fallback arrays can allocate outside these inlined compiled graphs.
This is not a throughput, globally allocation-free, no-spill, vector ABI,
cross-platform or machine-code-disassembly claim: physical instructions are
verified in final allocated-register LIR.

## Preserved failures and offline checker correction

The initial synthetic literal test incorrectly applied a zero-argument lambda
through the host bridge and received the closure. The fixture was corrected to
an ordinary unary scalar entry, with no runtime workaround. Its initial failed
XML is retained as `focused-initial.xml`; subsequent focused and full tests pass.

All16 original guest graph runs succeeded exactly once. The initial reader then
rejected two metadata headers for the SAME Graal compilation ID/root. The fixed
reader accepts repeated identical headers but still requires one distinct
compilation identity and one final LIR phase. Mutation controls reject distinct
IDs/roots and duplicated final phases. Eighteen reader tests pass normally and
under Python `-O`.

The corrected reader was run offline with `--recheck-after-checker-fix`; it
returned exit0 and `Int16X8 actual-Core graph gate passed: x86_64, 16 records`.
No guest execution, warmup or compilation was repeated. Original reader files,
failed checker log/command/status=1, frozen runtime source/JAR/JDK hashes, and
both reader revisions/hashes remain retained. Only the diagnostic reader and
its unit-test file may differ for this explicit correction; runtime, probe and
runner sources must match exactly. `checkerCorrection` records this distinction.

## Retention and reproduction

`evidence.json` preserves original absolute capture paths and SHA256 values.
Each corresponding `STAGE-BACKEND-ENTRY/graph.json.gz` decompresses to a
byte-identical copy of the recorded parsed graph; `final-lir.txt`, `run.log`, and command/status files
are also copied unchanged. `SHA256SUMS` covers every retained file except itself.
Raw354MB BGV/CFG capture material remains at
`build/int16x8-runtime-frozen` in the recorded worktree and is hash-addressed in
the evidence, but is deliberately not committed. Full95-suite XML reports
remain under `build/int16x8-validation/{default,handoff}`.

Fresh runs use `scripts/prepare-tests.sh`, `scripts/gradle.sh test installDist`,
the same full test with `JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true` and
`--rerun-tasks`, and `bash bench/experiments/int16x8-foundation/run-runtime.sh`
with a fresh output directory. Use the pinned environment and shared resource
gate around heavy jobs. No main or integration branch was modified.
