# Cabal library driver

`thc plan-package` configures an ordinary Cabal package and prints a JSON
description of its selected components, unit IDs, dependencies, module
declarations and native Cabal output layout. It links `Cabal` and `Cabal-syntax`
directly. There is no subprocess call to the `cabal` command and no replacement
dependency solver.

`thc run` is a bounded executable path. With an explicit `.cabal` file it uses
Cabal's library to configure one package. With a directory containing
`cabal.project` it asks cabal-install to build the selected executable and its
required component closure, then reads the resolved plan and build information.
Both paths export Core through `THC.Plugin`, strictly audit reachable bindings
and invoke the THC JVM runtime; neither runs the native Cabal executable. The
explicit-file and default pinned project providers run a raw `Main.main :: IO ()`
action. The complete installed-Core project provider instead runs GHC's generated
`main::Main.main` and, after normal completion, its original `flushStdHandles`
using the same program and Handle CAFs. Standalone `build` and `repl` commands
remain absent.

IO launchers (`--run-io` and `--run-executable`) do not append runtime metrics to
stderr by default. Guest output and failure reports are unchanged. To append the runtime metrics JSON after a successful action, set
`JAVA_OPTS="${JAVA_OPTS:-} -Dthc.diagnostics=true"` when invoking `thc run`.
Embedded callers can still read the `diagnostics` member directly. This output
setting does not change instrumentation, strict admission, or shutdown behavior.
The low-level integer-kernel launcher retains its diagnostic report.

The driver and JVM launcher accept `--ffi native` or `--ffi managed`. Native is
the default and preserves the current Sulong execution with native-library
access; C bitcode still runs through Sulong. Managed execution is **not supported
by the tested distribution**. The pinned `llvm-community` 25.3.4.1 runtime has no
`llvm.managed` option. A managed request fails with that exact reason before
native providers initialize or an entry executes. If another LLVM installation
exposes the option, THC instead reports its remaining provider/artifact
incompatibility: native file/GMP libraries and the packaged bitcode have not
been ported and verified for managed execution. There is no native fallback.

