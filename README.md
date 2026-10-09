# thc

<!-- badges:start -->
[![build](https://img.shields.io/github/actions/workflow/status/ekmett/thc/build.yml?branch=main&style=flat&label=build&logo=githubactions&logoColor=white)](https://github.com/ekmett/thc/actions/workflows/build.yml?query=branch%3Amain)
[![docs build](https://img.shields.io/github/actions/workflow/status/ekmett/thc/docs.yml?branch=main&style=flat&label=docs+build&logo=githubactions&logoColor=white)](https://github.com/ekmett/thc/actions/workflows/docs.yml?query=branch%3Amain)
[![issues](https://img.shields.io/github/issues/ekmett/thc?style=flat&label=issues&color=007ec6&logo=github&logoColor=white)](https://github.com/ekmett/thc/issues)
[![commits](https://img.shields.io/github/commit-activity/w/ekmett/thc?style=flat&label=commits&color=007ec6&logo=github&logoColor=white)](https://github.com/ekmett/thc/activity)

[![CMake: 3.24+](https://img.shields.io/static/v1?label=CMake&message=3.24%2B&color=064F8C&style=flat&logo=cmake&logoColor=white)](CMakeLists.txt)
[![Haskell](https://img.shields.io/static/v1?label=&message=Haskell&color=5e5086&style=flat&logo=haskell&logoColor=white)](thc.cabal)
[![GHC: 9.14.1](https://img.shields.io/static/v1?label=GHC&message=9.14.1&color=5e5086&style=flat&logo=haskell&logoColor=white)](thc.cabal)
[![Cabal: 3.16](https://img.shields.io/static/v1?label=Cabal&message=3.16&color=5e5086&style=flat&logo=haskell&logoColor=white)](README.md)
[![Java: 25](https://img.shields.io/static/v1?label=Java&message=25&color=b66a13&style=flat&logo=openjdk&logoColor=white)](README.md)
[![GraalVM: 25.3.4.1](https://img.shields.io/static/v1?label=GraalVM&message=25.3.4.1&color=b66a13&style=flat&logo=openjdk&logoColor=white)](etc/jam-graalvm.json)
[![Gradle: 9.7.1](https://img.shields.io/static/v1?label=Gradle&message=9.7.1&color=02303A&style=flat&logo=gradle&logoColor=white)](nih/gradle/wrapper/gradle-wrapper.properties)

[![OS: Linux · macOS · Windows](https://img.shields.io/static/v1?label=OS&message=Linux+%C2%B7+macOS+%C2%B7+Windows&color=64748b&style=flat)](README.md)
[![CPU: x86-64 · ARM64](https://img.shields.io/static/v1?label=CPU&message=x86-64+%C2%B7+ARM64&color=64748b&style=flat)](docs/jam-runtime.md)

[![license: UPL-1.0 AND BSD-3-Clause](https://img.shields.io/static/v1?label=license&message=UPL-1.0+AND+BSD-3-Clause&color=007ec6&style=flat)](LICENSE)
[![Contributor Covenant: 2.0](https://img.shields.io/static/v1?label=Contributor+Covenant&message=2.0&color=007ec6&style=flat&logo=contributorcovenant&logoColor=white)](CODE_OF_CONDUCT.md)

[![docs: read](https://img.shields.io/static/v1?label=docs&message=read&color=007ec6&style=flat)](https://ekmett.github.io/thc/)
<!-- badges:end -->

Haskell on Truffle/Graal.

I'm experimenting with using GHC as a frontend for a high performance Haskell
implementation on the JVM. GHC does the parsing, type checking, desugaring and
optimization. THC takes the resulting Core and gives Graal something it can
specialize.

GHC already knows quite a lot about compiling Haskell. The intention is to keep
that information around long enough to use it.

![Turbo Haskell](assets/turbo-haskell.png)

## Build and run

For native Windows, use the [PowerShell build and test guide](docs/windows.md).
It lists the required tools and current platform limits.

You need **GHC 9.14.1** (including `ghc-pkg` and `runghc`), **cabal-install 3.16**,
**JAM-patched GraalVM 25.3.4.1 / JDK 25**, and Python 3.12+. Put GHC on your `PATH` and
point `JAVA_HOME` at the extracted JAM package's `graalvm` directory, including
on macOS. The Gradle wrapper downloads its dependencies on the first build.
The driver checks for cabal-install 3.16. Use the pinned GraalVM release
above: incompatible Graal and Truffle compiler versions can disable runtime
compilation and leave execution in the interpreter.
Linux x86_64 setup includes clang and GMP development headers and libraries
(for example, `libgmp-dev` on Debian/Ubuntu) for native package dependencies.
Run `cabal update` once so Cabal has a package index.

`thc run` also needs LLVM 18's `clang`, `llvm-link`, `opt` and `llvm-nm`, even
for pure Haskell programs: acquisition compiles the native imports of boot
libraries such as `ghc-internal` to LLVM bitcode. CI uses Homebrew's keg-only
`llvm@18` on macOS and `clang-18` and `llvm-18` on Linux. Put that `bin`
directory on `PATH` or set the
[LLVM tool variables](docs/interface-foreign.md#run-a-package-with-native-imports).

The JAM package is pinned in [`etc/jam-graalvm.json`](etc/jam-graalvm.json);
see [migration status and platform requirements](docs/jam-runtime.md). THC's
default distribution also includes **patched Truffle API, runtime and Sulong JARs**. Their [patch inventory and shared-host effects](docs/truffle-patches.md)
are part of the embedding contract: some changes affect other languages using
the same runtime, including across separate contexts and engines.

From the repository root:

```sh
git -c core.autocrlf=false submodule update --init --depth 1
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

THC needs Core, GHC's intermediate representation, for both your program and
the Haskell libraries it calls. Standard GHC installations usually omit the
complete Core for their bundled libraries, including `base` and `ghc-internal`.
Compiling those libraries with
[`-fwrite-if-simplified-core`](https://downloads.haskell.org/ghc/9.14.1/docs/users_guide/phases.html#ghc-flag-fwrite-if-simplified-core)
keeps every binding in their `.hi` interface files so THC can load it. The flag
must be used when building the libraries; adding it only to your program
cannot recover Core from libraries already installed without it.

With a standard **GHC 9.14.1** installation, the default `--installed-core pinned`
provider rebuilds selected bundled libraries from the pinned GHC release and
caches their complete Core. Their versions, modules and dependencies must match
the selected installation. The first acquisition includes this library build.

Alternatively, build GHC's libraries with the flag above and select their
retained Core with `thc run TARGET --installed-core required`.
`make check-ghc-core GHC=/path/to/ghc` checks whether the installed libraries
contain the needed Core. The [GHC build guide](docs/ghc-core.md) shows how to
apply the flag when building GHC 9.14.1, the version THC currently supports.

Fixture tests also require CMake 3.24+ and Ninja. `make fixtures` builds all
admitted fixture files. For routine development, select an exact class, for
example `make test TESTS=thc.RuntimeTest`.
`make test-modes TESTS=thc.RuntimeTest` runs both handoff modes from shared fixture
files; see the [fixture graph and current limits](docs/fixture-build.md).
`make clean` removes build products.
`make distclean` also removes the checkout's Gradle caches.
`make run ARGS='--help'` builds and runs the driver; the equivalent Cabal command
is `cabal run thc -- --help`.

Run the included smoke test through THC:

```sh
cabal run thc -- run completed --project-dir t/fixtures/run-pure \
  --thc-root "$PWD" --dist-dir "$PWD/build/run-package"
```

This fixture checks a mutable reference and returns `()` without printing.
Inside your package, `thc build [TARGETS...]` follows Cabal build selection and
acquires dependency Core without launching an application. Omit targets for the
current package, use `all`, or select libraries and executables together.
Use `thc run [TARGET]`, following `cabal run` target syntax, to build and execute.
For an installed driver in your own project:

```sh
thc run my-package:exe:my-program --thc-root /path/to/thc
```

Omitting the target selects the current package's sole buildable executable,
otherwise its sole buildable runnable component. Explicit `PACKAGE:exe:NAME`,
`PACKAGE:test:NAME` and `PACKAGE:bench:NAME` targets work; tests must use the
`exitcode-stdio-1.0` interface. Use `--project-dir` or `--project-file` to select
another project. The command builds with Cabal, exports GHC Core, and executes
the GHC-selected `IO a` action in THC. Use `cabal run thc -- --help` for the
command-line options. The [driver guide](docs/driver.md)
has the options and integration check. `thc acquire [TARGET] [FLAGS]` uses the
same acquisition path but stops before auditing or executing the guest; a
produced manifest is not a claim that the program is runnable.

A directory containing `cabal.project` also works for a multi-package build.
The integration fixture includes a data library, a native Template Haskell
helper, an internal library and an executable:

```sh
cabal run thc -- run app-run:exe:completed \
  --project-dir t/fixtures/run-project --thc-root "$PWD" \
  --dist-dir "$PWD/build/run-project"
```

Cabal builds the native dependencies needed for the helper, and THC runs the
executable's accepted Core. The driver uses Cabal's resolved unit IDs and
per-component build information for the export.

For native imports, installed-library IO and callbacks, follow the
[foreign-code setup](docs/interface-foreign.md). Platform permissions and
resource lifetimes are explicit; see [Windows limits](docs/windows.md) for the
native Windows path. General project acquisition (`thc build`) is currently
available on macOS and Linux. `thc repl` is not implemented.

## Runtime capabilities

Both the bytecode and AST backends execute lazy Core with sharing, closures,
recursive bindings, typed constructor fields, local joins, unboxed tuples and
sums, [SIMD](docs/simd.md), arrays and mutable references. The bytecode backend
is the default. [Architecture](docs/architecture.md) explains the shared value
model and the two execution paths.

[Asynchronous exceptions](docs/async-exceptions.md), [MVars](docs/managed-mvars.md)
and [STM](docs/stm.md) support concurrent Haskell programs. Interrupted shared
thunks retain their unfinished work. Ordinary evaluation bounds nested calls
and forcing through saved continuations. Load requests and ordinary executable
launches default to async off on both backends: off speculates on a single guest
admission origin until guest concurrency is admitted. Explicit async opt-in
enables polling immediately. Delimited capture across STM and GC-driven deadlock detection remain unsupported.
Jam supplies the collector support for [automatic weak finalization](docs/weak-explicit.md),
including values and finalizers that refer back to their keys. The
[foreign resource example](src/examples/standard-apps/foreign-resource/README.md)
uses ordinary `ForeignPtr` APIs to keep a native buffer alive during use and run
a Haskell cleanup action when its last owner disappears. It passes on both
backends and handoff modes on Linux. The [weak thread example](src/examples/README.md)
observes worker lifetime and uses a finalizer to return mutable state to the
application, where it can be updated again. It also passes all four combinations.
The [buffered Handle example](src/examples/standard-apps/buffered-handle/README.md)
shows original `System.IO` finalizers flushing output and releasing file locks
when a handle becomes unreachable. It matches GHC on Linux with bytecode/default
and AST/dense execution. The [lazy file example](src/examples/standard-apps/lazy-file/README.md)
shows deferred input keeping its Handle alive until the unread tail is discarded. The
[weak document cache](src/examples/standard-apps/weak-cache/README.md) reuses lazy
analyses while their document is live, then releases the document/report cycle
without discarding the cache itself.
The [Jam runtime guide](docs/jam-runtime.md) records the wider JVM/Native Image
evidence and the remaining production and platform qualification.

The public [`thc:runtime` API](docs/runtime-services.md) exposes permissions,
thread and affinity observations, memory/GC statistics and structured tracing.
Availability and measurement scope are explicit. `THC.Internal.JIT` supplies
separate unstable diagnostics. [Polyglot calls](docs/polyglot.md) and the
[JVM embedding API](docs/site/embedding.md) support JavaScript and host callers;
[foreign code](docs/interface-foreign.md) describes native imports and exports.

THC remains experimental. The [behavior reference](docs/primop-behavior.md) lists
current semantic and platform limits, and the generated
[primop checklist](docs/primops.md) inventories operations. Package support also
requires complete dependencies, including cold error paths. Use
`--verify-artifacts` for the driver's pre-launch audit and artifact checks.
Diagnostic mode leaves explicit traps at unsupported sites; completing one path
in that mode does not establish support for its whole closure.

## Runtime checks

The test script prepares native GHC fixtures and builds the runtime. The scalar
runner then calls one exported entry; `--compile` requests guest compilation and
checks that code was installed:

```sh
bin/try.sh
bin/run.sh build/core/THC.Prim.Test.cbd,build/core/Fixtures.cbd main:Fixtures.sumLoop 100000 --compile
```

The [bytecode backend](docs/bytecode.md) is the default. To use the AST backend:

```sh
THC_BACKEND=ast bin/run.sh build/core/THC.Prim.Test.cbd,build/core/Fixtures.cbd main:Fixtures.sumLoop 100000 --compile
```

THC launchers disable Graal's automatic loop vectorization by default. Explicit JDK
Vector API operations remain enabled. To enable automatic loop vectorization for a
run, set `JAVA_OPTS=-Djdk.graal.VectorizeLoops=true`. This is a JVM-wide compiler
setting, so it applies to both backends. Gradle application and test JVMs use the
same default and respect explicit JVM properties; embedders choose their own JVM
options.

Development checks and benchmarks live in `src/diagnostics/`. Build their separate
`build/diagnostics/thc-tools.jar` with `./gradlew toolsJar`; the `try` scripts do
this alongside `installDist`. Direct Java launches add that JAR to the runtime
classpath. The production distribution and JVM API reference exclude these tools
and the embedding/polyglot examples in `src/examples/`.

For the native-checked library suite and diagnostic Map workload:

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
make -C bench kernels OUT="$PWD/work/bench-kernels"
THC_DIAGNOSTIC_UNSUPPORTED=true make -C bench map OUT="$PWD/work/bench-map"
THC_BACKEND=ast THC_DIAGNOSTIC_UNSUPPORTED=true make -C bench map OUT="$PWD/work/bench-ast"
```

Direct benchmark JVMs also disable automatic loop vectorization by default. Set
`JDK_JAVA_OPTIONS=-Djdk.graal.VectorizeLoops=true` for an explicit comparison
with it enabled; inherited JVM options preserve caller choices.
`bash bin/test-benchmark-entrypoints.sh` checks paths and launch defaults without
preparing fixtures or measuring.

Choose an unused output directory; the targets prepare and check their inputs
before measuring, then overwrite files in the supplied directory. Benchmarks vary their
inputs, consume the results, warm the JVM and compare against native GHC. Graph capture is a
separate run. Diagnostic Map execution does not establish strict support for
its entire dependency closure.

The [current runtime guides](docs/README.md#compiler-and-contributor-references)
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
  and its boundaries. [The documentation index](docs/README.md) groups user
  guides and implementation references.
* [The documentation site](https://ekmett.github.io/thc/) combines selected
  guides, the Java reference and the Haskell library API.
  [Build it locally](docs/documentation.md) with `make docs` (also needs Pandoc).
* [Development](docs/contributing.md) covers building, testing and contributing.
  [Generated references](docs/contributing.md#generated-references) explain how to
  update the primitive inventory.
* [Cabal integration](docs/cabal.md) describes `thc build`, `thc acquire` and
  `thc run`, including their current limits.

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
