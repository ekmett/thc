# Core to bytecode

THC has two interpreters for the same exported GHC Core. Bytecode is now the
default; the AST interpreter remains available. The bytecode interpreter uses Truffle's
Bytecode DSL, with primitive `long` and `boolean` stack values and locals. On the
initial measured Map workload it takes **16.3% less elapsed time** and allocates **8.1% less**
than the AST backend in the same build.

```sh
scripts/try.sh
THC_BACKEND=bytecode scripts/run.sh sumLoop 100000 --compile
THC_BACKEND=bytecode THC_DIAGNOSTIC_UNSUPPORTED=true scripts/try-map.sh
```

`THC_BACKEND=ast` selects the AST interpreter. Java callers can use
`-Dthc.backend=bytecode`; this takes precedence over the environment variable.
The Core request also accepts an explicit `backend` field, so requests for both
backends can coexist in one context. Diagnostics report the selected backend.

The measurements below describe the initial bytecode implementation at
`0ef73a0`. The [entry-contract and type-preservation report](entry-contracts.md)
records the current work; the [typed execution and tail-cycle follow-up](typed-tail.md)
records the intervening changes.

## What changes

Core arithmetic becomes arithmetic instructions. Lexical bindings become bytecode
locals, cases become branches, and eligible self calls restore arguments and
captures before taking a bytecode backedge. GHC's representation and evaluation
certificates govern the same lazy boundaries as in the AST interpreter.

The heap representation is shared. A captured closure still owns a selective
`StaticShape` environment. Constructors still use constructor-specific layouts.
Thunks use the same update, failure, and blackhole protocol. Partial application,
overapplication, bounded call-target caches, and mutual tail recursion use the
same application machinery. Bytecode does not change Truffle's `Object[]`
call-target ABI.

The compiler prepares a replayable instruction emitter before creating each
root. The generated interpreter executes those instructions directly; it does
not call the AST interpreter to evaluate Core expressions. Layouts, call arities,
and other metadata used during partial evaluation are constant operands.

The implementation takes guidance from Cadenza's bytecode experiment while
retaining THC's existing capture and constructor representation. The Bytecode DSL
is experimental upstream; this implementation is pinned to Truffle 25.3.4.1.

## Inspection

A bytecode entry exposes a read-only `bytecode` member containing the actual
instruction streams. To inspect the instruction streams after running the Map workload:

```sh
mkdir -p work build/graph-tools
javac -cp 'build/install/thc/lib/*' -d build/graph-tools tools/BytecodeDump.java
java --enable-native-access=ALL-UNNAMED -Xss2m \
  -Dthc.diagnosticUnsupported=true -cp 'build/graph-tools:build/install/thc/lib/*' \
  BytecodeDump build/map/modules.txt mapAggregate 10000 work/map-bytecode.txt
```

Graal graph capture uses the same driver for both backends:

```sh
THC_BACKEND=bytecode THC_DIAGNOSTIC_UNSUPPORTED=true \
  scripts/dump-map-packets.sh work/graphs/map-bytecode
```

Capture graphs separately from throughput timing. The controlled comparison
harness accepts `--baseline-backend ast --candidate-backend bytecode`, records
the backend in its configuration, and validates each JVM's diagnostics. It can
compare both interpreters from the same immutable distribution against the same
native GHC binary and exported modules.

## Validation

The implementation passes **54 tests**, including 11 bytecode parity tests. They
cover the native GHC fixture oracle before and after compilation, deep self and
mutual recursion, changing captures, sharing, lazy failures, strict constructor
saturation, partial and overapplication, and calls/captures with more than eight
operands. Explicit backend requests remain isolated within a shared engine. The
test JVM pins its default to AST, so an inherited `THC_BACKEND` cannot silently
change the AST tests; bytecode tests select their backend explicitly.

Both backends also match native GHC on all **18 Map inputs**, up to 100,000,
before and after requested compilation, with **zero unsupported traps**. This
remains a diagnostic-mode workload; cold unsupported GHC paths are still explicit
gaps. [Correctness results and logs](../bench/results/map-bytecode/correctness.json)
record the tested paths.

The Map program produces **72 bytecode roots**. The [actual instruction dump](bytecode-graphs/map-instructions.txt)
contains both warmed instructions and cold roots; the count does not mean that
all 72 roots were compiled. [Source and runtime provenance](bytecode-graphs/provenance.json)
identify commit `0ef73a0` and runtime JAR SHA-256
`aaa573a73d97cb1b198030064deef5966a4ec764e39d6a72f12d7134e293bb39`.

## Compiled graphs

