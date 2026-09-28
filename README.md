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
git submodule update --init --depth 1
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
`make distclean` also removes the checkout's Gradle caches.
`make run ARGS='--help'` builds and runs the driver; the equivalent Cabal command
is `cabal run thc -- --help`.

Then run the included Cabal executable through THC:

```sh
cabal run thc -- run completed --project-dir t/fixtures/run-pure \
  --thc-root "$PWD" --dist-dir "$PWD/build/run-package"
```

This example checks a mutable reference and returns `()` without printing.
Inside your package, use `thc run [TARGET]`, following `cabal run` target syntax.
Omitting the target selects the current package's sole buildable executable,
otherwise its sole buildable runnable component. Explicit `PACKAGE:exe:NAME`,
`PACKAGE:test:NAME` and `PACKAGE:bench:NAME` targets work; tests must use the
`exitcode-stdio-1.0` interface. Use `--project-dir` or `--project-file` to select
another project. The command builds with Cabal, exports GHC Core, and executes
an accepted `Main.main :: IO ()` in THC. Use `cabal run thc -- --help` for the
command-line options. The [driver guide](docs/driver.md)
has the options and integration check. `thc acquire [TARGET] [FLAGS]` uses the
same acquisition path but stops before auditing or executing the guest; a
produced manifest is not a claim that the program is runnable.

A directory containing `cabal.project` also works for a bounded multi-package
build. For example, the included project has a data library, a native Template
Haskell helper, an internal library and an executable:

```sh
cabal run thc -- run app-run:exe:completed \
  --project-dir t/fixtures/run-project --thc-root "$PWD" \
  --dist-dir "$PWD/build/run-project"
```

Cabal builds the native dependencies needed for the helper, and THC runs the
executable's accepted Core. The driver uses Cabal's resolved unit IDs and
per-component build information for the export.

On Linux x86_64, with complete installed Core and matching configured GHC sources, the bytecode
backend runs ordinary `putStrLn`, including GHC's original startup and Handle
shutdown. Select `--installed-core required --ghc-source /path/to/ghc-source`
on the project-directory path; the [driver guide](docs/driver.md) describes the
current Linux configuration and cache. A file-lifecycle test also matches native
GHC on UTF-8 reads and writes, append, seeking, EOF, caught missing-file errors,
and shutdown flushing. Binary `hPutBuf`/`hGetBuf` tests match native GHC on
offset buffers, short reads, EOF, and cleanup after exceptions. General file IO
remains incomplete. [STM/TVar transactions](docs/stm.md) work in both backends
with buffered writes, atomic commit and real retry wakeups. Asynchronous
interruption aborts the attempt and saves a fresh transaction restart;
delimited transaction capture and GC deadlock detection remain explicit limits.
The Windows simple-package backend retains its existing restriction against
internal library and build-tool dependencies, behind the same Cabal-shaped
target interface. `thc build` and `thc repl` are future commands.

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
[Narrow integer carriers](docs/narrow-integer-carriers.md) use JVM Int computation
with byte/short/int stored fields; machine integers and explicit 64-bit integers
remain Long.
All prefetch hints and the three user trace primops have
[JVM target implementations](docs/hints-and-tracing.md): hints are no-ops,
and trace records use the context's stderr diagnostic stream.

Both backends support [asynchronous exceptions](docs/async-exceptions.md)
between Haskell threads. Public load requests accept a Boolean `asyncExceptions`:
`true` enables resumable delivery; when omitted, it defaults to `false` for AST
and `true` for bytecode. AST capture covers ordinary calls, cases, lets, local
joins, mask/catch scopes and shared-thunk updates. An interrupted shared thunk
keeps its continuation, so another thread can resume it without repeating
completed work. Thread identities name logical guest lifetimes within their
THC context, independently of Java thread IDs. Distinct callback guest lifetimes
can share one Java carrier thread.

`forkOn#` requests best-effort CPU affinity; ordinary `fork#` clears an inherited
pin. The logical capability count initially matches eligible CPU capacity and can
be changed per context by original `setNumCapabilities`; it is not a guest-thread
count or JVM pool size. See [RTS capabilities](docs/rts-event-capabilities.md).
The base-only public [`THC` module](docs/cpu-affinity-api.md) exposes support
and per-fork acceptance queries. See [scheduling](docs/thread-scheduling.md) for
Linux/Windows behavior and the local Graal compiler-worker affinity reset.

The base-only [`thc:runtime` services](docs/runtime-services.md) also expose
runtime permissions/backend, thread accounting and CPU eligibility, JVM memory
and collector statistics, context-owned native allocation accounting, and
structured stderr/JFR events and spans. Availability and scope are explicit;
native GHC reports unavailable JVM services honestly. Opt-in JIT telemetry is
separate in `THC.Internal.JIT`, explicitly `Unsafe`; the public `THC` facade is
`Safe` and does not re-export hazardous internal controls.

The [polyglot example](docs/polyglot.md) calls JavaScript from Haskell with
`foreign import javascript`. GHC checks the declarations; THC implements them
with Truffle interop. Run `bin/javascript-demo.sh` to try it. A lower-level
`THC.Polyglot` module also exposes language evaluation and opaque foreign values.

