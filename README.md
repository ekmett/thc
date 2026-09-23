# thc

[![Build](https://github.com/ekmett/thc/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/ekmett/thc/actions/workflows/build.yml)

Haskell on Truffle/Graal.

I'm experimenting with using GHC as a frontend for a high performance Haskell
implementation on the JVM. GHC does the parsing, type checking, desugaring and
optimization. THC takes the resulting Core and gives Graal something it can
specialize.

GHC already knows quite a lot about compiling Haskell. The intention is to keep
that information around long enough to use it.

The runtime follows [Cadenza](https://github.com/ekmett/cadenza): indexed frames,
selective closure captures, partial applications, overapplication and bloom-guided
tail calls that close cycles at the matching active root. A trampoline handles
the remaining transfers. Haskell adds laziness, sharing, thunk updates and blackholes. Data
constructors have their own layouts, with primitive fields where GHC's
representation permits them.

The older runtime experiments live on the [legacy branch](https://github.com/ekmett/thc/tree/legacy).

## Running

You need **GHC 9.14.1**, **GraalVM 25.3.4.1 / JDK 25**, and Python 3.12+. The build
pins Gradle 9.7.1 and Kotlin 2.4.20. Initial builds download their
pinned dependencies and the small amount of upstream Haskell source needed by
the tests.

Put GHC on your `PATH` and point `JAVA_HOME` at the GraalVM JDK:

```sh
export JAVA_HOME=/path/to/graalvm
export PATH="$JAVA_HOME/bin:$PATH"

scripts/try.sh
scripts/run.sh sumLoop 100000 --compile
```

On macOS, `JAVA_HOME` is the `Contents/Home` directory inside the GraalVM bundle.
The `--compile` flag requests guest compilation and checks that code was
installed. Compilation failures are errors.

Ordinary app, test and probe launches use compact object headers. Constructor
layouts belong to their generated storage classes where that class has a unique
owner; shared storage classes keep a layout field. `-Dthc.classOwnedLayouts=false`
selects the field-bearing representation for comparison. Gradle's
`-Pthc.compactObjectHeaders=false` and the controlled benchmark's explicit
`-XX:-UseCompactObjectHeaders` option provide header-off controls. The remaining
storage and call-path experiments are opt-in.

The [bytecode backend](docs/bytecode.md) is the default. The AST interpreter is
also available:

```sh
THC_BACKEND=ast scripts/run.sh sumLoop 100000 --compile
```

For an ordinary `containers` example:

```sh
THC_DIAGNOSTIC_UNSUPPORTED=true scripts/try-map.sh
```

This builds a histogram using `Data.Map.Strict`, updates it, performs lookups,
and folds the result. The Map implementation is the actual Haskell source from
`containers-0.8`. The same workload is compiled by native GHC for comparison.

The diagnostic flag matters: the cold exception path still reaches unsupported
parts of GHC's runtime. Diagnostic mode leaves explicit traps at those gaps.
Normal mode rejects them when loading the program. Successful runs establish
support for the paths exercised; they don't make the gaps disappear.

Development uses [pull requests and a tested merge queue](docs/contributing.md).

## Coverage

The next step is breadth. A [native-GHC corpus](docs/coverage.md) exercises lazy
lists and streams, sharing, captured functions, partial and overapplication,
recursive trees, non-strict pointer identity, and numeric representation
boundaries. Both backends check
warm inputs, cold paths and recompiled code. The corpus also checks that the
intended structures survive GHC optimization and that shared producers are
evaluated once.

`examples/coverage.json` describes the inputs. `scripts/try.sh` prepares and runs
the suite; missing dependencies and unsupported constructs remain explicit.

[Library coverage](docs/library-coverage.md) adds ordinary `Data.IntMap.Strict`
and `Data.IntSet` operations, four public `Data.Sequence` workloads, bitmap
primitives and unsigned word boundaries,
checked against native GHC and independent models. `scripts/try-libraries.sh`
runs those checks on both backends.
The [unsigned scalar primop slice](docs/integer-primops.md) adds native-checked
machine-word division and narrow-word comparisons, bitwise operations and shifts.
Local [Int64X2 and Int32X4 SIMD operations](docs/simd.md) run on both backends with exact
vector metadata and primitive lane storage. Vector calls, returns, captures,
fields and joins remain explicit boundaries.
The [FloatX4 foundation](docs/floatx4.md) adds six local floating vector primops
with primitive Float lanes, a fixed-width FloatVector carrier and native IEEE-edge checks.
The [DoubleX2 foundation](docs/doublex2.md) adds the corresponding six binary64
operations with exact typed lanes and bit-sensitive correctness gates.
The [Int16X8](docs/int16x8.md) and [Int8X16](docs/int8x16.md) foundations
each add seven local narrow-integer vector operations, including wrapping
multiplication, with primitive lane carriers and native/model edge checks.
The [Word8X16 foundation](docs/word8x16.md) adds six unsigned counterparts with
distinct proofs, zero-extended lanes and native/model high-bit checks.
The [Word16X8 foundation](docs/word16x8.md) provides the six unsigned 16-bit
counterparts with eight primitive short fields and independently observed lanes.
The [Word32X4 foundation](docs/word32x4.md) adds six unsigned 32-bit operations,
four primitive int fields and explicit zero-extension to Long scalar lanes.
The [signed Int32X4 multiplication slice](docs/int32x4-multiply.md) completes
the seven-operation signed family with wrapping low-32-bit products and signed
lane observations, without widening vector calling conventions.

Exact [unboxed tuple results](docs/tuple-results.md) execute on both backends with
scalar/reference inputs, concrete Float/Double leaves, local join results and zero-width State# components;
[Exact empty tuple inputs](docs/empty-tuple-inputs.md) retain logical arity with no payload fields.
Other aggregate arguments, join captures, ordinary captures and sums remain explicit boundaries.

[Managed MutVar operations](docs/mutvars.md) execute ordinary ST/STRef code with
lazy reference storage and exact State sequencing on both backends.

[Managed ByteArray operations](docs/bytearrays.md) execute genuine ShortByteString
pack/length/unpack/uncons with ordered writes and contained copies and native/model checks on both backends.
[Int-array operations](docs/int-arrays.md) extend the same byte storage to
public `UArray`/`STUArray` examples with native-endian, full-width values.
[Double-array operations](docs/double-arrays.md) add typed floating storage,
public Double arrays and native-checked bit movement through tuple-returning reads.
[Int32/Word32-array operations](docs/int32-arrays.md) add four-byte signed and
unsigned elements, public accumulation/ST examples and cross-element byte aliases.
[Float/Word-array operations](docs/float-word-arrays.md) add typed four-byte Float
and eight-byte machine Word storage, public examples and native bit-movement checks.
[Int16/Word16-array operations](docs/int16-arrays.md) add two-byte signed/unsigned
storage and exact narrow literal proofs.

Public fixed-bounds `STArray` programs use [boxed array storage](docs/core-evidence.md#lifted-boxed-array-storage)
while preserving lazy lifted elements and closures.

[Scalar floating primitives](docs/floating-primitives.md) add concrete Float and
Double storage and 30 arithmetic/comparison/conversion primops, including square
roots, checked against native GHC. Floating tuple results also execute genuine
`Data.Complex` workers; residual scalar floating inputs still use the existing
Object call ABI.

The `Data.Set` example remains a separate frontier until its full exported call
graph passes; native results alone are not counted as THC execution passes.

## Where things stand

The Map example agrees with native GHC on inputs up to 100,000 operations, before
and after requested compilation. An earlier controlled macOS ARM64 comparison reduced
bytecode's time from **2.36 ms to 1.52 ms**, against **1.28 ms for native GHC**:
**35% less elapsed time**, or **1.19 times GHC's cost** on this workload.
No unsupported trap was entered.

Core exports retain [representation evidence, worker entry contracts and local
joins](docs/core-evidence.md), along with [source locations](docs/debug-locations.md)
in both executable trees. The runtime keeps primitive and evaluated reference
types through arguments, captures and constructor fields. PAPs stay lazy until
saturation, and recursive captures retain their cells until publication.

Each comparison uses three fresh processes per engine, five measured windows
per process, and at least 12,000 warmup workloads. The [entry-contract and
type-preservation report](docs/entry-contracts.md) records the changes,
measurements and remaining costs. These are results for this Map workload,
not a claim about arbitrary Haskell programs.

The [local entry-contract comparison](bench/results/constructor-class/powered-default/)
records the frozen runtime and fork variation. A separate
[hosted comparison](bench/results/hosted-2026-09-23/) found a similar broad
improvement, but was too noisy to settle the smaller storage experiments.
The [Linux comparison](bench/results/castlemeadow-2026-09-23/) on an i9-12900K
measured **1.60 ms against GHC’s 1.34 ms**, again about **1.19 times GHC’s cost**,
with class-owned layouts and compact headers. The other experimental storage
switches did not improve on that configuration in the matched comparison.
Those runs used at least 45 seconds and 30,000 calls of JVM warmup, with three
fresh processes per configuration and every timing window checked.

Constructor layouts now belong to their generated classes. With compact headers,
a Map `Bin` is 32 bytes and an `I#` is 16 bytes. The
[class-owned layout experiment](bench/results/class-owned-layouts/) measured
16.4% less allocation and confirms that the compiled loops no longer load
per-object layout pointers. The remaining call packets are a separate cost.

The [typed execution and tail-cycle report](docs/typed-tail.md),
[source-location report](docs/debug-locations.md),
[initial bytecode report](docs/bytecode.md), [original Map report](docs/map-example.md),
[call-packet follow-up](docs/call-packets.md), [inlining report](docs/map-inlining.md),
and [demand probe](docs/demand-probe.md) record the preceding experiments.
There is plenty left to do.

The first attempt at compiling Map was particularly useful: generic frame reads
and string comparisons in case dispatch blew up partial evaluation. Fixing those
let the insertion worker compile within the original graph budget.

The current implementation handles closures, recursive bindings, lazy and strict
constructor fields, primitive integer operations, and a growing set of Core
forms. General `Main`/IO, the full boot-library closure, FFI, and stack-safe
non-tail evaluation are still work in progress. The host entry interface is
currently integer-only.

## Benchmarks

```sh
scripts/benchmark.sh
THC_DIAGNOSTIC_UNSUPPORTED=true scripts/benchmark-map.sh
THC_BACKEND=ast THC_DIAGNOSTIC_UNSUPPORTED=true scripts/benchmark-map.sh work/bench-ast
```

Run the corresponding `try` script first. Benchmarks use changing inputs,
consume their results, warm the JVM, and compare against native GHC. Graph
capture is a separate run.

Earlier [kernel measurements](docs/prototype-results.md) and
[compiled graphs](docs/graph-inspection.md) are also retained. They are useful
for understanding individual mechanisms; the Map example is the next step.

## Finding your way around

* [`compiler/`](compiler/README.md) exports executable Core from GHC, with
  representation and evaluation information.
* [`src/`](src/) contains the Truffle runtime and its tests.
* [`examples/`](examples/) contains the Haskell programs and native oracle.
* [`scripts/`](scripts/) contains the build, audit, benchmark and graph drivers.
* [`docs/`](docs/) and [`research/`](research/) record the implementation and
  the earlier design investigation.

## License

THC uses the same license as Cadenza: **UPL-1.0 AND BSD-3-Clause**.
See [LICENSE.txt](LICENSE.txt) and [NOTICE.md](NOTICE.md) for the terms and
attribution notices.

Unless you explicitly state otherwise, contributions submitted for inclusion
are provided under these same terms.

## Contact Information

Contributions and bug reports are welcome!

Please feel free to contact me through GitHub.

-Edward Kmett
