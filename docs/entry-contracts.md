# Keeping Core's calling-convention evidence

The first useful result was also a warning about counters. Enforcing GHC's
worker entry contracts removed almost 97% of the evaluated thunks in the Map
workload. The AST timing improved by about 1%. Those thunks were much cheaper
after inlining than their count suggested.

Keeping the resulting type information through constructor fields, closure
captures and local variables made a larger difference. A controlled bytecode
comparison reduced the workload from **2.337 ms to 1.542 ms**, against **1.253 ms**
for native GHC. That is **34.0% less elapsed time**, or **1.231 times GHC's cost**.
These measurements describe the frozen `precise-fields-v3` candidate, before the
subsequent frame and storage-check experiments.

A later [powered local comparison](../bench/results/constructor-class/powered-default/README.md)
tests the complete default runtime through `fab44ea`: **2.360546 ms to
1.523473 ms**, against **1.279898 ms** for GHC. That is **35.46% less elapsed
time**, or **1.1903 times GHC's cost**. Candidate fork medians range from 1.466
to 1.630 ms; the result does not resolve small individual improvements.

## What changed

GHC selects call-by-value entry marks for eligible workers and joins during
Tidy, just after THC's ordinary Core export point. The exporter now invokes
that same pinned selection logic and exports the proposed entry convention
separately from already-evaluated value facts. THC enforces it at saturation,
including supplied PAP arguments, overapplication and tail reentry. Merely
creating a PAP remains lazy. Known saturated calls can avoid constructing
argument thunks whose only purpose would have been immediate evaluation.

The lowering also distinguishes a variable's type from its physical storage.
Most variables cannot contain a recursive cell. Captures taken while a recursive
group initializes still retain the cells by identity; values published after
the whole group initializes can use direct reads. This removes cell tests
without breaking recursive knots or thunk sharing.

Constructor metadata now retains the actual worker field types. A strict
`Map` child can be stored in a final `DataValue` property. A lazy field still
needs `Object`, and a strict polymorphic field stays generic. Evaluated captures
use the same rule. Precise reference and primitive types are restored when
arguments cross a root, self-call or join boundary.

The AST now handles a direct self call with parallel local moves and an internal
control transfer, preserving all supplied PAP arguments and changed captures.
Longer tail cycles still unwind to their matching active root through the
existing bloom-guided machinery. A small node-local cache reuses boxed bloom
masks without dropping any bits; a polymorphic site falls back to boxing.
Completed AST self and join transfers also release their operand temporaries.

The detailed proof boundaries, metadata format and fixture coverage are in
[Core evidence and local joins](core-evidence.md). None of these changes replaces
the remaining inter-root `Object[]` calling convention or unboxes arbitrary
Haskell heap objects.

## Controlled intermediate measurements

Both comparisons use the actual `containers-0.8` workload and the original
runtime at `4c116a8493eafad4077a1c5eb9c046bff56fe3c4` as their baseline. Each
comparison runs its baseline and candidate against the same frozen exported
modules and native oracle. The added metadata is ignored by the old runtime.

| Candidate | Backend | Baseline | Candidate | Native GHC | Candidate / GHC |
| --- | --- | ---: | ---: | ---: | ---: |
| Entry contracts and cell facts | AST | 2.494497 ms | 2.472417 ms | 1.273746 ms | 1.941 |
| Precise heap references and call paths | Bytecode | 2.336565 ms | 1.541947 ms | 1.252852 ms | 1.231 |

The rows are separate controlled comparisons, not an ablation attributing the
second result to one change. Their [raw results](../bench/results/entry-contracts/ast/summary.json)
and [bytecode results](../bench/results/precise-fields/bytecode/summary.json) include
per-window timings, commands, source and runtime hashes, and validation.

Each comparison has three fresh processes per engine, five measurement windows
per process, and at least 12,000 workloads and 15 seconds of JVM warmup. Engines
run serially in rotated order. Every workload consumes an input-dependent result
checked against native GHC. Source notes are enabled, instrumentation is off,
and graph/JFR collection runs separately. All 45 windows in each comparison
passed the installed-code, checksum and compilation-phase checks. No unsupported
trap was entered.

These are steady-state measurements of one workload on macOS ARM64 with GHC
9.14.1 and GraalVM 25.3.4.1. They include host entry and garbage collection. No
measured Truffle compilation event does not prove the absence of host JIT work
or unrelated machine activity. Map's cold unsupported GHC paths remain explicit
diagnostic traps.

## What the compiled code still pays for

The default bytecode graph after typed frame restoration contains no residual
recursive-cell tests in the eight common hot roots. Compared with the same
baseline backend and corpus, thunk tests fall from 90 to 12. The weighted-fold
graph falls from 11,955 to 996 nodes after mid-tier lowering, and its residual
guest-call sites fall from 18 to one. The [graph and allocation evidence](../bench/results/entry-contracts/graphs/README.md)
records the matched roots, concrete call-packet edges and capture provenance.

