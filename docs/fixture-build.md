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
`strict-fields`. Each has a `fixture-<group>` target. `make fixtures` requires an
exact test selector; an unmigrated or quarantined selection fails before running
any producer. The older CI preparation path has not yet been migrated.

`CMakeLists.txt` includes the file rules in `cmake/`. Arithmetic exports declare
both the fixture CBD and interface-closure CBD as outputs of the same invocation.
The native executable, generated driver, oracle TSV and manifest have their own
edges. The shared runtime oracle compiles without unused simplifier dumps. Each
Core compilation has its own object directory. No rule consumes an output-directory
listing or needs an empty directory. Ninja's file and command dependencies own
freshness; these rules do not use the old directory-hash receipts as a cache.

The Cabal plan determines executable paths and plugin dependency units. One rule
builds the shared tools, another publishes the plugin library, package cache and
every non-boot registration. Publication records the actual external registrations
and dynamic libraries in a Ninja depfile. The Java synthetic-CBD encoder's path
sidecar also has one writer. Gradle is not invoked by fixture generation.

This is an incremental migration of the [audited flows](fixture-inputs.log).
[Quarantined producers](fixture-quarantine.log) have no graph targets. In particular,
installed-Core/native-package producers still need their acquisition products
represented before admission; neither the pinned-source provider nor complete
installed Core is replaced or disabled by these rules. No claims about those
providers or runtime test results follow from successfully generating fixtures.