The comparison selects AST and bytecode from that **same frozen distribution**,
using the same exported Map modules and unchanged 100,000-node graph limit.
Each row uses the latest captured compilation of that root. Counts are static
sites across the retained graph, not calls or allocations per workload. The
[complete audit](bytecode-graphs/graph-comparison.json) records exact target
receivers, inlining frontiers, predicates, costs, and thresholds. [All 20 selected
raw BGVs](bytecode-graphs/final-selected-bgv.tar.xz) are retained, with exact
hashes in the [artifact manifest](bytecode-graphs/manifest.json).

| Root | Compilation AST / bytecode | After-mid nodes | Guest calls | Object arrays | Long allocations |
|---|---:|---:|---:|---:|---:|
| Insert | 2584 / 2691 | 10,225 → 10,134 | 21 → 35 | 18 → 29 | 18 → 29 |
| Adjust | 2698 / 2785 | 5,789 → 10,561 | 8 → 16 | 8 → 16 | 8 → 16 |
| Lookup | 2757 / 2848 | 753 → 731 | 0 → 0 | 0 → 0 | 0 → 0 |
| Weighted fold | 2792 / 2884 | 11,963 → 12,019 | 16 → 18 | 16 → 18 | 19 → 19 |
| Range 36 | 2804 / 2928 | 8,598 → 8,497 | 18 → 26 | 15 → 22 | 17 → 22 |
| Balance 10 | 2833 / 2942 | 1,127 → 894 | 0 → 0 | 0 → 0 | 0 → 0 |
| Query | 2820 / 2918 | 2,725 → 1,441 | 0 → 0 | 1 → 1 | 1 → 2 |
| Range 31 | 2824 / 2974 | 12,742 → 15,768 | 19 → 28 | 17 → 27 | 17 → 27 |
| Balance 15 | 2837 / 2984 | 12,964 → 13,132 | 21 → 28 | 18 → 23 | 18 → 23 |
| Entry | 2881 / 3044 | 511 → 443 | 1 → 1 | 1 → 1 | 1 → 1 |

Lookup remains free of residual calls, Object-array allocations, and Long
allocations. Query and the smaller balance root also shrink. All **152 residual
guest-call sites** in the bytecode graphs have constant targets. The three
`Long.longValue` Java calls in the AST fold and the one in range root 31 disappear;
no Java unboxing call remains in the selected bytecode graphs. No opcode-dispatch
call survives, and the remaining integer switches belong to the shared thunk
state machine.

Inspection found and removed two avoidable guard costs. Self-call classification
now caches the call target's relationship to its root; the final graphs retain no
dynamic root/body-identity walks. Object specializations generalize after seeing
an object instead of testing for excluded Long and Boolean values at every use.
The final graphs contain none of those exclusion tests, while numeric
instructions still use primitive frame slots.

The result is mixed. More guest edges inline after the guard removal, which can
expose several residual child calls in a branching callee. Insertion has more
call sites but fewer after-mid nodes; adjustment and the larger range graph grow.
Query still has one escaping tail-call packet and two Long allocation sites.
General lifted values retain thunk and recursive-cell checks, and residual calls
still cross the Object-array ABI. These observations do not establish a
throughput improvement.

## Measurements

The final controlled comparison measures **16.291% less elapsed time** for
bytecode than AST on this Map workload. Bytecode takes **1.896 times** the native
GHC time.

| Backend | Median time per workload |
|---|---:|
| AST | 2.806994 ms |
| Bytecode | 2.349703 ms |
| Native GHC | 1.239464 ms |

Both interpreters come from the same frozen runtime JAR. Three fresh processes
per engine run in rotating order, with at least **12,000 calls and 15 seconds** of
JVM warmup followed by five two-second measurement windows. The native process
warms for one second. Instrumentation is disabled; varying inputs and consumed
checksums match the native oracle. All **45 windows** passed validation, with no
Truffle compilation/deoptimization events during measurement or final
verification and zero unsupported traps. The [summary](../bench/results/map-bytecode/comparison/summary.json),
[validation](../bench/results/map-bytecode/comparison/validation.json), and
[configuration](../bench/results/map-bytecode/comparison/run-config.json) retain
the complete run.

Bytecode's window times range from **2.315 to 2.593 ms**, versus **2.762 to 2.907 ms**
for AST. This supports a gain for this workload on the measured desktop; it is
not a general result for Haskell programs. At that point AST remained the default
while the bytecode backend was experimental.

Current-thread allocation falls from **10,647,717.0 to 9,784,348.5 bytes per Map
workload**, an **8.108%** reduction. All three 256-call samples agree after 12,000
warmup calls and requested compilation. This measures allocation volume on the
executing thread, including host entry work, rather than retained heap. [Samples
and method](../bench/results/map-bytecode/allocation-summary.json) record the
measurement independently of throughput and graph capture.

An [earlier attempted comparison](../bench/results/map-bytecode-guarded/comparison/validation.json)
was rejected because the host entry bridge compiled during a measured window.
Its partial timings are retained as diagnostic evidence and are not used as a
steady-state result.
