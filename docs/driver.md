# Build and run Cabal programs

THC uses Cabal to build a selected component, acquires its optimized GHC Core,
and executes it on the JVM. Native GHC still runs Setup programs, preprocessors
and Template Haskell. THC does not launch the application's native executable.

## Build the tools

Use GHC 9.14.1, its matching `ghc-pkg` and `runghc`, cabal-install 3.16, and the
pinned GraalVM from the [README](../README.md). From a configured THC checkout:

```sh
make
cabal run thc -- --help
cabal run thc -- run completed --project-dir t/fixtures/run-pure \
  --thc-root "$PWD" --dist-dir "$PWD/build/run-package"
```

The included example returns `()` without printing. It works with the limited
installed-library provider. Ordinary console and file applications need the
complete-Core setup below.

## Choose a target and pass arguments

```sh
thc run my-package:exe:my-program --thc-root /absolute/path/to/thc -- --help
thc acquire my-package:exe:my-program --thc-root /absolute/path/to/thc
```

`run` accepts executables, `exitcode-stdio-1.0` test suites and benchmarks.
Use `PACKAGE:exe:NAME`, `PACKAGE:test:NAME`, `PACKAGE:bench:NAME`, or a shorter
unambiguous Cabal target. With no target, Cabal selects the current package's
sole buildable executable, otherwise its sole runnable component. Disabled,
missing and ambiguous targets fail explicitly.

| Option | Purpose |
| --- | --- |
| `--project-dir DIR`, `--project-file FILE` | Select the application's Cabal project. Otherwise use the current directory. |
| `--thc-root DIR` | Required path to the built THC checkout. |
| `--dist-dir DIR` | Select the THC/Cabal build and publication directory. |
| `--with-ghc PATH`, `--with-ghc-pkg PATH` | Select the matching compiler and package tool. |
| `--installed-core required` | Acquire complete executable Core from the selected installation. |
| `--ghc-source DIR` | Supply the matching configured GHC source tree for required foreign annotations. |
| `--verify-artifacts` | On `run`, audit the reachable Core before launch and verify artifact hashes. |
| `--runtime PATH` | Override `<thc-root>/build/install/thc/bin/thc`. |

`thc run TARGET --help` shows driver help without building. Put guest arguments
after the literal `--`; `thc run TARGET ... -- --help` asks the guest for help.
Arguments retain their boundaries, empty strings and option-looking values.
`getProgName` starts with the selected component's bare name.

`acquire` uses the same target and build options, then atomically publishes
`DIST/packages.json`. It does not audit or execute the guest. Runtime-only
options (`--runtime`, `--verify-artifacts`) and guest arguments are rejected. An existing
`audit.json` is not refreshed by acquisition or by a run without
`--verify-artifacts`.

## Installed library Core

The default `--installed-core pinned` provider supplies a limited boot-library
subset. It is enough for small examples, not ordinary `putStrLn` or general
application closures. Select `--installed-core required` for an installation
built with complete simplified Core; thin interfaces fail without a fallback.
See [GHC library Core](ghc-core.md) to check or build that installation.

