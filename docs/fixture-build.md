# Fixture file graph

CMake generates the Ninja fixture graph. Cabal compiles the Haskell tools;
Gradle builds and tests the application. Use CMake 3.24+, Ninja and the pinned
compiler described in the README.

```sh
make fixtures                               # all admitted fixture files
make fixtures TESTS=thc.IntegerPrimopsTest   # only this class's dependencies
make test-modes TESTS=thc.IntegerPrimopsTest # then execute both handoff modes
```

The equivalent direct entry points are:

```sh
cmake -S . -B build/fixtures -G Ninja -DGHC=/path/to/ghc-9.14.1
cmake --build build/fixtures --parallel 2
cmake --build build/fixtures --parallel 2 --target fixture-integer-primops
```

[CMakeLists.txt](../CMakeLists.txt) includes the rules in `cmake/`. Every admitted
group has a `fixture-<group>` target and names its consuming tests.
The `fixtures` aggregate is the default build target. The selection inventory is
[fast-fixtures.json](../.github/scripts/fast-fixtures.json); configure rejects an
admitted group without a rule or a quarantined group with one. Make and CI use
this same graph. Exact class and method selectors build only their dependencies;
fixture-free classes invoke no fixture tools. Unknown selectors and wildcards
fail before generation. The [quarantined groups](fixture-quarantine.log) have
no targets and are excluded from test execution, including direct Gradle runs.

Each file has one producer. Commands that generate related CBDs, interfaces,
native oracles and audit reports declare multiple named `OUTPUTS` and compilation
`BYPRODUCTS`. Arithmetic families split native compilation, Core export, oracle
execution and manifest construction into separate edges. Each compilation owns
its object directory. Dependencies are source files, tools and named generated
files. No rule needs an empty directory or consumes a generated-directory listing.
Ninja's file and command dependencies determine freshness; missing products rerun
their owner. The retired directory-receipt preparation path is removed.

The inventory marks `signed-narrow-primops`, `bit-primops`, `integer-primops` and
`explicit64-primops` with `nativeOracle: independent-ghc`. Their native executable,
object/interface files and expected output have a separate cache under
`THC_NATIVE_ORACLE_CACHE` (default `~/.cache/thc-native-oracles`). Keys include the
fixture and generated driver bytes, producer recipe, pinned GHC and global package
identity, platform and relevant environment. Exporter and Java changes do not
invalidate these baselines. Cache restoration verifies every output digest; THC
execution still runs. Other groups that produce native and Core files in one
operation are not yet eligible for this independent cache.

The Cabal plan determines tool paths and plugin dependency units. One rule builds
the shared tools and owns their Cabal plan and package cache. Another publishes
the plugin, its private package database and registrations, with external
registrations and dynamic libraries recorded in a depfile. Missing Cabal products
invalidate only that component's build/registration memo before Cabal relinks.
Every promised product must exist before the command succeeds. The synthetic-CBD
encoder's `build/thc-fixtures.path` has one writer and explicit consumer edges.
`fixture-tools` builds these shared prerequisites without generating the corpus;
CI shares them only within the same source, workspace, platform and workflow attempt.

Native/Core compilation uses the selected global package database. Its interfaces,
libraries, headers, registrations, settings and configured compiler/linker tools
are file inputs, including retained Core replaced under the same GHC version.
The configured PATH is captured; ambient GHC package environments are excluded.
Gradle is not invoked by fixture generation. Acquisition fixtures still in
quarantine need explicit package/archive products before admission. The production
pinned-source and installed-Core providers remain available; fixture generation
does not establish runtime support for either provider.

`native-open-request` compiles its existing Haskell observer and THC C bridge
with the installed GHC packages on Linux x86_64 and Darwin x86_64/arm64. It needs
no Core export or fixture-tool build. `NativeFileProviderTest` executes the child
fresh, with private scratch and a timeout, to contain process-global signal
controls. The proof covers native request ownership and cancellation, not Core
wrapper admission or installed guest execution.

Verification on macOS arm64, GHC 9.14.1 and GraalVM 25.3.4.1 / JDK 25:

- `make fixtures` completed the admitted graph in 1m40s with existing compiled
  tools and some fixture products. This is not a cold toolchain build measurement.
- Its immediate repeat generated nothing in 6.7s, including configuration.
  Direct `cmake --build build/fixtures --parallel 2` did no work in 0.49s.
- All 3,112 declared products existed; 1,758 consumed artifact hashes across
  76 manifests/provenance files matched products with declared graph writers.
- Ninja checked 8,083 graph nodes and reported no missing generated-file dependencies.
- `make test-modes TESTS=thc.IntegerPrimopsTest` passed all six executions
  across both handoff modes and both backends in 40.5s (Gradle: 33s).
- Focused recovery checks covered missing CBD siblings, native oracle files,
  the encoder sidecar and the interface-reader executable. Each rebuilt through
  its owner and returned to no work. Unrelated files were preserved and ignored.

Focused runtime checks exercised changed consumers in both backends and handoff
modes; they do not establish that every mapped class passes. Linux generation and
execution of the newly migrated rules remain unverified here. Native Windows graph
support is not implemented. Linux-only fixtures emit an unsupported receipt on
macOS and their consumers skip. These results do not claim green CI.
