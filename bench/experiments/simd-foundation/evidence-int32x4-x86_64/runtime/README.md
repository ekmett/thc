# Production THC Int32X4 on Linux x86-64

Verified 2026-09-23 on **eak-quartus**, Intel Core i9-12900K (Alder Lake),
at exact source revision `1b75f278756e8352bd5854d75934e4005e6374d2`.
The isolated worktree was `/home/ekmett/ai/thc-int32x4-x86-validation-01a0cdeb`,
branch `codex/int32x4-x86-validation-01a0cdeb`. No runtime, fixture, harness,
audit recognition, compilation-policy or graph-limit changes were made.

Toolchain: pinned GHC 9.14.1 and Oracle GraalVM 25.3.4.1+1.1 / Java 25.0.4.1.
The runtime controls enabled `jdk.incubator.vector`, native access and compact
object headers. Observed host defaults were `UseAVX=2` and `UseSSE=4`; neither
was overridden. Exact flags, CPU capabilities, kernel and JDK release/version
are retained alongside this file. This is not evidence for an SSE2-only host.

## Results

Fresh preparation regenerated pre-Tidy and post-Tidy Core and **243 native GHC
rows**: 81 four-argument cases each for `vectorCase`, `subtractCase` and
`branchCase`. Every row matched the independent signed-32-bit lane-wrapping
model, including 64-bit input extremes and values on both sides of the signed
32-bit boundaries. `native/` contains this run's oracle, preparation log and
source/exporter/generated-Core provenance; no generated Core or oracle was reused.

The unchanged production harness passed all eight controls:

| Export | Backend | Entry | Native rows | Final packed instruction |
| --- | --- | --- | ---: | --- |
| pre-Tidy | AST | vectorCase | 81 | VPADDD |
| pre-Tidy | AST | subtractCase | 81 | VPSUBD |
| pre-Tidy | bytecode | vectorCase | 81 | VPADDD |
| pre-Tidy | bytecode | subtractCase | 81 | VPSUBD |
| post-Tidy | AST | vectorCase | 81 | VPADDD |
| post-Tidy | AST | subtractCase | 81 | VPSUBD |
| post-Tidy | bytecode | vectorCase | 81 | VPADDD |
| post-Tidy | bytecode | subtractCase | 81 | VPSUBD |

Each graph control checks all 81 rows during warmup, explicitly compiles the
selected guest target, then checks all rows twice more while requiring that
target to remain last-tier valid. **These controls disable instrumentation:**
target validity after execution is not a per-invocation compiled-entry proof.

A separate, unchanged `SimdInt32VectorTest` run passed **3/3 tests**, including
**648 mandatory postcompile calls** (2 exports × 2 backends × 2 entries × 81 rows),
each requiring exactly **one** additional `compiledEntries`. This instrumented
test also checks native/model results, vector shape identity, four durable
primitive `int` fields, and the intentional vector-formal join rejection for
`branchCase`. Its log, XML report and source hash are retained separately from
the instrumentation-free graph evidence. This was a focused suite, not a full
repository test run.

All eight audited `Before phase HighTierLowering` graphs contain no NewArray,
NewInstance, CommitAllocation, Invoke/InvokeWithException, LoadField or StoreField
nodes. Complete final register-allocated LIR blocks include physical XMM lanes:

```text
xmm0|V128_DWORD = VPADDD (x: xmm1|V128_DWORD, y: xmm0|V128_DWORD) size: XMM
xmm0|V128_DWORD = VPSUBD (x: xmm1|V128_DWORD, y: xmm0|V128_DWORD) size: XMM
```

The public Object-return ABI's scalar `Long` result box remains and is accounted
for separately. These are local vector operations inside scalar THC roots,
not a vector function-calling convention. No timing campaign was performed.

## Reproduction and retained artifacts

```sh
. /home/ekmett/thc-benchmarks/2026-09-23-083914/environment.sh
python3 scripts/prepare-simd-audit.py --vector int32x4
scripts/gradle.sh --offline --no-daemon --max-workers=4 installDist
bench/experiments/simd-foundation/run-runtime.sh build/int32-native-graphs int32x4
scripts/gradle.sh --offline --no-daemon --max-workers=4 test --tests thc.runtime.SimdInt32VectorTest
```

The environment path is host-local. This run copied verified dependency caches
and vendor sources from the earlier isolated Int64X2 validation, but rebuilt all
project outputs and regenerated all Core/native artifacts at the revision above.

`evidence.json` retains all audit outcomes, graph identities/node counts, runtime
source/JAR hashes, exact commands/options, and the original audit-output hash.
Verbose graph properties and unavailable parsed-graph paths were omitted;
source/JAR paths are repository-relative and retained artifact paths are relative
to this directory. `input-provenance.json` preserves the unchanged original
absolute paths. The two Gradle stdout logs only had trailing whitespace removed.

Every control retains its original run log and complete final LIR block. Full
BGV/CFG/parsed dumps remain under the isolated worktree's ignored
`build/int32-native-graphs/` (approximately 1.6 GB); none are committed.
Run `sha256sum -c artifacts.sha256` here to verify all retained artifacts.
