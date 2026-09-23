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

## Where things stand

The Map example agrees with native GHC on inputs up to 100,000 operations, before
and after requested compilation. A controlled macOS ARM64 comparison reduced
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

The [latest local comparison](bench/results/constructor-class/powered-default/)
records the frozen runtime and fork variation. A separate
[hosted comparison](bench/results/hosted-2026-09-23/) found a similar broad
improvement, but was too noisy to settle the smaller storage experiments.

The [typed execution and tail-cycle report](docs/typed-tail.md),
[source-location report](docs/debug-locations.md),
[initial bytecode report](docs/bytecode.md), [original Map report](docs/map-example.md),
[call-packet follow-up](docs/call-packets.md), and [inlining report](docs/map-inlining.md)
record the preceding experiments. There is plenty left to do.

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
