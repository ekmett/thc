# Standard Haskell application smokes

These are ordinary application inputs, not replacement implementations of the
applications. The THC driver compiles and exports each real package, then runs
its accepted Core in the JVM. Native GHC executions below are independent
baselines, never evidence that the application ran under THC.

## Recorded baseline

On Linux x86-64 with GHC 9.14.1, all four pinned native `--version` commands
passed. The real native inputs below also passed: Alex's generated lexer prints
`["sum","+","42"]`, Happy's generated parser prints `3`, HsColour emits HTML,
and doctest reports `Examples: 2  Tried: 2  Errors: 0  Failures: 0`.

At argv checkpoint `bc39038ae3370c8787a57d6cf2fc43bb79ea6513`, the first unchanged
HsColour guest attempt stopped at its undeclared home-module inventory (see the
overlay below). Alex exported 104,454 bindings with 5,092 reachable bindings,
then strict admission reported seven issues and two missing globals. Its
concrete frontier includes original `array` byte-array `memcpy`, `bytestring`
`CSize`-returning `strlen`, `getenv`, and unix `unlink`/foreign-module admission.
Neither result is a guest application success. Later runtime improvements must
be checked by rerunning the actual commands, not inferred from primop counts.

Use the supported GHC 9.14.1 complete-Core installation, matching ghc-pkg,
Cabal 3.16 and Graal toolchain from [the driver guide](../../docs/driver.md).
Keep an absolute `THC_ROOT`, `GHC_SOURCE`, `GHC` and `GHC_PKG` for the commands
below. The root must have built `exe:thc`, `exe:thc-interface`, the plugin library
and `build/install/thc`.

## Source versions

The recorded Cabal index state is `2026-09-24T12:38:18Z`. Package archives have
these SHA-256 digests (Cabal metadata revisions are separately pinned by the
index state):

| Package | Archive SHA-256 |
| --- | --- |
| hscolour-1.25 | `54ce30da55599e872fd38d927aa518369e2971b284acc67ed0caac6ae14cc77c` |
| alex-3.5.4.2 | `df481dc960e2c59a30395f7335031fd4ef8773b8a42894a4f2320e00ff474418` |
| happy-2.2.1 | `67199e97d398403b433be8490440e1ed4c1dc3f03f9de737c512bad1ca9f9e59` |
| doctest-0.25.0.2 | `e61383f35832987af781602c551347262919eb81537d5e7839edbb0fef920013` |

Fetch with `cabal get PACKAGE-VERSION --index-state=2026-09-24T12:38:18Z
--destdir=DIR`. Retain the upstream source
and license files. Put a `cabal.project` in each package directory containing:

```cabal
packages: .
jobs: 4
tests: False
benchmarks: False
index-state: 2026-09-24T12:38:18Z
```

Build the native executable with `cabal build exe:NAME --with-compiler="$GHC"
--with-hc-pkg="$GHC_PKG" -j4`; `cabal list-bin exe:NAME` locates it. Executable
names are `HsColour`, `alex`, `happy`, and `doctest` respectively. The first
guest attempt is:

```sh
"$THC_DRIVER" run "$PACKAGE_DIR" --exe "$EXE" --thc-root "$THC_ROOT" \
  --dist-dir "$THC_ROOT/build/standard-apps/$EXE-guest" \
  --with-ghc "$GHC" --with-ghc-pkg "$GHC_PKG" \
  --installed-core required --ghc-source "$GHC_SOURCE" -- --version
```

The suffix after `--` is the guest command line. Keep the resulting `audit.json`,
`packages.json`, native build receipts and Core ZIPs: a failure before launch is
an export/admission blocker, not an application execution result. General
original executable startup currently uses the bytecode backend. Do not silently
replace it with native execution or a synthetic Core module.

## Real inputs

- `TinyLexer.x`: Alex generates a Haskell lexer. Compile its output with GHC and
  run it; expected stdout is `["sum","+","42"]` followed by a newline.
- `TinyParser.y`: Happy generates a Haskell parser. Compile its output with GHC
  and run it; expected stdout is `3` followed by a newline.
- `TinyMath.hs`: doctest checks two documented examples, and HsColour produces
  HTML syntax highlighting of the same file.

For a source-tree Alex executable, export `alex_datadir="$ALEX_SOURCE/data"`
for both the native and THC commands so its original `Paths_alex` module finds
the actual packaged templates. This is a data-file location, not a replacement
lexer generator. Native commands are:

```sh
"$ALEX" -o "$OUT/Lexer.hs" "$INPUTS/TinyLexer.x"
"$GHC" -O1 -outputdir "$OUT/lexer-objects" "$OUT/Lexer.hs" -o "$OUT/lexer"
"$OUT/lexer"
"$HAPPY" -o "$OUT/Parser.hs" "$INPUTS/TinyParser.y"
"$GHC" -O1 -outputdir "$OUT/parser-objects" "$OUT/Parser.hs" -o "$OUT/parser"
"$OUT/parser"
"$HSCOLOUR" -html "-o$OUT/TinyMath.html" "$INPUTS/TinyMath.hs"
"$DOCTEST" "$INPUTS/TinyMath.hs"
```

For guest workloads, replace each application executable with its `thc run`
command above and replace the `--version` suffix with the identical application
arguments, using separate output paths. Compare generated Haskell/HTML bytes,
then compile and execute both generated lexer/parser sources. This distinguishes
THC running the generator from native GHC running the generated program.
Doctest itself runs child GHC processes: a native child interpreter is expected,
but its parent doctest main must really execute as THC Core before calling the
workload a guest success.

## HsColour metadata overlay

The unchanged HsColour 1.25 executable omits its sixteen automatically discovered
home modules from `other-modules`. GHC builds it with missing-home-module warnings,
but THC's declared-component inventory consequently classifies the undeclared
objects as native products and requires native compiler receipts. Keep this
unmodified-package failure explicit.

For a metadata-only compatibility workaround, apply
`hscolour-1.25-home-modules.patch` to a separate pristine package copy with
`git apply`. It lists exactly those original modules in the executable stanza;
no Haskell source, compiler result, interface or native receipt is changed.
The runtime and driver ownership guards remain unchanged. General admission of
compiler-discovered home modules is a separate driver compatibility follow-up.
