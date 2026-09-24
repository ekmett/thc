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
selective captures, partial applications and tail calls. Haskell adds laziness,
sharing, thunk updates and blackholes. Constructors have their own layouts, with
primitive fields where GHC's representation permits them.

## What works

Both the bytecode and AST backends run lazy Core with closures, recursive
bindings, typed constructor fields, local joins, and unboxed tuple inputs and
results. There is also bounded support for unboxed sum results, scalar arithmetic,
local SIMD operations, and managed arrays and mutable references.

The tests include ordinary list, `STRef`, array, `ShortByteString`, `IntMap`,
`IntSet` and `Sequence` programs. They compare native GHC results with both
interpreters and compiled guest code. The [coverage guide](docs/README.md) links
the individual contracts, native checks and remaining gaps; the generated
[primop checklist](docs/primops.md) tracks what is implemented, partial or missing.

This is still an experiment, not a replacement for GHC. General `Main`/IO, the
complete boot-library closure, FFI, and stack-safe non-tail evaluation remain
unfinished. The host entry interface is currently integer-only. Vector calling
conventions and several aggregate storage forms are deliberately unsupported.

In particular, Map and Set still have cold runtime paths that strict loading
rejects. Diagnostic mode leaves explicit traps at those gaps. A successful
workload in that mode does not establish support for its whole call graph.

## Running

You need **GHC 9.14.1**, **GraalVM 25.3.4.1 / JDK 25**, and Python 3.12+. The build
pins Gradle 9.7.1 and Kotlin 2.4.20. Initial builds download their dependencies
and the upstream Haskell source used by the tests.

Put GHC on your `PATH` and point `JAVA_HOME` at the GraalVM JDK:

```sh
export JAVA_HOME=/path/to/graalvm
export PATH="$JAVA_HOME/bin:$PATH"

scripts/try.sh
scripts/run.sh sumLoop 100000 --compile
```

On macOS, use the bundle's `Contents/Home` directory. `--compile` requests guest
compilation and checks that code was installed; compilation failures are errors.
The [bytecode backend](docs/bytecode.md) is the default. To use the AST backend:

```sh
THC_BACKEND=ast scripts/run.sh sumLoop 100000 --compile
```

For the native-checked library suite and the diagnostic Map example:

```sh
scripts/try-libraries.sh
THC_DIAGNOSTIC_UNSUPPORTED=true scripts/try-map.sh
```

The latter builds, updates, queries and folds a histogram using the actual
`Data.Map.Strict` implementation from `containers-0.8`.

## Performance

The recorded Map comparisons are encouraging: about **1.19 times native GHC's
elapsed time** on both an ARM64 Mac and an x86-64 Linux machine. These are
warmed-up results for one workload, not a claim about arbitrary Haskell.
The [entry-contract report](docs/entry-contracts.md) describes the measurements;
[retained runs and graph reports](docs/README.md#performance-and-runtime-design)
include the inputs, variation and remaining costs.

Constructor layouts belong to their generated storage classes where possible.
With compact headers, a Map `Bin` is 32 bytes and an `I#` is 16 bytes. The
[class-owned layout experiment](bench/results/class-owned-layouts/) records the
allocation comparison. Compact headers are the default; the reports document
controls for that and the opt-in storage experiments.

```sh
scripts/benchmark.sh
THC_DIAGNOSTIC_UNSUPPORTED=true scripts/benchmark-map.sh
THC_BACKEND=ast THC_DIAGNOSTIC_UNSUPPORTED=true scripts/benchmark-map.sh work/bench-ast
```

Run the corresponding `try` script first. Benchmarks vary their inputs, consume
the results, warm the JVM and compare against native GHC. Graph capture is a
separate run.

## Finding your way around

* [`compiler/`](compiler/README.md) exports executable Core from GHC, with
  representation and evaluation information.
* [`src/`](src/) contains the Truffle runtime and its tests.
* [`examples/`](examples/) contains Haskell programs and the native oracle.
* [`scripts/`](scripts/) contains build, audit, benchmark and graph drivers.
* [The documentation index](docs/README.md) groups coverage and design reports;
  [`research/`](research/) contains the earlier design investigation.
* [Development](docs/contributing.md) covers local checks and the tested merge
  queue. Update the [primop checklist](docs/primops.md#updating-the-list) when
  adding a primitive.

The older runtime experiments live on the
[legacy branch](https://github.com/ekmett/thc/tree/legacy).

## License

THC uses the same license as Cadenza: **UPL-1.0 AND BSD-3-Clause**.
See [LICENSE.txt](LICENSE.txt) and [NOTICE.md](NOTICE.md) for the terms and
attribution notices.

Unless you explicitly state otherwise, contributions submitted for inclusion
are provided under these same terms.

## Contact Information

Contributions and bug reports are welcome! Please feel free to contact me through
GitHub.

-Edward Kmett
