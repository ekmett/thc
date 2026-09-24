# Int32X4 ByteArray evidence — x86-64, 2026-09-24 UTC

Six managed index/read/write primitives, with 16-byte vector or 4-byte scalar
offset units and sixteen-byte accesses. Immediate mutable-read destructuring
does not enable general vector tuple/function/capture/join transport.

Runtime fix: `62ad4655b5ab56fd203bc18f8d03139e3d89d1ad`.
Final JVM and graph source: `1530261020457e48532d2e249f76eaf28b3ae429`.
Offline graph-reader correction: `72bd83edeee605c1b50d70c5203ec4dd043dde80`.
Base: signed-multiply `19cfc0a130ddfad4fdf5a5a3625655cee2108fe9`.
Host: eak-quartus, Linux x86-64, Intel i9-12900K; GHC 9.14.1 and pinned
Graal/Truffle 25.3.4.1, JDK 25.0.4.1. Exact release and source/JAR/JDK hashes
are in the retained snapshots, not inferred from these abbreviated versions.

## Results

- Fresh native GHC and independent byte model: 9,666 matching rows, 19 committed
  source hashes and seven artifact hashes. Final verification also rehashes the
  actual GHC executable and launcher. Both pre/post exports have fourteen
  accepted roots, seven exact frontier negatives and six unsigned mutations.
- Final focused suite: fourteen tests. Full default and dense-handoff suites:
  **501 each, 103 suites each**, identical case identities, no errors/failures/
  skips. The prior 498×2 and 500×2 checkpoints are separately archived.
- The native corpus alone requires 77,328 compiled row calls and 229,536 exact
  guest entries per handoff mode across pre/post, AST/bytecode and inlining
  on/off. Every row checks active guest/host target identity, installed validity,
  entry deltas and cleared handoff pools. No settling or retry calls.
- The cold-failure regression independently checks 96 compiled-valid → invalid
  bounds → recovery transitions per suite: six operations, both backends, eight
  invalid size/index cases. Fixed forty interpreted warm calls, one explicit
  guest compile and an interpreted host bridge; unchanged bytes/no failed
  publication, target invalidation and recovery without recompilation. A high
  test-only compilation-trigger threshold suppresses automatic compilation;
  compiler graph budgets and production settings are unchanged.
- Normal and optimized Python: auditor65, vector22, memory-proof14, byte-model10,
  byte-array9, coverage4. Final reader: thirty tests in both modes, including
  adversarial provenance, metadata, dataflow, allocation and physical-LIR cases.
- Sixteen accepted selected graphs: pre/post × AST/bytecode × two index roots
  and two store roots. Eight live packed 128-bit reads observe all four signed
  lanes (32 exact extensions); eight packed stores consume all four lane inputs
  (32 exact narrows). Sixteen allocated XMM `VMOVDQU32` load/store instructions.
  Each caller-array access has checked origin and dynamic offset. No private
  vector/carrier/payload allocation, field traffic or compiled exception call.
- Graph probes perform 1,152 checked installed-target invocations. Every call
  gets fresh 64-byte storage; all bytes and store-result reference identity are
  checked. Instrumented suites establish counters separately; graph probes do
  not claim a compiled-entry counter measurement.

## Preserved failures and correction boundaries

`first-capture/` records the original runtime at `6f9960b`. All sixteen guest and
parser commands succeeded, but graph acceptance failed. The first reader error
was a virtual frame-tag byte array. Independent inspection found a genuine
deeper failure in **every** graph: RuntimeFault allocation and a
`Throwable.fillInStackTrace` call on the bounds-error branch. The retained
`first-capture-frontier.json` inventories these nodes and hashes original raw
artifacts. Both later reader versions still reject those graphs specifically
for the compiled call; they are not accepted performance evidence.

Runtime62ad465 adds `transferToInterpreterAndInvalidate` before constructing
that existing bounds exception, matching other managed-array implementations.
The corrected runtime was captured separately, after its full regressions.
All sixteen new guest/parser commands passed and removed the genuine frontier.
Its original checker still rejected four AST store graphs because it expected
only offset/lane Long unboxes. The extra host-slot-zero unbox is the existing
bloom header: constant-mask OR, then only frame primitive-slot-zero deopt
metadata. The offline reader correction proves that exact chain; any use as
address, packed lane, return value, different frame slot or extra scalar fails.

`check.log`/status retain that original reader failure; `recheck.log`/status
retain the passing **offline-only** check. Archived `capture-*.py` readers and
`checkerCorrection` preserve before/after hashes. Seventy-five source records,
eleven installed JARs and four JDK files were frozen; only the two reader/test
sources changed for rechecking. No guest or native execution was repeated.
Exact frame-tag and interpreter-local deopt metadata exceptions do not admit
live calls, materialization, private memory accesses or vector payloads.

## Files and verification

`evidence.json` is the accepted detailed graph/LIR report. Its SHA-256 is
`e7361b663408531dbf5100343e84cd8ed56dc25b8b7f9c173bdefbcfc978bba4`.
Native provenance has uncompressed SHA-256
`626868378f1c05decb0c6e107a9ada85b5541a38f374095c5e1b37fdae8a547b`.

Large provenance, TSV, selected graph and LIR files use deterministic gzip;
recorded original hashes refer to their decompressed bytes. `SHA256SUMS` seals
the actual retained files, including compressed-byte hashes. The seal script
checks decompressed graphs against reports, rehashes original BGV/CFG and failed
capture artifacts, compares archived JUnit XML byte-for-byte, and verifies all
suite counts. The independent result is `package-verification.json`.

```sh
cd bench/experiments/int32x4-bytearray/evidence-x86_64
sha256sum -c SHA256SUMS
```

`validation/` retains commands, logs, XML archives, count/hash reports and
packaging/checking scripts. `native/` contains provenance, audits, native/model
TSVs and first fresh root preparation logs. `python-checks/`, `reader-checks/`
and `reader-correction/` preserve their separate checks. The original raw BGV/CFG
files remain locally under the recorded `build/int32x4-bytearray-runtime-frozen`
and `build/int32x4-bytearray-runtime-bounds-deopt` directories; compact selected
graphs and final LIR views are committed here, not the full compiler dumps.

Earlier fixture-worker preflight had a missing-output-directory setup failure
before native compilation; its failure and corrected command sequence remain
at `/home/ekmett/ai/thc-int32x4-bytearray-fixtures-01a0cdeb/build/int32x4-bytearray-validation/sequence.md`.
The separately retained fresh root preparation passed its first run. None of
those preliminary worker snapshots substitutes for this root evidence.

These graphs cover immutable indexing and stores; compiled semantic tests also
cover local mutable reads. Public Long results may box and interpreter/deopt
fallbacks may allocate. No throughput, spill-free, globally allocation-free ABI,
cross-device determinism, big-endian, AArch64 or generic vector-transport claim.