There are still 172 residual guest-call sites across those roots. All targets
are constant and all have argument-array packets. Their count is higher than
the baseline's 152 because inlining a branching callee can expose several child
calls. Static site counts are not calls or allocations per workload.

Truffle already profiles the arguments and returns of these direct targets.
All 172 residual returns acquire exact non-null class stamps: 169 generated
constructor carriers and three JVM `Long` results. The missing optimization is
not a switch that enables return-type profiling. The remaining generic ABI
still materializes argument arrays and primitive boxes when a call does not
inline.

The [call-boundary audit](call-boundaries.md) follows that ABI through the pinned
Truffle runtime and Cadenza. It also records why mutable packet reuse needs a
frame-lifetime contract, and a smaller partial-inlining opportunity in the fold.

Separate allocation runs measured **9,575,492.5 to 8,329,466.5 bytes per workload**
for the original and typed-frame bytecode runtimes, a **13.0% reduction**. Three
warmed 256-workload samples agree in each run. The measurement uses the executing
JVM thread's allocation counter, includes host entry, and does not measure
retained heap. GHC's process-wide allocation slope is **7,074,185 bytes per
workload**, repeated three times; that scope differs from the JVM counter.

In a separate JFR recording, estimated allocation weight is 73.3% `Bin` cells,
11.8% argument arrays, 10.3% JVM `Long`, 4.3% Haskell `I#`, and less than 0.4%
thunks and captures together. The generated classes were identified through the
actual constructor and capture factories. These sampled shares are not exact
per-class allocation counters. There are no remaining bloom-origin Long
allocation sites in the selected compiled graphs; most remaining primitive
boxes are arguments to residual calls.

A separate [shallow-size probe](../bench/results/constructor-class/object-sizes/README.md)
uses the actual constructor and capture factories and JVM instrumentation.
With compressed references and 8-byte alignment, `Bin` occupies 40 bytes,
`I#` and an uncached JVM `Long` each occupy 24, and the primitive-plus-two-reference
capture occupies 32. A three- or four-slot `Object[]` packet occupies 32 bytes;
five slots occupy 40. Referenced objects are additional. These are object sizes,
not dynamic allocation counts, and the probe does not execute the Map workload.

Larger inlining budgets help some configurations and hurt others. In a
single-process screen, the typed-frame bytecode candidate took 1.535 ms with
the default budgets and 1.410 ms with both budgets raised from 12,000 to 64,000.
The latest compiled guest-code sizes were 296 KB and 890 KB, respectively.
That screen selects an experiment for a controlled repeat; it does not establish
an accepted speedup or justify changing a general runtime default.

## Storage experiments

The subsequent runtime adds two independent, opt-in experiments:
`thc.staticShapeUnchecked` removes redundant Truffle storage checks after
authenticating each layout's allocations; `thc.constructorClassIdentity` lets
constructor matching use an exact Java class while invalidatable ownership
assumptions hold. Both default to false. Their proof boundaries are described
in [owned static storage](core-evidence.md#owned-static-storage).

All 148 tests pass with both options off and with both on. Another 36 semantic
tests pass with both enabled under array-based storage, where constructors can
share a carrier class. Seven Map configurations also pass the full 18-input
native oracle before and after compilation. The [validation record](../bench/results/constructor-class/validation.json)
includes the frozen runtime hash and the compiled constructor audit.

Neither storage experiment has demonstrated a throughput win. An initial storage
comparison suffered a roughly twofold slowdown in both native and JVM processes
as the host battery reached 4%. A short native probe later returned to normal,
but the next sustained JVM screen slowed again and was stopped. The
[rejected comparison](../bench/results/owned-storage/ast-checks/PERFORMANCE-NOT-ACCEPTED.md)
retains its raw timings. Compiler and checksum checks alone do not detect this
kind of host interference; future comparisons also record power status around
each process.

The manual [hosted performance workflow](../.github/workflows/performance.yml)
rebuilds the original and current runtimes, freezes one shared Core/native
corpus, verifies the archived graphs, and runs the comparisons serially. It
also tests compact JVM object headers before considering a timing comparison.
Hosted results belong to that runner; their absolute times cannot be combined
with the local M3 measurements above.

The [completed hosted run](../bench/results/hosted-2026-09-23/README.md) passes
all guards for 180 measured windows and reproduces all 18 archived graph phases. It also
shows substantial fork variability. Constructor class matching and unchecked
storage were slightly slower by the aggregate statistic; compact headers were
slightly faster. Those small differences do not establish a winner, and neither
storage option is enabled by default.

The compact-header size probe establishes a narrower structural result: JVM
`Long` shrinks from 24 to 16 bytes, and three-slot packets from 32 to 24. `Bin`
and `I#` remain 40 and 24 bytes. Their per-instance layout field and alignment
consume the header space saved by the VM. Moving the owner information to the
carrier class needs permanent class ownership and a layout-carrying fallback;
simply deleting the field would break shared-carrier storage.
