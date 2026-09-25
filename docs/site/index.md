<p class="thc-eyebrow">An experimental Haskell implementation</p>

# GHC knows Haskell. Graal makes it run.

THC takes optimized Core from GHC and executes it on Truffle/Graal. GHC handles
parsing, types, desugaring, and optimization; THC keeps that information available
to a specializing JVM runtime.

Start with a small accepted program, follow its Core into the runtime, or explore
the implementation through the two generated references above.

## Build, then run

Install **GHC 9.14.1**, **cabal-install 3.16**, **GraalVM 25.3.4.1 / JDK 25**,
and Python 3.12+. Linux x86_64 runtime builds also need clang and GMP development
headers. Put GHC on `PATH` and set `JAVA_HOME` to the GraalVM installation.

```sh
export JAVA_HOME=/path/to/graalvm
export PATH="$JAVA_HOME/bin:$PATH"
make
cabal run thc -- run test/fixtures/run-pure/run-pure.cabal \
  --exe completed --thc-root "$PWD" --dist-dir "$PWD/build/run-package"
```

This example checks a mutable reference and returns `()` without printing.
The [driver guide](../driver.md) covers your own Cabal packages and the tested
multi-package path. The [repository README](../../README.md) remains the main
build-and-run introduction.

## Pick a path

- **Run Haskell:** [driver commands](../driver.md), [Cabal integration](../cabal.md).
- **Embed THC:** [JVM entrypoints and value lifetimes](embedding.md),
  [Haskell-to-JavaScript calls](../polyglot.md).
- **Understand the boundary:** [installed GHC Core](../ghc-core.md),
  [package identities and manifests](../core-package-manifest.md),
  [foreign products and executable admission](../interface-foreign.md).
- **Work on the implementation:** [bytecode backend](../bytecode.md),
  [development checks](../contributing.md), [documentation builds](../documentation.md).

## What the references promise

**Haskell API** documents the exposed `THC.Plugin` and `THC.Interface` library
modules for GHC 9.14.1. The command-line driver is an executable; its internal
modules are not a separate supported library API.

**JVM reference** combines Kotlin KDoc and Java Javadoc. Begin with
`executionContext` and `loadEntry`, described in the embedding guide. Public
runtime carriers, nodes, frames, and generated calling conventions are
implementation details, not a stable embedding ABI. THC itself remains
experimental.

Both backends execute substantial lazy Core, but complete boot-library closure,
general IO, full FFI, and stack-safe non-tail evaluation remain unfinished.
Successful diagnostic workloads do not establish support for their whole call
graph. The [coverage index](../README.md) records contracts and remaining gaps.

## Evidence stays with the code

This site publishes a small guide set and API references. The complete
[documentation archive](../), [benchmark evidence](../../bench/), and
[test fixtures](../../test/) stay in the public repository. Their links point to
the same source revision as this site; large graphs, logs, archives, and generated
DSL dumps are not copied into the Pages artifact.
