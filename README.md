# thc

[![Build](https://github.com/ekmett/thc/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/ekmett/thc/actions/workflows/build.yml)

Haskell on Truffle/Graal.

I'm experimenting with using GHC as a frontend for a high performance Haskell
implementation on the JVM. GHC does the parsing, type checking, desugaring and
optimization. THC takes the resulting Core and gives Graal something it can
specialize.

GHC already knows quite a lot about compiling Haskell. The intention is to keep
that information around long enough to use it.

## Build and run

For native Windows, use the [PowerShell build and test guide](docs/windows.md).
It records the pinned tools, tested runtime/exporter slice, and remaining platform limits.

You need **GHC 9.14.1** (including `ghc-pkg` and `runghc`), **cabal-install 3.16**,
**GraalVM 25.3.4.1 / JDK 25**, and Python 3.12+. Put GHC on your `PATH` and
point `JAVA_HOME` at GraalVM. On macOS, use the bundle's `Contents/Home`
directory. The Gradle wrapper downloads its dependencies on the first build.
Linux x86_64 builds also require clang and the native GMP development headers
and library (for example, `libgmp-dev` on Debian/Ubuntu) for the
[checked limb provider](docs/gmp-limb-provider.md).

From the repository root:

```sh
export JAVA_HOME=/path/to/graalvm
export PATH="$JAVA_HOME/bin:$PATH"

make
```

This runs `./gradlew installDist` for the JVM runtime and `cabal build`
for the Haskell library and driver. The `thc` library contains the GHC Core
plugin; Cabal builds it alongside the `thc` executable. Both builds are
incremental. `make runtime` and `make haskell` build either part separately.
`make` keeps Gradle's cache in `.gradle-user-home`; set `GRADLE_USER_HOME` to
share a cache across checkouts.

`make check-ghc-core GHC=/path/to/ghc` checks whether an installation carries
complete Core for `ghc-internal`, `base`, and their package dependencies. The
[compiler build guide](docs/ghc-core.md) includes a Hadrian settings file and
source-build instructions. Project runs can select complete installed Core
with `--installed-core required`; the default `pinned` provider remains a
separate, explicit choice. Exporter API support is limited to the GHC version
above.

Use `make test` for the test suite and `make clean` to remove build products.
`make test-modes` runs both handoff modes in separate JVMs from one shared build.
`make distclean` also removes the checkout's Gradle and Kotlin caches.
`make run ARGS='--help'` builds and runs the driver; the equivalent Cabal command
is `cabal run thc -- --help`.

Then run the included Cabal executable through THC:

```sh
cabal run thc -- run test/fixtures/run-pure/run-pure.cabal \
  --exe completed --thc-root "$PWD" --dist-dir "$PWD/build/run-package"
```

This example checks a mutable reference and returns `()` without printing.
For your own package, pass its directory or `.cabal` file and its executable
name to `thc run`. The command builds with Cabal, exports GHC Core, and executes
an accepted `Main.main :: IO ()` in THC. Use `cabal run thc -- --help` for the
command-line options. The [driver guide](docs/driver.md)
has the options and integration check.

A directory containing `cabal.project` also works for a bounded multi-package
build. For example, the included project has a data library, a native Template
Haskell helper, an internal library and an executable:

```sh
cabal run thc -- run test/fixtures/run-project \
  --exe app-run:exe:completed --thc-root "$PWD" \
  --dist-dir "$PWD/build/run-project"
```

Cabal builds the native dependencies needed for the helper, and THC runs the
executable's accepted Core. The driver uses Cabal's resolved unit IDs and
per-component build information for the export.

On Linux x86_64, with complete installed Core and matching configured GHC sources, the bytecode
backend now runs ordinary `putStrLn`, including GHC's original startup and Handle
shutdown. Select `--installed-core required --ghc-source /path/to/ghc-source`
on the project-directory path; the [driver guide](docs/driver.md) describes the
current Linux configuration and cache. A file-lifecycle test also matches native
GHC on UTF-8 reads and writes, append, seeking, EOF, caught missing-file errors,
and shutdown flushing. Binary `hPutBuf`/`hGetBuf` tests match native GHC on
offset buffers, short reads, EOF, and cleanup after exceptions. General file IO
remains incomplete. [Synchronous STM/TVar transactions](docs/stm.md) work in both
backends with buffered writes, atomic commit and real retry wakeups; asynchronous
transaction continuations and GC deadlock detection remain explicit limits.
The independent single-package `.cabal` path still excludes
internal library and build-tool dependencies; use a `cabal.project` directory
for the tested multi-package path. `thc build` and `thc repl` are future commands.

## What works

The runtime follows [Cadenza](https://github.com/ekmett/cadenza): indexed frames,
selective captures, partial applications and tail calls. Haskell adds laziness,
sharing, thunk updates and blackholes. Constructors have their own layouts, with
primitive fields where GHC's representation permits them.

Both the bytecode and AST backends run lazy Core with closures, recursive
bindings, typed constructor fields, local joins, and unboxed tuple inputs and
results. There is also bounded support for unboxed sum results, scalar arithmetic,
SIMD calls and operations for [supported shapes](docs/simd-families.md), and
managed arrays and mutable references.
All prefetch hints and the three user trace primops have
[JVM target implementations](docs/hints-and-tracing.md): hints are no-ops,
and trace records use the context's stderr diagnostic stream.

The bytecode backend also supports [asynchronous exceptions](docs/async-exceptions.md)
between Haskell threads. An interrupted shared thunk keeps its continuation, so
another thread can resume it without repeating completed work. Thread identities
are Java thread IDs scoped to their THC context.

`forkOn#` requests best-effort CPU affinity; ordinary `fork#` clears an inherited
pin, and capability queries report available CPU capacity rather than guest-thread
count. The base-only public [`THC` module](docs/cpu-affinity-api.md) exposes support
and per-fork acceptance queries. See [scheduling](docs/thread-scheduling.md) for
Linux/Windows behavior and the local Graal compiler-worker affinity reset.

The [polyglot example](docs/polyglot.md) calls JavaScript from Haskell with
`foreign import javascript`. GHC checks the declarations; THC implements them
with Truffle interop. Run `scripts/javascript-demo.sh` to try it. A lower-level
`THC.Polyglot` module also exposes language evaluation and opaque foreign values.

The tests include ordinary list, `STRef`, array, `ShortByteString`, `IntMap`,
`IntSet` and `Sequence` programs. They compare native GHC results with both
interpreters and compiled guest code. The [coverage guide](docs/README.md) links
the individual contracts, native checks and remaining gaps; the generated
[primop checklist](docs/primops.md) counts implemented and missing operations,
with concrete runtime limitations documented separately. Tuple, vector and pointer
representations do not make an implementation incomplete.

An explicit [full-Core locale/iconv proof group](docs/original-iconv.md) exercises
the four original imports through native glibc/Sulong, with context-owned handles
and checked buffer copies. It requires full installed GHC library Core and is
separate from stock-toolchain tests; it does not establish complete Handle/IO.

The [GMP provider](docs/gmp-limb-provider.md) implements eleven original GHC
foreign calls through Sulong and native GMP. Native comparisons cover interpreted
and compiled calls on both backends; full `Integer` and `Natural` coverage is
still separate work.

This is still an experiment, not a replacement for GHC. General `Main`/IO, the
complete boot-library closure, full FFI coverage, and stack-safe non-tail
evaluation remain unfinished. The script-level scalar entry is integer-only;
`thc run` has the narrower `IO ()` path described above. The experimental
[managed export API](docs/site/embedding.md) also exposes declared scalar
functions and IO actions through polyglot bindings. Host vector arguments and
results, and several aggregate storage forms, remain unsupported.

In particular, Map and Set still have cold runtime paths that strict loading
rejects. Diagnostic mode leaves explicit traps at those gaps. A successful
workload in that mode does not establish support for its whole call graph.

## Development examples

The test script prepares native GHC fixtures and builds the runtime. The scalar
runner then calls one exported entry; `--compile` requests guest compilation and
checks that code was installed:

```sh
scripts/try.sh
scripts/run.sh sumLoop 100000 --compile
```

The [bytecode backend](docs/bytecode.md) is the default. To use the AST backend:

```sh
THC_BACKEND=ast scripts/run.sh sumLoop 100000 --compile
```

Development checks and benchmarks live in `src/diagnostics/`. Build their separate
`build/diagnostics/thc-tools.jar` with `./gradlew toolsJar`; the `try` scripts do
this alongside `installDist`. Direct Java launches add that JAR to the runtime
classpath. The production distribution and JVM API reference exclude these tools
and the embedding/polyglot examples in `src/examples/`.

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
* [`thc.cabal`](thc.cabal), [`app/`](app/) and [`src/THC/`](src/THC/) build the
  command-line driver; [`test/`](test/) contains its Cabal fixtures and checks.
* [`src/main/`](src/main/) and [`src/test/`](src/test/) contain the Truffle
  runtime and its tests.
* [`examples/`](examples/) contains Haskell programs and the native oracle.
* [`scripts/`](scripts/) contains build, audit, benchmark and graph drivers.
* [The documentation index](docs/README.md) groups coverage and design reports;
  [`research/`](research/) contains the earlier design investigation.
* [The documentation site](https://ekmett.github.io/thc/) combines selected
  guides, the mixed Java/Kotlin reference and the Haskell library API.
  [Build it locally](docs/documentation.md) with `make docs` (also needs Pandoc).
* [Development](docs/contributing.md) covers local checks, build batching and manual
  integration of reviewed PRs. Update the [primop checklist](docs/primops.md#updating-the-list) when
  adding a primitive.
* [Cabal integration](docs/cabal.md) describes the limited working `thc run`
  path and the planned `thc build` and `thc repl` commands.

The older runtime experiments live on the
[legacy branch](https://github.com/ekmett/thc/tree/legacy).

## License

THC uses the same license as Cadenza: **UPL-1.0 AND BSD-3-Clause**.
See [LICENSE.txt](LICENSE.txt) and [NOTICE.md](NOTICE.md) for the terms and
attribution notices.

Unless you explicitly state otherwise, contributions submitted for inclusion
are provided under these same terms.

## Contact Information

Contributions and bug reports are welcome!

Please feel free to contact me through [GitHub](https://github.com/ekmett/thc)
or on the [##thc](https://web.libera.chat/##thc) IRC channel on
`irc.libera.chat` (Libera Chat).

-Edward Kmett
