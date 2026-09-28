# Core to bytecode

THC has two interpreters for the same exported GHC Core. Bytecode is the
default; the AST interpreter is also available. The bytecode backend lowers
Core to Truffle's Bytecode DSL, with typed operations and locals. It is not
GHC bytecode or a call into the AST interpreter.

```sh
bin/try.sh
THC_BACKEND=bytecode bin/run.sh sumLoop 100000 --compile
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

Async- or delimited-enabled roots compile handler selection without adaptive
exception-history guards; resolving a first suspension does not mark an
exception profile as observed. Blocking-request handlers use declared Object
scratch carriers and stateless loads/stores, including the saved root mask.
This is compiler metadata, not execution of a preparatory suspension. Ordinary
roots retain their exception profiling, and ordinary local writes retain
adaptive widening. The pinned generated-code normalization checks version and
source shape before applying this policy.

The implementation takes guidance from Cadenza's bytecode experiment while
retaining THC's existing capture and constructor representation. The Bytecode DSL
is experimental upstream; this implementation is pinned to Truffle 25.3.4.1.

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

## Checks and limitations

`BytecodeBackendTest` exercises the native-GHC corpus, sharing, lazy failures,
strict constructors, partial and excess application, recursion and captured
values. Tail-cycle and representation tests exercise their own explicit
backend selections. The Gradle test JVM pins its default backend to AST;
bytecode checks select their backend explicitly, so an inherited
`THC_BACKEND` does not change the intended test path.

Both backends share admission rules for supported representations and effects,
but their lowering and continuation machinery are distinct. Selecting bytecode
does not make unsupported Core, foreign products or package closures executable.
The Map inspection commands above use diagnostic mode deliberately; executing
one path without a trap does not establish strict support for the full closure.

Async exceptions default to enabled for bytecode and disabled for AST. See the
[asynchronous-exception contract](async-exceptions.md) for explicit selection
and continuation limits. Guest compilation on the optimizing JVM is also
separate from the [Native Image](native-image-feasibility.md) execution model.
