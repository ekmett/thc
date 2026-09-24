# Cabal integration

The intended interface is `thc build`, `thc run` and `thc repl` inside an ordinary
Cabal project. A limited `thc run` now builds and executes a selected executable
whose `Main.main :: IO ()` passes the strict Core audit; see the
[driver instructions](../driver/README.md). General `thc build` and `thc repl`
remain planned.

The goal is to run those projects with no unimplemented paths in their complete
dependency closure, including cold exceptions and library internals. A successful
build or a benchmark that avoids unsupported code is not completion. Diagnostic
traps are useful during development, but do not belong in the finished interface.

Cabal should continue to solve dependencies and build components. GHC should
continue to parse, typecheck and optimize Haskell. THC needs to collect the Core
produced by those builds, link it, and run the selected component on Graal.

## Build and run

First, package `THC.Plugin` as an ordinary Cabal library, tied to the GHC version
whose API it uses. A small driver can arrange for Cabal's GHC invocations to load
that plugin. Package flags, CPP, generated modules and component dependencies
then come from the actual Cabal build rather than a second approximation of it.

Each source compilation should write a Core artifact identified by its GHC unit
ID and module name. A package manifest should record dependencies, entry points,
the export format and the compiler/exporter versions. Two instances of the same
package must remain distinct when Cabal gives them different unit IDs.

Dependencies need to be built with the exporter too. Installed interfaces do not
contain every executable body. The boot libraries need matching Core artifacts;
our current source export work is the beginning of that package set.

Cache exports by their source, compiler, options, dependencies and exporter.
Keep THC artifacts separate from Cabal's native outputs. A native object file or
an interface file is not evidence that the corresponding Core export exists.

`thc build [target]` would run the build, assemble the selected component's Core
and report any unsupported requirements. The current `thc run` builds one Cabal
executable and launches accepted `IO ()` Core on the JVM. General executables
still require the full
`Main`/IO/error path. The first useful test is an unchanged Cabal project with a
library, an executable and a `containers` dependency, running without diagnostic
mode and with its error paths exercised as well as normal execution.

## Build-time Haskell

Initially, Setup programs, preprocessors and Template Haskell should run under
native GHC. THC consumes the resulting program. This means retaining native
build products where the build needs them, even when the final program runs on
the JVM. Foreign calls remain an explicit runtime requirement.

## REPL

`thc repl [target]` should use the same package environment and export cache as
the other commands. GHC can maintain the interactive typechecking environment;
THC can load and execute the resulting expressions and bindings. Reloading a
module must replace its dependent code and values without retaining obsolete
closures indefinitely.

This can start with a driver around Cabal and GHC. A dedicated Cabal backend can
follow if it becomes useful; it need not block the first working integration.
