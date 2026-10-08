<p class="thc-eyebrow">Haskell on Truffle/Graal</p>

# thc

<figure class="thc-mascot">
<img class="thc-bot-light" src="turbo-haskell-bot-flipped-light.png" width="240" height="240" alt="A cheerful purple robot waving toward the text, with a lambda badge, hovering on little rocket boosters above a soft shadow.">
<img class="thc-bot-dark" src="turbo-haskell-bot-flipped-dark.png" width="240" height="240" alt="A cheerful purple robot waving toward the text, with a lambda badge, hovering on little rocket boosters above a soft shadow.">
</figure>

I'm experimenting with using GHC as a frontend for a high performance Haskell
implementation on the JVM. GHC does the parsing, type checking, desugaring and
optimization. THC takes the resulting Core and gives Graal something it can
specialize.

GHC already knows quite a lot about compiling Haskell. The intention is to keep
that information around long enough to use it.

Have a look around, try the examples, or come find us in
[##thc](https://web.libera.chat/##thc) on `irc.libera.chat`.

## Using THC

The goal is to work with ordinary Cabal projects. Keep your Haskell source,
package dependencies and build configuration, and run the resulting program on
Truffle/Graal. Cabal still builds the native code needed for Template Haskell and
other compile-time work. THC exports the executable Core and caches package
bundles so that an unchanged dependency need not be exported again.

The implemented commands separate acquisition from execution:

```sh
thc acquire my-program --thc-root /absolute/path/to/thc
thc run my-program --thc-root /absolute/path/to/thc
```

`thc acquire [TARGET] [FLAGS]` produces a package manifest without auditing or
executing the guest. `thc run [TARGET] [FLAGS] [-- ARG...]` builds and executes
the GHC-selected `IO a` action. Add `--verify-artifacts` for artifact verification and the
pre-launch dependency audit.
Targets use Cabal's syntax, including `my-package:bench:my-benchmark` and
`my-package:test:my-test`. With no target, Cabal selects the current package's
sole buildable executable, otherwise its sole buildable runnable component.
Use `--project-dir` or `--project-file` to select a different project.
`--thc-root` names the built THC checkout, not the application directory; both
commands require it.
`thc build` and `thc repl` are not implemented. Acquisition alone does not
establish that a program is runnable; the [architecture guide](../architecture.md)
explains linking and runtime admission.

You can already run real generators and utilities: see the
[Happy, HsColour and Alex command lines](../driver.md#run-real-applications).
They use ordinary upstream packages and the complete-Core installation described
below, with setup, expected output and current backend limits spelled out.

Running on Truffle also gives Haskell a route into other languages. The
[JavaScript example](../polyglot.md) supports `foreign import javascript`.
The [embedding guide](embedding.md) describes the current JVM entrypoints for
loading and calling accepted Haskell code from Java.
The public [`thc:runtime` library](../runtime-services.md) gives Haskell programs
typed access to runtime identity, thread and CPU-affinity information, memory
and GC statistics, structured tracing, and optional JIT diagnostics. Unavailable
services are explicit; JVM-wide statistics are distinguished from context-local
measurements.

## Build and run

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
cabal run thc -- run completed --project-dir t/fixtures/run-pure \
  --thc-root "$PWD" --dist-dir "$PWD/build/run-package"
```

It checks a mutable reference and returns `()` without printing. The
[driver guide](../driver.md) covers runnable target selection and the tested
multi-package `cabal.project` path. Use `cabal run thc -- --help` for current
options, `make test` for the test suite, and `make clean` to remove build products.
`make distclean` also removes this checkout's Gradle caches.

THC executes Core, GHC's intermediate representation, for your program and the
Haskell libraries it calls. Standard GHC installations usually omit complete
Core for bundled libraries such as `base` and `ghc-internal`. Building those
libraries with
[`-fwrite-if-simplified-core`](https://downloads.haskell.org/ghc/9.14.1/docs/users_guide/phases.html#ghc-flag-fwrite-if-simplified-core)
retains every binding in their `.hi` interface files. The flag belongs on the
library build; adding it only to your program cannot recover missing library
Core.

THC includes pinned sources from **GHC 9.14.1** and compiles a supported subset
of its library modules itself. This default runs THC's small examples with a
standard GHC 9.14.1 installation, without rebuilding GHC. For applications that
need more library Core, build GHC's libraries with the flag above, then select
them with `thc run TARGET --installed-core required`.

`make check-ghc-core GHC=/path/to/ghc` checks for the retained Core. The
[GHC build guide](../ghc-core.md) gives the build instructions.

## Runtime scope

Both backends execute lazy Core with closures, sharing, typed constructor fields,
joins, unboxed tuples and sums, arrays and mutable references. Native imports,
callbacks and executable IO require the [foreign-code setup](../interface-foreign.md).
Package dependencies must supply complete Core, including cold error paths.

[Async-enabled execution](../async-exceptions.md) bounds nested calls and thunk
forcing with saved continuations. [STM](../stm.md), [MVars](../managed-mvars.md)
and [delimited continuations](../delimited-continuations.md) have explicit capture
and ownership limits. Automatic weak finalization and GC deadlock detection
remain unsupported.

THC is experimental. The [documentation index](../README.md),
[primop checklist](../primops.md) and [behavior reference](../primop-behavior.md)
provide the current contracts. A diagnostic run through a rejected dependency
closure does not establish complete program support.

## Documentation

The guides cover [Cabal integration](../cabal.md),
[package bundles](../core-package-manifest.md),
[the bytecode backend](../bytecode.md), [runtime services](../runtime-services.md),
and [development](../contributing.md).
The Haskell runtime API documents the public `THC` namespace; the separate
compiler API covers `THC.Plugin` and `THC.Interface`. `THC.Internal.JIT` is
intentionally unstable and `Unsafe` for Safe Haskell: `.Internal` names are not
stable interfaces. The JVM-internals reference is generated from Java
Javadoc. Runtime nodes and storage classes are implementation details, not a
stable embedding API.

The [source repository](../../README.md) includes the full
[documentation](../), [benchmark runners](../../bench/) and
[test fixtures](../../t/). THC uses the same license as Cadenza:
**UPL-1.0 AND BSD-3-Clause**; see [LICENSE.txt](../../LICENSE.txt).

## Contact Information

<div class="thc-contact">
<div class="thc-contact-copy">

Contributions and bug reports are welcome!

Please feel free to contact me through [GitHub](https://github.com/ekmett/thc)
or on the [##thc](https://web.libera.chat/##thc) IRC channel on
`irc.libera.chat` (Libera Chat).

-Edward Kmett

</div>
<aside class="thc-contact-bot" aria-label="Turbo Haskell bot">
<video src="turbo-haskell-bot-blocks.webm" width="1280" height="720" autoplay loop muted playsinline aria-label="The Turbo Haskell robot playing with blocks."></video>
<button type="button" class="thc-animation-toggle" hidden>Pause animation</button>
</aside>
</div>
<script src="mascot.js"></script>
