# Cabal integration

Use `thc build [TARGETS...] [FLAGS]` inside a Cabal project to build components
and acquire their dependency Core. Omit targets for the current package, use
`all`, or name libraries and runnable components together. `thc run [TARGET]
[FLAGS] [-- ARG...]` performs acquisition and executes one component on THC.

Use `thc build --native-image [TARGETS...]` to produce fresh THC Native Images
for selected executables, `exitcode-stdio-1.0` test suites and benchmarks after
normal acquisition; libraries still acquire. The current producer requires
Linux x86_64, the pinned GraalVM and synchronous AST execution. The driver uses
the qualified `resource-copy` profile by default; override it with
`THC_NATIVE_IMAGE_VECTOR_PROFILE`. Per-component artifacts and their concrete
`completion.json` inventory are under `DIST/native-images/<unit-id SHA256>/`.
Each runnable has a separate `packages.json` dependency closure and exception
bridge; the shared `DIST/packages.json` is acquisition inventory. Deployable
outputs are `artifacts/program` and the runtime files/directories listed in
`completion.json`, taken from Native Image's own artifact report.
Use `--installed-core pinned` (default) or `required`; the `demand` provider
converts interfaces inside a running THC context and cannot supply offline image
capture. Images are built afresh, with no image cache or static-linking guarantee. This
option is accepted only by `build` and does not execute the application.

`thc acquire` retains the same single-runnable selection as `run` for compatibility.
Build the driver with
`cabal build exe:thc`; from the THC checkout, invoke it with `cabal run thc -- ...`.
See the [driver guide](driver.md) for commands, toolchain setup and examples.

## A first program

After building THC (see the [README](../README.md)), create a package outside the
checkout with one executable. Use `base >= 4.22 && < 4.23`, the version shipped
with GHC 9.14.1.

```cabal
-- hello.cabal
cabal-version: 3.0
name: hello
version: 0.1.0.0
build-type: Simple

executable hello
  main-is: Main.hs
  hs-source-dirs: app
  build-depends: base >= 4.22 && < 4.23
  default-language: Haskell2010
```

```haskell
-- app/Main.hs
module Main (main) where

main :: IO ()
main = do
  putStrLn "Hello from Haskell on Truffle/Graal!"
  print (sum [1 .. 100 :: Int])
```

From the package directory, run the driver built in the THC checkout (find it
with `cabal list-bin exe:thc` there):

```sh
cd hello
"$(cd /path/to/thc && cabal list-bin exe:thc)" run --thc-root /path/to/thc
```

or, from the THC checkout, `cabal run thc -- run hello --project-dir /path/to/hello
--thc-root "$PWD"` (the explicit `hello` target is required there). The first run
also acquires Core for the bundled libraries, which takes longer. It prints the
greeting and `5050`; build products go to `dist-thc/` in the package.

## Select a component

`run` targets follow Cabal syntax: `PACKAGE:exe:NAME`, `PACKAGE:test:NAME`, or
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
`--installed-core pinned` provider rebuilds selected GHC 9.14.1 bundled libraries
from the pinned release and caches their complete Core. Versions, modules and
dependencies must match the selected installation. `--installed-core required`
instead reads Core already retained in installed interfaces, using the matching
configured source tree where required. Missing Core fails explicitly; the driver
does not silently switch providers. Follow [GHC library Core](ghc-core.md) to
prepare that installation. Acquisition alone does not establish runtime support.

The opt-in `--installed-core demand` provider defers eligible whole installed
units to the selected-GHC helper on actual module demand. Units with native or
startup obligations retain ordinary CBD acquisition and linking. Thin requested
units fail acquisition. The initial mode requires explicit context process
permission and does not support `--verify-artifacts`; see the
[eligibility and verification limits](driver.md#installed-library-core).

Package C/C++ and CAPI imports use the configured native sources and link
settings. See [foreign imports and exports](interface-foreign.md) for LLVM setup,
buffer and callback contracts, and unsupported forms. Native build products are
still needed for compile-time Haskell even though the final guest runs on THC.

Exports are cached by source, configuration, native products and exporter
identity. Local Core stays under the selected build directory; dependency bundles
use the THC cache. Reusing an unchanged package avoids another export. See
[caching and failures](driver.md#caching-and-failures) for locations and cleanup.

`thc repl` is not implemented. General project acquisition (`thc build`) is
currently available on macOS and Linux. The native Windows simple-package
backend has narrower dependency support; consult [Windows builds](windows.md).