The tests include ordinary list, `STRef`, array, `ShortByteString`, `IntMap`,
`IntSet` and `Sequence` programs. They compare native GHC results with both
interpreters and compiled guest code. The [coverage guide](docs/README.md) links
the individual contracts, native checks and remaining gaps; the generated
[primop checklist](docs/primops.md) counts implemented and missing operations,
with [known behavior differences and limits](docs/primop-behavior.md) documented
primop by primop. Tuple, vector and pointer
representations do not make an implementation incomplete.

An explicit [full-Core locale/iconv proof group](docs/original-iconv.md) exercises
the four original imports through native glibc/Sulong, with context-owned handles
and checked buffer copies. It requires full installed GHC library Core and is
separate from stock-toolchain tests; it does not establish complete Handle/IO.

The [GMP provider](docs/gmp-limb-provider.md) implements twenty-four original GHC
foreign calls, including GCD, bitwise operations, shifts and floating conversions, through Sulong/native
GMP and a direct JVM translation of the scalar RTS encoding call. Native comparisons cover interpreted
and compiled calls on both backends; full `Integer` and `Natural` coverage is
still separate work.

This is still an experiment, not a replacement for GHC. General `Main`/IO, the
complete boot-library closure and full FFI coverage remain unfinished.
[Async-enabled AST evaluation](docs/async-exceptions.md) bounds nested calls
and thunk forcing with saved continuations. Deep evaluation inside an active
STM transaction remains unsupported; other modes keep their existing stack
behavior. The command-line scalar runner accepts integer arguments;
`thc run` uses the `IO ()` path described above. The
[Core embedding API](docs/site/embedding.md#load-a-core-entry) transports exact
numeric, vector, tuple and sum values, plus context-owned references and
functions. The declared C-export path separately exposes supported scalar
functions and IO actions. Recursive or lifted aggregate lets and global
aggregate storage remain unsupported.

In particular, Map and Set still have cold runtime paths that strict loading
rejects. Diagnostic mode leaves explicit traps at those gaps. A successful
workload in that mode does not establish support for its whole call graph.

## Development examples

The test script prepares native GHC fixtures and builds the runtime. The scalar
runner then calls one exported entry; `--compile` requests guest compilation and
checks that code was installed:

```sh
bin/try.sh
bin/run.sh sumLoop 100000 --compile
```

The [bytecode backend](docs/bytecode.md) is the default. To use the AST backend:

```sh
THC_BACKEND=ast bin/run.sh sumLoop 100000 --compile
```

Development checks and benchmarks live in `src/diagnostics/`. Build their separate
`build/diagnostics/thc-tools.jar` with `./gradlew toolsJar`; the `try` scripts do
this alongside `installDist`. Direct Java launches add that JAR to the runtime
classpath. The production distribution and JVM API reference exclude these tools
and the embedding/polyglot examples in `src/examples/`.

For the native-checked library suite and the diagnostic Map example:

```sh
bin/try-libraries.sh
THC_DIAGNOSTIC_UNSUPPORTED=true bin/try-map.sh
```

The latter builds, updates, queries and folds a histogram using the actual
`Data.Map.Strict` implementation from `containers-0.8`.

## Performance

Use native-GHC comparisons and compiler graphs to evaluate the workloads and
configurations you intend to run. Typed runtime storage and successful guest
compilation do not by themselves establish allocation removal or a speedup.

```sh
bin/benchmark.sh work/bench-kernels
THC_DIAGNOSTIC_UNSUPPORTED=true bin/benchmark-map.sh work/bench-map
THC_BACKEND=ast THC_DIAGNOSTIC_UNSUPPORTED=true bin/benchmark-map.sh work/bench-ast
```

Run the corresponding `try` script first and choose an unused output directory:
the scripts overwrite files in the supplied directory. Benchmarks vary their
inputs, consume the results, warm the JVM and compare against native GHC. Graph capture is a
separate run. Diagnostic Map execution does not establish strict support for
its entire dependency closure.

The [current runtime guides](docs/README.md#performance-and-runtime-design)
describe implemented protocols and opt-in experiments.

## Finding your way around

* [`src/compiler/`](src/compiler/) exports executable Core from GHC, with
  representation and evaluation information.
* [`src/driver/`](src/driver/) builds the
  command-line driver; [`t/`](t/) contains its Cabal fixtures and checks.
* [`src/cbd/`](src/cbd/) implements compact Core storage and inspection.
* [`src/runtime/`](src/runtime/) provides the public Haskell runtime API.
* [`nih/pinned/`](nih/pinned/) pins upstream Git submodules; [`nih/licenses/`](nih/licenses/) collects notices.
* [`src/main/`](src/main/) and [`src/test/`](src/test/) contain the Truffle
  runtime and its tests.
* [`src/examples/`](src/examples/) contains Haskell programs and the native oracle.
* [`bin/`](bin/) contains build, audit, benchmark and graph drivers.
* [The architecture guide](docs/architecture.md) describes the current system
  and planned work. [The documentation index](docs/README.md) groups coverage
  and design reports; [open design questions](research/open-questions.md)
  identify the next design decisions and relevant background.
* [The documentation site](https://ekmett.github.io/thc/) combines selected
  guides, the Java reference and the Haskell library API.
  [Build it locally](docs/documentation.md) with `make docs` (also needs Pandoc).
* [Development](docs/contributing.md) covers local checks, build batching and manual
  integration of reviewed PRs. Update the [primop checklist](docs/primops.md#updating-the-list) when
  adding a primitive.
* [Cabal integration](docs/cabal.md) describes the working `thc acquire` and
  limited `thc run` paths, and the planned `thc build` and `thc repl` commands.

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
