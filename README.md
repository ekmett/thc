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
selective closure captures, partial applications, overapplication and a tail-call
trampoline. Haskell adds laziness, sharing, thunk updates and blackholes. Data
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
and after requested compilation. On the current macOS ARM64 measurements, a
workload over roughly 10,000 input items takes **3.10 ms under THC versus 1.33 ms under
GHC**, or **2.34 times the cost**, after warmup. No unsupported trap was entered.

That is one workload, with three fresh processes and five measured windows per
process. There is plenty left to do. See the [Map report](docs/map-example.md)
for the method, raw results, actual Graal graphs, and remaining costs.

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
