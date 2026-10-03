# Fixture file graph

CMake generates the Ninja graph; Cabal compiles Haskell tools and Gradle builds
and tests the application. CMake 3.24+ and Ninja are required in addition to the
pinned compiler described in the README.

```sh
cmake -S . -B build/fixtures -G Ninja -DGHC=/path/to/ghc-9.14.1
cmake --build build/fixtures --parallel 2 --target fixture-runtime-core-native
make fixtures TESTS=thc.IntegerPrimopsTest
```

The admitted groups are `compact-model`, `signed-narrow-primops`, `bit-primops`,
`integer-primops`, `explicit64-primops`, `runtime-core-native`, `source-core` and
`strict-fields`, `int-arrays`, `int8-arrays`, `int16-arrays`, `int32-arrays`,
`double-arrays`, `float-word-arrays`, `pinned-pointer-cells`, `integer-completion`,
`word-floating`, `tuple-arithmetic`, `scalar-bitcasts`, `floating-remainder`,
`fused-floating`, `small-arrays`, `managed-address-reads`, `wide-char-address`,
`scalar-memory-utilities`, `thread-scheduling`, `thread-label`, `weak-explicit`,
`stable-names`, `explicit64-arrays`, `floating-address`, `floating-byte-offset`,
`atomic-address`, `unaligned-scalar-memory`, `address-array-copy`, `deep-evaluation`,
`mask-functions`, `thread-status`, `uncaught-self`, `live-async`, `vector-api`,
`truffle-strings`, `thread-async`, `native-addresses`, `simd-int64x2`,
`simd-int32x4`, `simd-floatx4`, `simd-doublex2`, `simd-calls`, `simd-floatx4-fma`,
`simd-wide-floating-fma` and `io-main-pap`. Each has a `fixture-<group>` target. `make fixtures` requires an
exact test selector; an unmigrated or quarantined selection fails before running
any producer. The older CI preparation path has not yet been migrated.

`CMakeLists.txt` includes the file rules in `cmake/`. Arithmetic exports declare
both the fixture CBD and interface-closure CBD as outputs of the same invocation.
For the arithmetic families, the native executable, generated driver, oracle TSV
and manifest have their own edges. Each scalar-array family currently uses one
command with all its named outputs; its native compilation and pre/post exports
share the already-built tools. The shared runtime oracle compiles without unused simplifier dumps. Each
Core compilation has its own object directory. No rule consumes an output-directory
listing or needs an empty directory. Ninja's file and command dependencies own
freshness; these rules do not use the old directory-hash receipts as a cache.

The Cabal plan determines executable paths and plugin dependency units. One rule
builds the shared tools, another publishes the plugin library, package cache and
every non-boot registration. Publication records the actual external registrations
and dynamic libraries in a Ninja depfile. The Java synthetic-CBD encoder's path
sidecar also has one writer. Gradle is not invoked by fixture generation. Native/Core compilation uses the
selected global package database. Its interfaces, native libraries, registrations,
compiler settings and available configured compiler/linker executables are file
inputs, so replacing Core under the same compiler version invalidates the graph.
The configured search path is captured, with ambient GHC package environments and
`GHC_PACKAGE_PATH` excluded. The published plugin database is an explicit input.

This is an incremental migration of the [audited flows](fixture-inputs.log).
[Quarantined producers](fixture-quarantine.log) have no graph targets. In particular,
installed-Core/native-package producers still need their acquisition products
represented before admission; neither the pinned-source provider nor complete
installed Core is replaced or disabled by these rules. No claims about those
providers or runtime test results follow from successfully generating fixtures.

Local graph verification (macOS arm64, GHC 9.14.1)

- All 52 admitted groups (89 mapped JUnit classes) built together in 41.02 seconds
  after the shared producer changed, then rebuilt with no work in 0.03 seconds.
  This reused compiled tools and some unchanged fixture files; it is not a clean
  bootstrap timing. Every receipt artifact had a declared graph writer.
- Seven SIMD groups and IO-main PAP generated successfully. On ARM the vector
  fixtures declare pre-Tidy Core only; wide FMA additionally produced 2,112 native
  scalar-lane observations. No ARM native vector parity is implied.

- The first 25 admitted targets (58 mapped JUnit classes) generated successfully, then Ninja reported no
  work. The six array families together took 20.6 seconds including their shared
  tool rebuild (Ninja command log); native comparisons contained 19,175 rows.
- Removing the Integer/Word interface-closure CBD rebuilt both CBD outputs and
  their manifest without rebuilding tools or the native oracle.
- Removing `int16-arrays/literal-oracle.tsv` rebuilt that family's outputs.
  Touching `IntArrayAudit.hs` rebuilt only `int-arrays`; an unrelated file did
  not trigger a build. Quarantined target names were rejected.
- Managed-address reads now passes the exact two CBD paths to the auditor. A
  deliberately malformed extra CBD was ignored while a missing required CBD
  was regenerated; it did not enter the receipt.
- The next nine groups (thread scheduling/labels, weak pointers, stable names and
  five memory families) generated with a deliberately invalid ambient
  `GHC_PACKAGE_PATH`. The graph supplied its selected database. The weak fixture
  also rebuilt with an unrelated malformed CBD present, then returned to no-op.
- Ten more groups generated: address copies, deep evaluation, masking, thread
  status, uncaught/live/async exceptions, string/vector APIs and native pointers.
  The async programs have separate compiler intermediates. No-op checks caught
  and removed a nonexistent native object declaration in the uncaught fixture.
- `python3 bin/test-plugin.py`: 23 checks passed.
  `python3 .github/scripts/test_fast_fixtures.py`: 48 checks passed.

These checks establish local generation and dependency behavior. They do not
establish JVM execution or Linux/Windows results. Native Windows graph support
and migration of the CI entry point remain open. Forty-two indexed groups still
lack rules and are rejected by this entry point; 45 more are quarantined.
