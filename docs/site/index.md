<p class="thc-eyebrow">Haskell on Truffle/Graal</p>

# thc

I'm experimenting with using GHC as a frontend for a high performance Haskell
implementation on the JVM. GHC does the parsing, type checking, desugaring and
optimization. THC takes the resulting Core and gives Graal something it can
specialize.

GHC already knows quite a lot about compiling Haskell. The intention is to keep
that information around long enough to use it.

## Using THC

The goal is to work with ordinary Cabal projects. Keep your Haskell source,
package dependencies and build configuration, and run the resulting program on
Truffle/Graal. Cabal still builds the native code needed for Template Haskell and
other compile-time work. THC exports the executable Core and caches package
bundles so that an unchanged dependency need not be exported again.

The command-line interface I'm working toward is:

```sh
thc build
thc run . --exe my-program
thc repl
```

`thc run` has a working, limited implementation today. `thc build` and `thc repl`
are planned; the REPL will use GHC's frontend rather than introduce a second
Haskell parser and type checker. The aim is to run complete programs, including
their error paths.

Running on Truffle also gives Haskell a route into other languages. The
[JavaScript example](../polyglot.md) already supports `foreign import javascript`.
The [embedding guide](embedding.md) describes the current JVM entrypoints for
loading and calling accepted Haskell code from Java or Kotlin.

## Build it today

You need **GHC 9.14.1**, **cabal-install 3.16**, **GraalVM 25.3.4.1 / JDK 25**,
and **Python 3.12+**. Put GHC, `ghc-pkg` and `runghc` on `PATH`. Linux x86_64
builds also need clang and GMP development headers and libraries
(`libgmp-dev` on Debian/Ubuntu).

```sh
git clone https://github.com/ekmett/thc.git
cd thc
export JAVA_HOME=/path/to/graalvm
export PATH="$JAVA_HOME/bin:$PATH"
make
```

On macOS, `JAVA_HOME` should point at the GraalVM bundle's `Contents/Home`.
`make` builds the JVM runtime with Gradle and the Haskell library and driver with
Cabal. Both builds are incremental; `make runtime` and `make haskell` build them
separately.

Run the included Cabal executable:

```sh
cabal run thc -- run test/fixtures/run-pure/run-pure.cabal \
  --exe completed --thc-root "$PWD" --dist-dir "$PWD/build/run-package"
```

It checks a mutable reference and returns `()` without printing. The
[driver guide](../driver.md) covers executable selection and the tested
multi-package `cabal.project` path. Use `cabal run thc -- --help` for current
options, `make test` for the test suite, and `make clean` to remove build products.
`make distclean` also removes this checkout's Gradle and Kotlin caches.

For installed libraries, THC needs more Core than a stock GHC installation
usually retains. `make check-ghc-core` checks the selected compiler; the
[GHC build guide](../ghc-core.md) supplies the Hadrian configuration and source
build instructions. Project runs can request those complete installed libraries
with `--installed-core required`.

## Current limitations

This is still an experiment. Both backends execute substantial lazy Core, with
closures, sharing, typed constructor fields, joins, unboxed tuples, arrays and
mutable references. Tests compare native GHC results with interpreted and
compiled guest execution. That does not yet amount to general Cabal package
support.

Complete boot-library loading, general IO and FFI, and stack-safe non-tail
evaluation remain unfinished. Even `main = putStrLn "hello"` still fails the
strict load audit. The narrower `thc run` example above works; arbitrary
executables are not yet accepted. The exporter currently targets GHC 9.14.1.

Unsupported reachable paths are reported before a normal run. Development
benchmarks can explicitly use diagnostic traps, but success on one path does
not establish support for the rest of the program. The
[coverage index](../README.md) and [primop checklist](../primops.md) record the
current contracts and gaps.

## Documentation

The guides cover [Cabal integration](../cabal.md),
[package bundles](../core-package-manifest.md),
[the bytecode backend](../bytecode.md), and [development](../contributing.md).
The Haskell API documents `THC.Plugin` and `THC.Interface`; the Runtime reference
combines Kotlin KDoc and Java Javadoc. Runtime nodes and storage classes are
implementation details, not a stable embedding API.

The [source repository](../../README.md) includes the full
[documentation archive](../), [benchmark evidence](../../bench/) and
[test fixtures](../../test/). THC uses the same license as Cadenza:
**UPL-1.0 AND BSD-3-Clause**; see [LICENSE.txt](../../LICENSE.txt).

## Contact Information

Contributions and bug reports are welcome!

Please feel free to contact me through [GitHub](https://github.com/ekmett/thc)
or on the [##thc](https://web.libera.chat/##thc) IRC channel on
`irc.libera.chat` (Libera Chat).

-Edward Kmett
