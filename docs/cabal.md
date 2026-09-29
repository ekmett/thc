# Cabal integration

Use `thc run [TARGET] [FLAGS] [-- ARG...]` inside an ordinary Cabal project to
build and execute a Haskell component on THC. Use `thc acquire` to produce its
Core package manifest without running it. Build the driver with
`cabal build exe:thc`; from the THC checkout, invoke it with `cabal run thc -- ...`.
See the [driver guide](driver.md) for commands, toolchain setup and examples.

## Select a component

Targets follow Cabal syntax: `PACKAGE:exe:NAME`, `PACKAGE:test:NAME`, or
`PACKAGE:bench:NAME`, with short forms where unambiguous. Tests must use
`exitcode-stdio-1.0`. With no target, select the current package's sole buildable
executable, otherwise its sole runnable component. Ambiguity is an error.
Use `--project-dir` or `--project-file` to select another project, and
`--thc-root` to identify the built THC checkout.

Cabal handles package flags, CPP, generated modules and dependencies. Setup
programs, preprocessors and Template Haskell run under native GHC. THC exports
the selected component and dependency Core using Cabal's actual unit IDs and
compiler configuration, then runs the accepted `Main.main :: IO ()` on Graal.
Two instances of one package remain distinct when Cabal assigns different units.

Ordinary runs validate code as it is loaded. Add `--verify-artifacts` to request
a strict pre-launch reachable-Core audit and artifact-hash verification.
A successful acquisition or native build does not establish THC support for
every reachable operation.

## Supply dependencies

Every reachable Haskell definition needs executable Core. The default
`--installed-core pinned` provider supplies a limited set of exact GHC library
sources. For ordinary applications, use `--installed-core required` with a
complete-Core GHC 9.14.1 installation and, where required, its matching configured
source tree. Thin interfaces fail explicitly; the driver does not silently
switch providers. Follow [GHC library Core](ghc-core.md) to prepare them.

Package C/C++ and CAPI imports use the configured native sources and link
settings. See [foreign imports and exports](interface-foreign.md) for LLVM setup,
buffer and callback contracts, and unsupported forms. Native build products are
still needed for compile-time Haskell even though the final guest runs on THC.

Exports are cached by source, configuration, native products and exporter
identity. Local Core stays under the selected build directory; dependency bundles
use the THC cache. Reusing an unchanged package avoids another export. See
[caching and failures](driver.md#caching-and-failures) for locations and cleanup.

`thc build` and `thc repl` are not implemented. The native Windows simple-package
backend has narrower dependency support; consult [Windows builds](windows.md).
