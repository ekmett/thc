# Core to bytecode

THC has two interpreters for the same exported GHC Core. Bytecode is the
default; the AST interpreter is also available. The bytecode backend lowers
Core to Truffle's Bytecode DSL, with typed operations and locals. It is not
GHC bytecode or a call into the AST interpreter.

```sh
bin/try.sh
THC_BACKEND=bytecode bin/run.sh build/core/THC.Prim.Test.cbd,build/core/Fixtures.cbd main:Fixtures.sumLoop 100000 --compile
THC_BACKEND=bytecode THC_DIAGNOSTIC_UNSUPPORTED=true bin/try-map.sh
```

`THC_BACKEND=ast` selects the AST interpreter. Java callers can use
`-Dthc.backend=bytecode`; this takes precedence over the environment variable.
The Core request also accepts an explicit `backend` field, so requests for both
backends can coexist in one context. Diagnostics report the selected backend.

## Lowering and shared values

Core arithmetic becomes arithmetic instructions. Lexical bindings become bytecode
locals, cases become branches, and eligible self calls restore arguments and
captures before taking a bytecode backedge. GHC's representation and evaluation
certificates govern the same lazy boundaries as in the AST interpreter.

The heap representation is shared. A captured closure owns a selective
`StaticShape` environment. Constructors use constructor-specific layouts.
Thunks use the same update, failure, and blackhole protocol. Partial application,
overapplication, bounded call-target caches, and mutual tail recursion use the
same application machinery. Bytecode does not change Truffle's `Object[]`
call-target ABI.

The compiler prepares a replayable instruction emitter before creating each
root. The generated interpreter executes those instructions directly; it does
not call the AST interpreter to evaluate Core expressions. Layouts, call arities,
and other metadata used during partial evaluation are constant operands.

Large constructor cases can be split into prepared side roots when bytecode
local-ID capacity is exhausted before publication. A contiguous, nonrecursive
join-only let prefix can move with its case when it has no free outer joins and
the scrutinee does not reference its joins. The scrutinee stays in the caller and
runs once; each side retains the join definitions in their original lexical
order. Ordinary lets remain outside this transformation. Recursive prefixes and
cases needing an outer join activation stay on the existing inline path.
Preparation does not execute guest code or change published bytecode PCs.

Suspending operations preserve their pending operands and caller state through
Truffle continuation frames. Handler selection uses ordinary exception profiling;
a first suspension may deoptimize before resuming correctly. Continuation-local
storage restores the saved frame's actual tags, including frames retained before
a local widened. Preparation does not run guest code or seed exception profiles.

An unobserved compiled conditional consumes its actual Boolean without quickening
or inventing branch observations. Both real arms remain available while its two
counters are zero, including stack guards and retry loops. Real interpreter
execution still quickens and updates the ordinary branch profile; compilation
with learned history retains its probabilities and unseen-arm deoptimization.

The Bytecode DSL is pinned to Truffle 25.3.4.1. See the
[continuation contract](async-continuation-contract.md) when changing lowering or
adding a suspending operation.

## Inspection

A bytecode entry exposes a read-only `bytecode` member containing the actual
instruction streams. To inspect the instruction streams after running the Map workload:

```sh
mkdir -p work build/graph-tools
javac --add-modules=jdk.incubator.vector -cp 'build/install/thc/lib/*' -d build/graph-tools tools/BytecodeDump.java
java --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xss2m \
  -Dthc.diagnosticUnsupported=true -cp 'build/graph-tools:build/install/thc/lib/*' \
  BytecodeDump build/map/modules.txt mapAggregate 10000 work/map-bytecode.txt
```

Graal graph capture uses the same driver for both backends:

```sh
THC_BACKEND=bytecode THC_DIAGNOSTIC_UNSUPPORTED=true \
  bin/dump-map-packets.sh work/graphs/map-bytecode
```

Capture graphs separately from throughput timing. The controlled comparison
harness accepts `--baseline-backend ast --candidate-backend bytecode`, records
the backend in its configuration, and validates each JVM's diagnostics. It can
compare both interpreters from the same immutable distribution against the same
native GHC binary and exported modules.

## Limits

Both backends share admission rules for supported representations and effects,
but their lowering and continuation machinery are distinct. Selecting bytecode
does not make unsupported Core, foreign products or package closures executable.
The Map inspection commands above use diagnostic mode deliberately; executing
one path without a trap does not establish strict support for the full closure.

Async exceptions default to disabled for both bytecode and AST. See the
[asynchronous-exception contract](async-exceptions.md) for explicit selection
and continuation limits. Guest compilation on the optimizing JVM is also
separate from the [Native Image](native-image-feasibility.md) execution model.
