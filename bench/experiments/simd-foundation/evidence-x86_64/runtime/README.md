# Production THC SIMD on Linux x86-64

Verified 2026-09-23 on **eak-quartus**, Intel Core i9-12900K (Alder Lake),
Linux x86-64. This is not the Castlemeadow host. The complete CPU capabilities
and kernel are in `cpu.txt` and `kernel.txt`.

Every command used source revision
`acf6d950f4b0f97732def5c4fb436d57c771fc62`, independently checked out on
`codex/simd-x86-validation-01a0cdeb`. No runtime, fixture, harness, audit recognition,
or compiler-limit changes were made for this validation. Later parent changes
are not covered by these results.

Toolchain: pinned GHC 9.14.1 and Oracle GraalVM 25.3.4.1+1.1 / Java 25.0.4.1.
The runtime controls enabled `jdk.incubator.vector`, native access, and compact
object headers. The host's effective defaults were `UseAVX=2` and `UseSSE=4`;
they were observed, not overridden. Full Java release/version records and exact
commands/flags are retained beside this file and in `evidence.json`.

## Results

Fresh preparation regenerated both pre-Tidy and post-Tidy Core and **147 native
GHC rows**: 49 input pairs each for `vectorCase`, `subtractCase`, and `branchCase`.
All native rows matched the independent signed-64-bit wrapping formulas,
including both machine extremes. `native/` retains the new oracle, complete
preparation log, command provenance, fixture/exporter hashes and generated-Core
hashes. No previously generated Core or native oracle was reused.

The unchanged production harness passed all eight controls:

| Export | Backend | Entry | Native rows | Final packed instruction |
| --- | --- | --- | ---: | --- |
| pre-Tidy | AST | vectorCase | 49 | VPADDQ |
| pre-Tidy | AST | subtractCase | 49 | VPSUBQ |
| pre-Tidy | bytecode | vectorCase | 49 | VPADDQ |
| pre-Tidy | bytecode | subtractCase | 49 | VPSUBQ |
| post-Tidy | AST | vectorCase | 49 | VPADDQ |
| post-Tidy | AST | subtractCase | 49 | VPSUBQ |
| post-Tidy | bytecode | vectorCase | 49 | VPADDQ |
| post-Tidy | bytecode | subtractCase | 49 | VPSUBQ |

Each control checks all 49 pairs during warmup, explicitly compiles the exact
selected guest target, then checks all pairs twice more and requires that target
to remain last-tier valid. **This is installed-target validity after execution,
not a per-invocation compiled-entry counter.** Instrumentation is disabled in
these graph controls. Do not reinterpret this evidence as proving every call
entered compiled code; the two claims are different.

All eight audited `Before phase HighTierLowering` graphs contain no NewArray,
NewInstance, CommitAllocation, Invoke/InvokeWithException, LoadField or StoreField
nodes. Final register-allocated LIR contains physical XMM 128-bit packed integer
instructions, for example:

```text
xmm0|V128_QWORD = VPADDQ (x: xmm1|V128_QWORD, y: xmm0|V128_QWORD) size: XMM
xmm0|V128_QWORD = VPSUBQ (x: xmm1|V128_QWORD, y: xmm0|V128_QWORD) size: XMM
```

The public Object-return ABI's scalar `Long` result box remains and is accounted
for separately. This is SIMD arithmetic within scalar THC roots, not a hardware
vector calling convention. VEX-encoded output on this AVX2 host is not a claim
about an SSE2-only CPU. There was no timing campaign.

The focused `SimdVectorTest` suite also passed **3/3 tests**. It preserves the
intentional rejection of the real `branchCase` vector-formal join and checks
forged metadata, vector-versus-tuple identity, actual native/model outcomes and
installed-target validity. It likewise does not assert a per-call entry counter.

## Reproduction

From a clean checkout of the source revision with its pinned dependencies:

```sh
. /home/ekmett/thc-benchmarks/2026-09-23-083914/environment.sh
python3 scripts/prepare-simd-audit.py
scripts/gradle.sh --offline --no-daemon --max-workers=4 installDist
bench/experiments/simd-foundation/run-runtime.sh build/simd-runtime-native
scripts/gradle.sh --offline --no-daemon --max-workers=4 test --tests thc.runtime.SimdVectorTest
```

The environment path is host-local; select the same pinned Java/GHC versions
elsewhere. This validation used separate Gradle caches copied from the verified
joins-validation workspace, but rebuilt all project outputs and generated Core.

`evidence.json` is a compact derivative of the unmodified audit output: it retains
all control outcomes, graph identities/node counts, source and runtime-JAR hashes,
and log/LIR hashes. Verbose graph properties (compiler timing/internal addresses)
and unavailable parsed-graph filenames were omitted; the original evidence hash
is recorded. Runtime source paths are repository-relative; copied output paths
are relative to this directory. `input-provenance.json` is the unchanged complete
input provenance and retains its original absolute paths.

Each control directory contains the complete final `After FinalCodeAnalysisStage`
LIR block and original run log. No full BGV/CFG dumps or binaries are committed.
They remain under `/home/ekmett/ai/thc-simd-x86-validation-01a0cdeb/build/`.
Only trailing whitespace was removed from the two Gradle stdout logs.
`sha256sum -c artifacts.sha256` verifies the retained files from this directory.