[GraalVM's LLVM options](https://www.graalvm.org/jdk25.4/reference-manual/llvm/Options/)
describe managed availability and its potentially different LLVM toolchain.
This selector does not claim managed execution merely because a guest buffer
has a managed interop view, and it does not introduce another allocation arena.

This is a runtime-only option on both the single-package and project paths: it
does not change GHC flags, Core export, native build settings, or cache identities.
When the option is omitted, the driver passes no override. The launcher selects
the explicit option first, then `-Dthc.ffiMode`, then `THC_FFI_MODE`, then native.
Values are exactly `native` or `managed`; malformed values fail. Selection is
local to each launch and does not modify environment variables or JVM properties.
The `executionContext` embedding factory follows the same property/environment
defaults; the explicit `NativeIO` factory retains its native policy. Put a guest's
own `--ffi` argument after the literal `--`, where it is preserved without
interpretation. The low-level JVM launcher accepts this selector before its
entry command and also supports `--ffi=native` / `--ffi=managed`.

Pass guest command-line arguments after a literal `--`:

```sh
cabal run thc -- run test/fixtures/run-arguments --exe arguments \
  --thc-root "$PWD" --installed-core required --ghc-source "$GHC_SOURCE" \
  -- "two words" "" "lambda-λ" --help
```

The driver preserves the suffix as separate arguments, including empty strings
and option-looking values. `getProgName` starts with the selected executable's
name (also for `PACKAGE:exe:NAME` selectors); no host JVM arguments leak into the
guest. The original GHC `getArgs`, `getProgName`, `withArgs` and `withProgName`
implementations call the context-owned `getProgArgv`/`setProgArgv` adapter. On the
existing Linux x86_64 native-allocation backend it owns a real NUL-terminated
`char **` vector, including `argv[0]` and the terminal null pointer. Updating the
arguments copies the input strings before retiring the old native image; context
disposal releases the final image. CLI strings are UTF-8 and cannot contain NUL.
This does not provide process-global RTS arguments or `getFullProgArgv`.

The opt-in `cabal test arguments-full-core -ffull-core-tests` compares the actual
ordinary program with native GHC, including nested overrides and exception
restoration. It uses the complete installation variables described below and
reuses the same exported package closure for AST/bytecode and default/dense
runtime runs. Bytecode runs the generated executable entry and shutdown; AST
runs the original raw `Main.main` with explicit flushing. That test retains its
synchronous AST setup; [process signal dispatch](process-signals.md) now supports
AST with `-Dthc.asyncExceptions=true`. The arguments test is not an AST
generated executable-lifecycle claim.
The low-level launchers retain their previous no-argument syntax;
their optional suffix is `-- PROGRAM_NAME ARG...`.

Reproducible [standard application inputs](../examples/standard-apps/README.md)
cover Alex, Happy, HsColour and doctest, distinguishing native baselines from
actual guest execution and reporting original-package closure blockers.
`cabal test library-memory-full-core -ffull-core-tests` exercises the original
`array` freeze/thaw and `bytestring` CString paths exposed by these applications,
using the same full-Core environment and backend/startup distinction above.

## Run real applications

The following Linux x86_64 examples run the original **Happy 2.2.1** parser
generator and **HsColour 1.25** highlighter inside THC. Their generated files
have been compared byte-for-byte with native GHC in both backends and both
handoff modes. These command lines select bytecode, which runs the complete
executable startup/shutdown. The AST checks use the original raw `Main.main`
instead; they are not a general executable-lifecycle claim. See the
[application results and pinned source hashes](../examples/standard-apps/README.md).

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
THC_BACKEND=bytecode "$THC_DRIVER" run "$THC_APPS/happy-2.2.1" \
  --exe happy --thc-root "$THC_ROOT" --dist-dir "$THC_APPS/happy-guest" \
  --with-ghc "$GHC" --with-ghc-pkg "$GHC_PKG" \
  --installed-core required --ghc-source "$GHC_SOURCE" -- \
  -o "$THC_OUTPUT/Parser.hs" "$THC_ROOT/examples/standard-apps/TinyParser.y"

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
  happy -o "$THC_OUTPUT/Parser-again.hs" "$THC_ROOT/examples/standard-apps/TinyParser.y"
```

Keep `happy_lib_datadir` set and retain the manifest's referenced Core bundles.

### HsColour: generate HTML

HsColour needs the checked metadata-only patch listing its existing home
modules. Apply it to the freshly unpacked copy; no Haskell source is changed:

```sh
(cd "$THC_APPS/hscolour-1.25" && \
  git apply "$THC_ROOT/examples/standard-apps/hscolour-1.25-home-modules.patch")
THC_BACKEND=bytecode "$THC_DRIVER" run "$THC_APPS/hscolour-1.25" \
  --exe HsColour --thc-root "$THC_ROOT" --dist-dir "$THC_APPS/hscolour-guest" \
  --with-ghc "$GHC" --with-ghc-pkg "$GHC_PKG" \
  --installed-core required --ghc-source "$GHC_SOURCE" -- \
  -html "-o$THC_OUTPUT/TinyMath.html" "$THC_ROOT/examples/standard-apps/TinyMath.hs"
```

Open `build/real-programs/output/TinyMath.html` to see the highlighted file.
An unchanged HsColour package still has the declared-module inventory limitation;
the patch is explicit, not an automatic source rewrite by THC.

### Alex: generate a lexer

Alex 3.5.4.2 uses its original packaged templates, without an application-source
patch. The full-bytecode workload below passed both handoff modes on public
runtime `981b360c`; the application notes retain the earlier failing checkpoint.

```sh
export alex_datadir="$THC_APPS/alex-3.5.4.2/data"
THC_BACKEND=bytecode "$THC_DRIVER" run "$THC_APPS/alex-3.5.4.2" \
  --exe alex --thc-root "$THC_ROOT" --dist-dir "$THC_APPS/alex-guest" \
  --with-ghc "$GHC" --with-ghc-pkg "$GHC_PKG" \
  --installed-core required --ghc-source "$GHC_SOURCE" -- \
  -o "$THC_OUTPUT/Lexer.hs" "$THC_ROOT/examples/standard-apps/TinyLexer.x"

"$GHC" -O1 -outputdir "$THC_OUTPUT/lexer-objects" \
  "$THC_OUTPUT/Lexer.hs" -o "$THC_OUTPUT/lexer"
"$THC_OUTPUT/lexer"
```

The lexer prints `["sum","+","42"]`. Alex runs in THC; native GHC compiles and
runs the generated lexer. Keep `alex_datadir` set for later guest invocations.

Doctest and Pandoc remain development targets, not demonstrated runnable commands
here. Native baselines, strict Core admission and actual THC execution are
reported separately in the application notes.

## Build and exercise

Use GHC 9.14.1 with its bundled Cabal/Cabal-syntax 3.16. The API bounds are narrow
because Cabal's configuration and symbolic-path interfaces are version specific.
Put that compiler and its matching `ghc-pkg`/`runghc` on `PATH`, together with
cabal-install 3.16. The driver links the Cabal libraries bundled with GHC.

From the repository root:

```sh
cabal build
cabal run thc -- --help
cabal run thc -- plan-package test/fixtures/tiny/tiny-fixture.cabal \
  --dist-dir "$PWD/build/tiny-plan" --enable-tests --enable-benchmarks
```

The same Cabal build produces the `thc` library containing `THC.Plugin` and the
driver executable. `thc run` locates the plugin through Cabal's build metadata.
`make` builds both the JVM launcher and the Haskell components:

```sh
export JAVA_HOME=/path/to/graalvm-jdk-25
make
cabal run thc -- run test/fixtures/run-pure/run-pure.cabal \
  --exe completed --thc-root "$PWD" --dist-dir "$PWD/build/run-package"
cabal test driver-tests --test-show-details=direct
```

The runtime-selection parser and argument-forwarding checks can run without
building or exporting the application fixtures:

```sh
cabal test driver-tests --test-options=--run-options-only --test-show-details=direct
```

After `./gradlew installDist`, `--test-options=--run-ffi-only` also runs the
ordinary single-package fixture through both backends with explicit native
selection and checks that a managed request fails without guest output.
The JVM `FfiModeTest` and `LauncherDiagnosticsTest` cover both IO entry protocols,
mode precedence, unchanged guest arguments, and rejection before entry loading.

`run` requires a real Cabal executable and its `Main.main :: IO ()`. It builds
an explicit `.cabal` component with Cabal's own `LocalBuildInfo`, then invokes
GHC again with its selected source directories, language/CPP/GHC options and
installed package IDs to export Core. The strict `--io-main` audit checks the erased
`State# RealWorld -> (# State#, () #)` boundary and every reachable dependency.
The runtime supplies the zero-width state carrier, executes the action, and
verifies its boxed unit result. The successful fixture performs `newMutVar#`,
`writeMutVar#`, and `readMutVar#`; the integration check also changes its expected
read value and requires a guest failure. Under the default pinned provider,
console IO such as `putStrLn` still fails strict audit. There is no diagnostic
trap fallback or native execution.

This first slice requires an executable without internal library or build-tool
dependencies when using an explicit `.cabal` file. It expects a source `.hs` main
and an installed THC JVM launcher
(`--runtime` overrides `<thc-root>/build/install/thc/bin/thc`). Native Cabal
output is built but never launched by `thc run`. The runtime currently executes
the IO action in the interpreter; it does not claim a compiled guest entry.

For a Cabal project directory, use the same `run` command with an executable
name or a package-qualified `PACKAGE:exe:NAME` selector:

```sh
cabal run thc -- run test/fixtures/run-project \
  --exe app-run:exe:completed --thc-root "$PWD" \
  --dist-dir "$PWD/build/run-project"
```

The project path requires cabal-install 3.16 and GHC 9.14.1. Cabal performs the
normal native build, including preprocessing and compile-time Haskell, for the
selected executable and its dependency closure. Both this build and a cold
store-Core acquisition target that executable, not every sibling application,
test or benchmark in the project. An unrelated unbuildable executable therefore
does not block the selected workload. The driver
reads `plan.json` and Cabal's `--enable-build-info` records, retaining exact
unit IDs and GHC arguments for a separate post-Tidy export of local dependencies.
For Cabal's grouped library records, including Custom Setup packages, the
runtime closure follows `components.lib.depends`. The separate
`components.setup.depends` graph belongs to native Setup execution, not the
guest; it must neither hide transitive library dependencies nor pull host-only
Setup packages into the Core manifest.
It writes one compressed Core ZIP per local component under
`<dist-dir>/native/cache/thc/core-bundles/v1`, inside Cabal's build directory,
and a checked `packages.json` manifest. `cabal clean --builddir <dist-dir>/native`
removes these in-place bundles along with the native build. The OS application
cache (`THC_CACHE_HOME` overrides its location) is reserved for stable package
IDs. Each ZIP includes `manifest.json`, its Core modules, and
`inplace-manifest.json` with hashes of that component's native `.o`/`.hi`
artifacts and dependency build identities. Unchanged native artifacts reuse
the ZIP without another GHC export; a changed artifact or exporter refreshes it.
Local `-inplace` IDs are GHC linking names, so the native artifact hash supplies
the immutable cache identity. Native files from a different build directory may
have different bytes and then correctly produce a new ZIP. The strict audit and
THC loader both consume the manifest. Source-built store dependencies use their
actual Cabal store ID as the ZIP basename in the OS cache, partitioned by GHC
version, ABI and platform. On a missing ZIP, Cabal rebuilds the source package
in a temporary private store. A transparent compiler wrapper first runs native
GHC unchanged, then exports Core with the same Cabal arguments while its unpacked
source still exists. Both compiler wrappers disable the THC driver's own RTS
argument parsing with `--RTS`, preserving the compiler's `+RTS ... -RTS` options
and response-file arguments for native compilation and Core replay. The
replay also preserves Cabal's original debug-info settings: adding `-g` only to
an export can change optimizer-generated binder names and leave consumers of
the native interfaces with missing globals. Source-note export retains whatever
notes those original compiler settings produced; it does not force a different
debug level for either local components or store packages. The
temporary store is removed after ZIP publication;
matching store IDs skip that export on later runs. The fixture has a
data library, a native Template Haskell helper, an internal library, CPP and an
autogenerated Paths module. Its `main` forces an imported definition and uses a
mutable reference; the test compares native execution with both THC backends,
then changes the dependency source and verifies cache invalidation and failure.

Compiler-conditional compatibility libraries and C-only packages can legitimately
contain no Core modules (for example `nats` on modern GHC and `libyaml-clib`).
For these units, the driver checks the registration from the same private Cabal
build: its unit and dependencies must
match, with no exposed/hidden modules or reexports. Cabal can list a C-only
archive in `hs-libraries`; that field alone does not imply a Haskell module.
The empty Core bundle retains the complete registration, including native library
metadata, and revalidates it on cache reads. Referenced foreign calls still pass
the normal strict audit. A missing capture for a library with Haskell modules
still fails; no replacement Core is invented.

A reexport-only store library, such as the top-level `happy-lib` component,
also owns no Core. Its bundle retains the original registration separately as
`reexportRegistration`. The driver accepts only definite reexports, preserves
their exposed names and original provider unit/module identities, and checks
each provider against the resolved dependency closure and captured module
inventory before writing the executable manifest. Renaming an exposed module
does not rename or synthesize its provider's Core. Hidden or ordinary owned
modules still require actual exports. `cabal test driver-tests
--test-options=--store-inventory-only --test-show-details=direct` exercises empty,
C-only and renamed-reexport store dependencies, both backends, and cache reuse.

This is a bounded executable path. Native code remains necessary for Template
Haskell and build tools. THC still rejects unsupported runtime dependencies;
the default pinned provider rejects ordinary `putStrLn`. This path does not
claim general Hackage, C FFI or no-code-only package builds. Project flags
belong in `cabal.project`; the independent `plan-package` and explicit `.cabal`
path keep their existing scope.
Source-built store packages require a Cabal source hash and a successful Core
capture; unsupported build modes fail before producing an incomplete manifest.
Selected `ghc-internal` definitions come from exact, unmodified GHC 9.14.1
sources pinned under `compiler/pinned-ghc-internal`. The driver compiles them
against installed dynamic interfaces in disposable staging under Cabal's build
directory, then caches their post-Tidy Core in one checked ZIP under the OS
cache. Other installed GHC/base units remain dependency identities without
claimed Core exports. This supplies the actual `MonadFail IO`, `IOException`,
Typeable and backtrace definitions, while the strict audit still rejects a
real `catch (fail ...)` entry on unsupported RTS stack-snapshot operations.
The original `.hsc` modules are preprocessed against the installed GHC target
headers only when this ZIP is missing. Its hashed build-input receipt and
module index retain a parsed target-layout summary, including word and stack
frame sizes, InfoProv offsets and closure ordinals. The receipt identifies the
nonprofiling dynamic way separately from the GHC version, ABI and platform.
It is provenance for a future low-level snapshot adapter; source export alone
does not make the original RTS stack primitives executable.
The JVM package loader compares that layout in the ZIP index and hashed build
receipt, checks the host architecture, word size, endianness, nonprofiling way
and field bounds, and carries the installed GHC tables-next-to-code choice into
one immutable target-layout record for either
backend. Bundles without the receipt have no target layout; stack/IPE operations
must reject that absence instead of assuming offsets from a particular host.
Generated-source receipts must name exactly the current seven original HSC
sources, including `Heap/InfoTable/Types.hsc`, with unique paths and well-formed
SHA-256 values. The index and hashed build-input receipts must agree; the old
six-source inventory, missing or additional paths, and duplicates are rejected.

### Installed complete-Core provider

For a GHC 9.14.1 installation whose libraries carry full simplified Core,
project runs can select `--installed-core required`. The default
`--installed-core pinned` retains the limited source provider described above;
these are separate choices, not an implicit fallback after an interface error.
The explicit `.cabal` path does not yet support the installed provider.

An optional `--ghc-source DIR` supplies the matching configured GHC 9.14.1
source tree when original `Conc.Bound`, `System.Posix.Internals`, or Unix's
`System.Posix.Files.PosixString` interfaces lack
THC's typed foreign annotations. It requires `--installed-core required` and
currently supports native x86_64/aarch64 Linux with the original
`_build/stage1/libraries/{ghc-internal,unix}/setup-config`, built interfaces and generated
sources/headers intact. The selected compiler remains the native compiler. Only missing
annotations trigger genuine selected-module compilation into an acquisition-only
cache; no installed compiler, native library or existing ZIP is changed.
Source/interface mismatches and present but invalid provenance fail explicitly.
See [the producer and cache contract](interface-foreign.md#typed-annotations-and-ordinary-acquisition).

On Linux x86_64, this path has run an ordinary `putStrLn` executable through cold preparation
and warm cache reuse, matching native GHC output with a clean strict audit.
The bytecode backend executes GHC's generated `main::Main.main` and then its
original `flushStdHandles` after successful completion, sharing one program's
Handle CAFs. Relative file paths are resolved from the Cabal project directory.
The default `pinned` provider retains the limited raw-IO entry convention.

General file IO is still incomplete. On Linux x86_64 with a complete GHC 9.14.1
installation, the separate `file-lifecycle-full-core` fixture passed native GHC
comparison through the ordinary production driver and bytecode backend. Its
strict audit supplied 72,884 bindings, reached 2,207, and reported zero missing
bindings, issues or unsupported traps. The fixture verified UTF-8 file reads and
writes, append, absolute and end-relative seek, EOF, a caught missing-path
`IOException`, and original shutdown flushing. Its exact output was `file
lifecycle ok` without a newline. This run had `compiledEntries=0`, so it does
not establish a JIT-compiled path. Enable the opt-in test with
`cabal test file-lifecycle-full-core -ffull-core-tests`;
`THC_INSTALLED_CORE_GHC`, `THC_INSTALLED_CORE_GHC_PKG`, and
`THC_INSTALLED_CORE_GHC_SOURCE` select the complete installation and its configured
source tree. The test is not part of the stock-GHC suite.

The separate `binary-buffers-full-core` test uses the same configuration and
ordinary driver to compare `hPutBuf` and `hGetBuf` with native GHC. It checks
offset pointers, NUL and high-bit bytes, short reads, EOF, untouched buffer
boundaries, cleanup after an exception, and original shutdown flushing. Its
strict audit supplied 72,921 bindings, reached 2,217, and reported zero missing
bindings or issues. This bytecode run also had `compiledEntries=0`.
Run it with `cabal test binary-buffers-full-core -ffull-core-tests`.

The driver builds the selected-compiler `thc-interface` helper, discovers exact
pre-existing registrations in the selected global package database, and reads
each declared owned module's dynamic interface. Hidden modules are included;
native-only and reexport-only registrations do not acquire invented bodies.
Reexports must have concrete providers in their dependency closure. Store-source
and local component exports retain their existing paths. Missing complete Core
reports the exact registration/module; wrong identity/way, malformed responses
and process failures remain errors. Foreign stubs/files are retained in
[schema 2](interface-foreign.md), without executing their native C. Supported
managed execution additionally requires verified typed producer evidence;
ordinary unannotated interfaces remain archival.
Nothing substitutes ordinary
unfoldings or adds a second pinned provider beside a wired interface owner.

Generated Core preserves original unit identities, private workers, recursive
groups and CBV evidence. Cabal registration IDs remain dependency/cache keys;
the receipt records their mapping to original wired Core owners. Checked ZIPs
use the existing schema and atomic publication, with registration/DB/compiler/
way/inventory provenance and hashes of generated Core plus THC-owned exporter
code. GHC remains version-gated; compiler executables and installed libraries
are not hashed. An optional batch probe fingerprints GHC's full retained
interface bytes across the registered dependency inventory, including complete
Core, annotations and foreign products. Exact provider membership and current
source-note contents must also agree before a checked ZIP can bypass hydration
and JSON rendering. The driver repeats these observations after archive
validation. Missing or inconsistent evidence falls back to ordinary acquisition;
this is not a command-free cache or an ABI-only freshness claim. Failed refreshes
leave prior complete bundles intact. Source-built package caching is unchanged.

This provider derives its target-layout receipt from the selected GHC and RTS
headers. The checked ZIP stores that receipt in both its index and hashed
build inputs, with the selected RTS registration and layout recipe in its cache
identity. It does not claim the pinned provider's seven HSC preprocessing
products. Complete interfaces still do not establish foreign-export/RTS
support or make ordinary Handle programs runnable.
The existing source-deleted opaque/private/CBV fixture exercises discovery,
process acquisition, cache reuse and strict ZIP admission, followed by native
comparisons in first-installed AST and bytecode targets.

`driver-tests` is an ordinary Cabal HUnit test suite. Cabal builds the driver
first, then the tests copy fixtures into isolated temporary directories outside
this repository's `cabal.project`. Set `THC_TEST_RUNTIME` and
`THC_TEST_THC_ROOT` to use an existing JVM launcher and compiler artifacts from
another checkout for the single-package case. The project case builds the plugin
from a private source-only root, then checks its new manifest. Command output is
retained in `build/driver-haskell-tests`.

The printed evidence directory retains the fresh driver build, `commands.jsonl`
(commands, working directories, stdout/stderr, exit statuses and timeout errors),
and `tests.log`. Bootstrap errors additionally produce `failure.log` and a
nonzero exit status; unavailable or mismatched tools are failures, not skips.
These directories are retained for diagnosis and may be removed when no longer
needed. Passing both `--driver` and `--scratch` keeps the prebuilt-binary workflow
above, with command/test evidence retained under its scratch directory. Supplying
only one of those two arguments is a usage error.

The driver also accepts an explicit `.cabal` path:

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

## Guest environment

The command-line context inherits its host-authorized environment. Original
GHC 9.14.1 POSIX `getenv`, `putenv`, `__hsbase_unsetenv` and `__hscore_environ`
operate on context-owned storage: guest `setEnv` and `unsetEnv` do not change
the JVM process or another THC context. Custom embedding contexts retain
Truffle's environment-access policy and explicit environment overrides.

Initial host strings use the selected Linux UTF-8 filesystem encoding. Subsequent
guest C strings retain their bytes. `putenv` retains its caller's buffer, so
mutating that buffer changes the value returned by `getenv`; the caller must
keep it live. Returned strings and null-terminated pointer vectors obey the
existing owned-native allocation lifetime checks. Environment mutation may
invalidate a previously returned vector. This is the current Linux x86_64
native-allocation path, not Windows environment support or child-process launch.

The original string encoder's `realloc` uses the same owned allocation registry.
Successful resizing preserves the retained byte prefix and retires all old
aliases; allocation failure leaves the original allocation live. Null input is
`malloc`, while nonnull input with size zero follows Linux's free-and-null
contract. Interior pointers, cross-context allocations and a synchronous resize
of a currently borrowed allocation reject rather than bypass lifetime checks.

`cabal test environment-full-core -ffull-core-tests` compares original
`System.Environment` operations with native GHC through the installed-Core
provider. It uses the same `THC_INSTALLED_CORE_GHC`,
`THC_INSTALLED_CORE_GHC_PKG` and `THC_INSTALLED_CORE_GHC_SOURCE` settings as the
other full-Core tests. The JVM controls additionally cover pointer aliasing,
context isolation, ABI rejection and first-installed calls on both backends.

## Original file removal

The original Unix `unlink` import uses the explicitly authorized native file
provider. Paths retain their raw bytes and resolve relative to the context's
working directory; removal follows native symlink and open-file semantics.
Failures expose the native errno, while success leaves the previous errno
unchanged. Invalid or retired path storage rejects before any filesystem effect.
An arbitrary embedding context does not acquire this authority merely by
enabling native access or ordinary Truffle IO access.

`cabal test unlink-full-core -ffull-core-tests` compares ordinary
`System.Directory.removeFile` with native GHC, including a caught missing-file
error, using the same installed-Core settings as the environment test. JVM
controls cover byte-preserving relative names, symlinks, open descriptors,
directory rejection, invalid arguments and both first-installed backend paths.
This bounded operation is not general directory or process support.

## What the plan means

`Distribution.PackageDescription.Parsec.parseGenericPackageDescription` parses
the original package file, including common stanzas. Then
`Distribution.Simple.Configure.configure` elaborates and checks it against the
chosen GHC compiler and that compiler's **global installed package database**.
Cabal resolves installed dependencies and the package's internal libraries,
assigns component/unit IDs, and supplies `LocalBuildInfo`. Missing dependencies
are errors unless Cabal can choose an allowed automatic flag alternative.

This is more than syntactic condition flattening, but it is **not** a solved
cabal-install project/install plan. There is no Hackage index, acquisition,
multi-package project solver, user package database, v2 store, or project
configuration support. In particular, the returned IDs/layout belong to this
Cabal package configuration and must not be mistaken for a future v2 project's
store identities or THC export artifacts.

Directory discovery requires exactly one `.cabal` file. It rejects a
`cabal.project`, `cabal.project.local` or `cabal.project.freeze` in that directory
or any ancestor, rather than dropping its settings. Passing an explicit `.cabal`
file intentionally requests independent package configuration and ignores any
surrounding project. The input package file and sources are never rewritten.

The command does write Cabal's `setup-config` and configuration directories under
`--dist-dir` (default `dist-thc`, relative to the package root). This is a
configuration command, not a filesystem-free dry run. Reusing the directory
replaces its previous Cabal configuration. Select an isolated build directory;
the command does not create or install libraries. Cabal may probe GHC, ghc-pkg
and native toolchain programs as part of configuration.

The single JSON object on stdout has schema `thc.cabal-package-plan.v1`.
Diagnostics go to stderr and failures exit nonzero without emitting a plan.

| Field | Meaning |
| --- | --- |
| `stage` | `cabal-installed-package-configuration` |
| `solvedProjectPlan`, `artifactsBuilt` | Both `false` |
| `cabalVersion`, `compiler`, `platform` | Actual Cabal version and selected compiler/host platform |
| `cabalFile`, `packageRoot`, `package` | Canonical package input and Cabal package ID |
| `flags` | Cabal's final flag assignment |
| `setupConfig` | Persisted Cabal configuration path |
| `components[].name`, `componentId`, `unitId` | Real Cabal identifiers for enabled/buildable components |
| `dependencies` | Cabal-selected library unit IDs and package IDs |
| `internalDependencies`, `toolDependencies` | Cabal's internal and executable dependency unit IDs |
| `declaredDependencies` | Finalized package dependency constraints |
| `sourceDirectories`, `exposedModules`, `otherModules`, `autogenModules`, `virtualModules`, `mainSource` | Finalized source declarations |
| `defaultLanguage`, `defaultExtensions`, `cppOptions`, `ghcOptions` | Selected build settings; not a complete compiler command line |
| `buildDirectory`, `objectDirectory`, `autogenDirectory` | Cabal-computed per-component output roots |
| `plannedArtifact` | Native vanilla library archive or native component executable path |

All relative paths are relative to `packageRoot`; absolute paths remain absolute.
The `components` array is not an execution schedule; use the dependency IDs for
ordering. Module entries are declarations, not a preprocessed source inventory:
this command does not run preprocessors, resolve generated sources, or inspect
Haskell imports. Conventional `.o`/`.hi` paths are under `objectDirectory` using
module-name path components. `mainSource` is searched through `sourceDirectories`
by Cabal. Autogenerated Paths/PackageInfo modules use Cabal's reported autogen
directories. Configuration success alone does not establish source buildability.

The supported component kinds are ordinary libraries/internal libraries,
executables, and `exitcode-stdio-1.0` tests/benchmarks. Only `build-type: Simple`
is accepted; Custom, Configure, Make and Hooks builds are rejected without
executing package Setup code. Foreign libraries, Backpack signatures, module
reexports, and other test/benchmark interfaces are explicit errors. No claim is
made that THC can execute any of the native components described by this plan.

## Verification

`test/fixtures/tiny` is an ordinary unchanged package with a library, internal
library, executable, test and benchmark. Its common stanza, manual flag,
automatic dependency-sensitive flag, OS condition and GHC condition are all
handled by Cabal. Functional tests copy the fixture to a path with spaces,
quotes and Unicode, configure it through `thc`, independently check the installed
base unit ID, and then ask native Cabal to build the **same persisted
configuration**. They check the reported archives, executables, module object
paths and autogen directories against actual files, run the native executable
and test, and verify that all input source hashes stayed unchanged.

Additional cases exercise missing dependencies, unknown flags, disabled
components, ambiguous/malformed/missing packages, project boundaries, custom
Setup rejection, unsupported test interfaces and relative output paths. These
are native Cabal planning checks; their test build is an independent oracle.
The same suite checks the limited raw THC IO action on both backends and
requires unsupported console IO under the pinned provider to fail before launch.

Future work should expand the tested project and runtime dependency closure,
support no-code-only export where installed interface identities permit it, and
expand strict IO capabilities. GHCi and Sulong integration are outside this
package slice.

Source and fixture licensing follow THC's `UPL-1.0 AND BSD-3-Clause` terms; the
repository's license notices are included for standalone package distribution.
