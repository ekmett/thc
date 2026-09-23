# FloatX4 production compilation evidence

This probe executes real pre/post-Tidy `SimdFloatX4` Core through both THC
backends, using genuine native GHC oracle rows. It captures the three finite
arithmetic roots separately, with all four lanes consumed and dynamic inputs.
It is not a throughput benchmark or a vector calling-convention test.

From a prepared checkout and pinned JDK, after `installDist`:

```sh
bash bench/experiments/floatx4-foundation/run-runtime.sh build/floatx4-runtime
```

The checker requires fresh native/input/runtime hashes before and after capture,
one BGV per target, matching graph/CFG target names, packed ADD/SUB/MUL in final
register-assigned LIR, and no surviving carrier/array allocations, field traffic,
vector payload loads, residual calls, floating boxes or fused arithmetic.
The public scalar Object ABI still unboxes four Long inputs and boxes one Long
result; those operations are checked explicitly, not called eliminated.

The probe disables guest instrumentation. Post-execution installed-target
validity is not proof of per-call compiled guest entry; that is independently
required by `SimdFloatVectorTest` for all 2,196 inputs at both stages/backends,
in default and handoff runs. Exceptional/rounding classifications and the
separate-rounding witness belong to those tests, not these three finite graphs.

The [x86-64 evidence](evidence-x86_64/evidence.json) records runtime source
`41058d184c77596b9bfaf0f793924287f84a8f15`, Oracle GraalVM 25.3.4.1, and all
12 successful captures on eak-quartus (Intel i9-12900K, AVX2). Final code uses
physical XMM `VADDPS`, `VSUBPS`, and `VMULPS`. It does not establish SSE-only
or AArch64 lowering, and makes no cross-device throughput claim.

Default and dense-handoff full suites each passed 355 tests across 72 suites,
with zero failures, errors or skips. Each configuration separately requires
8,784 positive per-input compiled-entry deltas for this FloatX4 corpus. Fresh
native/model preparation agrees on all 2,196 rows, all seven positive entries
pass strict auditing, and the vector-formal frontier remains rejected. Eight
vector-auditor, seven model and 41 general-auditor tests also pass.

The independent export-only check ran on Linux using the same committed source
tree: pre-Tidy/model-only, all positive strict audits pass, no native oracle
claim, all 19 source/artifact hashes verified. This verifies the preparation
mode, not execution on macOS or AArch64.

Compact retained artifacts include normalized final LIR, logs, source/input
provenance, native rows and the focused JVM XML from both full runs. Raw BGV/CFG
dumps remain at the recorded local paths with exact hashes. To package a fresh
validated capture, use `tools/package-floatx4-evidence.py CAPTURE DEST RUNTIME_SHA`;
it refuses an existing destination and rechecks the graph gate first.