For the supported native x86_64/aarch64 Linux setup, `--ghc-source DIR` supplies
the matching configured GHC 9.14.1 stage1 tree when selected `ghc-internal` or
`unix` modules lack typed foreign annotations. Keep its built interfaces,
Cabal configuration, generated sources and headers intact. Mismatches fail
explicitly. The [foreign guide](interface-foreign.md#acquire-installed-foreign-declarations)
explains this requirement. The Windows simple-package backend does not support
this complete-interface provider.

Cold hydration uses at most two helper processes. `THC_INSTALLED_CORE_JOBS`
selects a bound from 1 to 64; higher values need more CPU and memory.

## Runtime selection and host resources

Bytecode is the default backend; `THC_BACKEND=ast` selects AST. Full executable
launches enable asynchronous exceptions on both backends. Set
`JAVA_OPTS="${JAVA_OPTS:-} -Dthc.asyncExceptions=false"` only when synchronous
execution is intended; programs installing process signal handlers require
async mode. Raw IO and embedding defaults are described in
[asynchronous exceptions](async-exceptions.md).

The complete installed-Core provider runs GHC's generated `main::Main.main`
and, on normal completion, `flushStdHandles` in the same program. The limited
pinned provider runs the raw IO action. Relative guest file paths use the launch
working directory; `--project-dir` does not change it.

The command-line context grants its native filesystem and standard streams.
Guest environment changes and working-directory changes belong to the context,
not the JVM process. [Process creation](process-lifecycle.md) has its own
inheritance and platform contract. Custom embeddings choose their own authority;
ordinary native access alone does not install the managed file provider.
See [native files](native-file-provider.md) and [process signals](process-signals.md).

Package C/C++ uses [declared native linkage](interface-foreign.md).
Haskell foreign calls use native-enabled Sulong. Native Haskell context factories
reject an inherited `polyglot.llvm.managed` setting other than absent or exact
`false` before initializing native providers. Other LLVM options retain their
ordinary engine validation.

The driver also supplies the genuine [foreign-exception bridge](foreign-exceptions.md),
reusing the application's runtime unit when present. Ordinary Haskell `catch`,
`finally` and `bracket` can then handle eligible foreign failures. Missing or
ambiguous bridge identity is a link error; raw embedding must supply that support.

To append runtime metrics after a successful IO launch, set
`JAVA_OPTS="${JAVA_OPTS:-} -Dthc.diagnostics=true"`. Normal guest stderr contains
no metrics report. Embedded callers can read the `diagnostics` member directly.

## Caching and failures

The driver exports only the selected component's dependency closure. Local
component bundles live under `<dist-dir>/native/cache/thc/core-bundles/v1`.
Dependency bundles use the OS application cache, overridden by `THC_CACHE_HOME`.
The published manifest selects directly seekable unit artifacts; see
[Core packages](core-package-manifest.md) for their format and explicit embedding.

Cache keys include sources, compiler/exporter configuration, native products and
dependency identities. Native packages also track LLVM tools and headers.
Changing those inputs triggers acquisition again. `--verify-artifacts` bypasses
selection shortcuts and checks source archives and published hashes. Do not edit
cache metadata to bypass a mismatch.

Missing store exports are captured in a private Cabal build while their sources
and generated headers exist. Successful publication removes its temporary
staging. A failed capture or publication retains the staging directory and prints
its path for diagnosis. Preserve it when reporting the failure; remove it when
no longer needed. Failed refreshes leave earlier complete bundles intact.

For an explicit retained-stage handoff, `THC_CAPTURED_STORE_BUNDLES` may name an
absolute JSON file with `format: "thc-captured-store-bundles"`, `schema: 1`,
`request`, and `bundles`. The driver writes the current compiler, dependency-plan
and local/native input snapshot to `<file>.request.json`; the supplied `request`
must match it. Each bundle names its `unit`, absolute `path`, `sha256`, and
original `buildKey`/`exportKey`. The inventory must cover every requested store
unit exactly once. Selection uses the existing archive validator and successful
selection receipts; `--verify-artifacts` still forces full validation. This
explicit reuse does not relabel old artifacts with current producer keys, and a
missing or mismatched bundle fails rather than starting another store capture.
An optional `installed` array selects already-acquired boot-library bundles.
Each row names the registered `unit`, absolute bundle `path`, and absolute `probe`
path to its original successful installed-probe receipt. Every requested installed
unit must appear once; compiler, dependency and module/reexport inventories must
match. The original producer inputs and bundle digest are preserved and checked
by the normal installed archive reader. This explicitly selects old artifacts;
it does not assert that they were generated by the current helper or revalidate
their original source tree. Omitting `installed` retains ordinary acquisition.
Missing local exports still use their normal export path.

`cabal clean --builddir <dist-dir>/native` removes the corresponding local build
and bundles.

| Failure | Next step |
| --- | --- |
| Missing complete Core | Select a complete-Core installation; ordinary unfoldings cannot replace missing bodies. |
| Unsupported reachable operation | Consult [runtime limits](primop-behavior.md) and the named operation. Acquisition success is not execution support. |
| Missing native library or rejected pointer | Check [foreign setup and diagnostics](interface-foreign.md#diagnose-a-rejected-import). |
| Source, interface or header mismatch | Rebuild/reacquire with one matching compiler configuration. |
| Ambiguous target | Use the fully qualified Cabal component name. |

## Run real applications

These Linux x86_64 examples run **Happy 2.2.1**, **HsColour 1.25** and
**Alex 3.5.4.2** inside THC using bytecode and the executable startup/shutdown
protocol. The commands demonstrate these workloads; other applications may
reach unsupported operations. See the [example collection](../src/examples/standard-apps/README.md).

Start in a built THC checkout with the complete-Core GHC 9.14.1 installation
and matching `ghc-pkg` on `PATH`. Set `GHC_SOURCE` to its matching configured
source tree as described in [GHC library Core](ghc-core.md). These are ordinary
project-directory runs; the default partial installed-library provider is not
enough for these programs.

```sh
export THC_ROOT="$PWD"
export GHC="$(command -v ghc)"
export GHC_PKG="$(command -v ghc-pkg)"
export GHC_SOURCE=/absolute/path/to/configured/ghc-9.14.1
THC_DRIVER=$(cabal list-bin exe:thc)
THC_APPS="$THC_ROOT/build/real-programs"
THC_OUTPUT="$THC_APPS/output"
mkdir -p "$THC_OUTPUT"

cabal get happy-2.2.1 happy-lib-2.2.1 alex-3.5.4.2 hscolour-1.25 \
  --index-state=2026-09-24T12:38:18Z --destdir="$THC_APPS"
for package in happy-2.2.1 alex-3.5.4.2 hscolour-1.25; do
  printf '%s\n' 'packages: .' 'tests: False' 'benchmarks: False' \
    'index-state: 2026-09-24T12:38:18Z' > "$THC_APPS/$package/cabal.project"
done
```

Run that source preparation once in a fresh directory; keep the downloaded
licenses and the generated build/cache directories for subsequent runs.

### Happy: generate a parser

Point Happy at its original packaged templates, then run its real command line:

```sh
export happy_lib_datadir="$THC_APPS/happy-lib-2.2.1/data"
THC_BACKEND=bytecode "$THC_DRIVER" run happy --project-dir "$THC_APPS/happy-2.2.1" \
  --thc-root "$THC_ROOT" --dist-dir "$THC_APPS/happy-guest" \
  --with-ghc "$GHC" --with-ghc-pkg "$GHC_PKG" \
  --installed-core required --ghc-source "$GHC_SOURCE" -- \
  -o "$THC_OUTPUT/Parser.hs" "$THC_ROOT/src/examples/standard-apps/TinyParser.y"

"$GHC" -O1 -outputdir "$THC_OUTPUT/parser-objects" \
  "$THC_OUTPUT/Parser.hs" -o "$THC_OUTPUT/parser"
"$THC_OUTPUT/parser"
```

The parser prints `3`. Happy itself runs in THC; the last two commands use
native GHC to compile and run the Haskell source Happy generated. To check its
version, use the same `thc run` command with `-- --version` as its suffix.

Once acquired, its exact Core manifest can also be launched directly without
invoking Cabal or the exporter again:

```sh
THC_BACKEND=bytecode "$THC_ROOT/build/install/thc/bin/thc" \
  --run-executable "@$THC_APPS/happy-guest/packages.json" \
  main::Main.main ghc-internal:GHC.Internal.TopHandler.flushStdHandles -- \
  happy -o "$THC_OUTPUT/Parser-again.hs" "$THC_ROOT/src/examples/standard-apps/TinyParser.y"
```

Keep `happy_lib_datadir` set and retain the manifest's referenced Core bundles.

For the AST startup/shutdown path, use the same generated main and shutdown
entries:

```sh
THC_BACKEND=ast \
  "$THC_ROOT/build/install/thc/bin/thc" \
  --run-executable "@$THC_APPS/happy-guest/packages.json" \
  main::Main.main ghc-internal:GHC.Internal.TopHandler.flushStdHandles -- \
  happy -o "$THC_OUTPUT/Parser-ast.hs" "$THC_ROOT/src/examples/standard-apps/TinyParser.y"
```

The same environment selection works for the HsColour command below.
Keep the Linux launcher's `-Xrs` setting: original GHC startup installs its
[process signal handlers](process-signals.md), under the same explicit
launcher authority on either backend.

### HsColour: generate HTML

Use the unchanged HsColour 1.25 package. GHC discovers its home imports even
though the executable's Cabal stanza omits `other-modules`; THC verifies those
modules against the selected compiler's actual component-owned interfaces.

```sh
THC_BACKEND=bytecode "$THC_DRIVER" run HsColour --project-dir "$THC_APPS/hscolour-1.25" \
  --thc-root "$THC_ROOT" --dist-dir "$THC_APPS/hscolour-guest" \
  --with-ghc "$GHC" --with-ghc-pkg "$GHC_PKG" \
  --installed-core required --ghc-source "$GHC_SOURCE" -- \
  -html "-o$THC_OUTPUT/TinyMath.html" "$THC_ROOT/src/examples/standard-apps/TinyMath.hs"
```

Open `build/real-programs/output/TinyMath.html` to see the highlighted file.
No package metadata overlay or source rewrite is required.

### Alex: generate a lexer

Alex uses its packaged templates. Set `alex_datadir` before launching it:

```sh
export alex_datadir="$THC_APPS/alex-3.5.4.2/data"
THC_BACKEND=bytecode "$THC_DRIVER" run alex --project-dir "$THC_APPS/alex-3.5.4.2" \
  --thc-root "$THC_ROOT" --dist-dir "$THC_APPS/alex-guest" \
  --with-ghc "$GHC" --with-ghc-pkg "$GHC_PKG" \
  --installed-core required --ghc-source "$GHC_SOURCE" -- \
  -o "$THC_OUTPUT/Lexer.hs" "$THC_ROOT/src/examples/standard-apps/TinyLexer.x"

"$GHC" -O1 -outputdir "$THC_OUTPUT/lexer-objects" \
  "$THC_OUTPUT/Lexer.hs" -o "$THC_OUTPUT/lexer"
"$THC_OUTPUT/lexer"
```

The lexer prints `["sum","+","42"]`. Alex runs in THC; native GHC compiles and
runs the generated lexer. Keep `alex_datadir` set for later guest invocations.

Doctest and Pandoc remain development targets, not demonstrated runnable commands
here. Native baselines, strict Core admission and actual THC execution are
reported separately in the application notes.

For library-based examples, see [lens](../src/examples/standard-apps/lens/README.md),
[automatic differentiation](../src/examples/standard-apps/ad/README.md), and
[the GHC API](../src/examples/standard-apps/ghc-api/README.md).

## Inspect a package configuration

`plan-package` configures one package against the selected compiler’s global
installed package database and prints a JSON description. It does not solve a
cabal-install project, acquire dependencies, build components or run guest code:

```sh
thc plan-package path/to/example.cabal --flag fast --flag=-debug
thc plan-package path/to/package --with-ghc /toolchain/bin/ghc \
  --with-ghc-pkg /toolchain/bin/ghc-pkg
```

Tests and benchmarks are off by default. `--enable-tests` and
`--enable-benchmarks` enable their corresponding component classes. Cabal selects
default/manual/automatic flags and evaluates `flag`, `os`, `arch` and `impl`
conditions. Unknown explicit flag names are errors. Repeated flag settings use
the last value, following Cabal's flag-assignment behavior.

Use an explicit `.cabal` path for independent configuration. Directory discovery
requires one package file and rejects surrounding project files rather than
ignoring their settings. The command writes `setup-config` under `--dist-dir`
(default `dist-thc`), replacing any previous configuration there.

Only `build-type: Simple` is supported. Foreign libraries, Backpack signatures,
module reexports and non-exitcode test interfaces are rejected. The JSON reports
actual component/unit IDs, dependencies, flags, source declarations and native
output paths. Relative paths use `packageRoot`; component order is not a build
schedule. Configuration success does not establish source buildability.
