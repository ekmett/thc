# Original containers benchmarks

This probe uses the upstream `containers-tests` benchmark components from the
configured GHC 9.14.1 source tree. It is not THC's Map/Set microbenchmark suite.
The package declares 13 benchmark components. Its own copy of the containers
library is compiled with the original `-DTESTING` option; neither that library
nor the benchmark sources are replaced or edited.

The first target is `containers-tests:bench:intmap-benchmarks`. Its original
`Main` constructs and forces five IntMaps before entering Tasty, even for help
and listing. The configured dependency is `tasty-bench-0.4.1`.

## Prepare the original project

Use the complete-Core GHC, matching configured source tree, Cabal and Graal
toolchain in the [driver guide](../../../../docs/driver.md). Set `THC_ROOT`,
`THC_DRIVER`, `GHC_SOURCE`, `GHC` and `GHC_PKG` as described there. Copy the
upstream project to a fresh writable location, keeping its original Cabal
declarations, Haskell sources and licenses:

```sh
mkdir -p "$THC_ROOT/build"
CONTAINERS_PROJECT=$(mktemp -d "$THC_ROOT/build/containers-project.XXXXXX")
cp -R "$GHC_SOURCE/libraries/containers/." "$CONTAINERS_PROJECT/"
printf 'index-state: 2026-09-24T12:38:18Z\njobs: 2\n' \
  > "$CONTAINERS_PROJECT/cabal.project.local"
CONTAINERS_NATIVE="$THC_ROOT/build/containers-intmap-native"
CONTAINERS_GUEST="$THC_ROOT/build/containers-intmap-guest"
CONTAINERS_TARGET=containers-tests:bench:intmap-benchmarks
```

The extra project-local file selects the recorded package index and job count;
it does not convert benchmarks into executables or modify package bodies.
Retain these directories for subsequent runs.

## Independent native controls

```sh
cabal build "$CONTAINERS_TARGET" --project-dir "$CONTAINERS_PROJECT" \
  --builddir "$CONTAINERS_NATIVE" --with-compiler "$GHC" --with-hc-pkg "$GHC_PKG"
INTMAP_NATIVE=$(cabal list-bin "$CONTAINERS_TARGET" \
  --project-dir "$CONTAINERS_PROJECT" --builddir "$CONTAINERS_NATIVE" \
  --with-compiler "$GHC" --with-hc-pkg "$GHC_PKG")
"$INTMAP_NATIVE" --help
"$INTMAP_NATIVE" --list-tests
"$INTMAP_NATIVE" -p minView --stdev Infinity --timeout 10s \
  --hide-progress --color never --ansi-tricks false
```

`--list-tests` lists 57 original benchmarks. Tasty Bench's `--stdev Infinity`
requests one measurement, so the final command exercises the original
`minView` benchmark without its adaptive repeated-measurement loop. This is a
bounded workload check, not a statistically meaningful performance comparison.

## Acquire and check the guest closure

```sh
"$THC_DRIVER" acquire "$CONTAINERS_TARGET" --project-dir "$CONTAINERS_PROJECT" \
  --thc-root "$THC_ROOT" --dist-dir "$CONTAINERS_GUEST" \
  --with-ghc "$GHC" --with-ghc-pkg "$GHC_PKG" \
  --installed-core required --ghc-source "$GHC_SOURCE"

python3 "$THC_ROOT/bin/audit-core.py" \
  --package-manifest "$CONTAINERS_GUEST/packages.json" \
  --entry main::Main.main \
  --entry ghc-internal:GHC.Internal.TopHandler.flushStdHandles \
  --io-main --output "$CONTAINERS_GUEST/audit.json"
```

Acquisition publishes the actual Core closure but performs no strict audit or
guest execution. The audit checks all syntactically reachable branches, not
just the selected help or benchmark argument. A rejected closure must be
resolved before claiming that the suite launches or runs under THC.

## Recorded checks

On Linux x86-64 with GHC 9.14.1, the unchanged native benchmark passes help,
listing all 57 cases, and the one-measurement `minView` workload (`All 1 tests
passed`). The latter is only a workload smoke, not a useful timing sample.

At driver checkpoint `726de17e`, Cabal successfully resolved and built the
original benchmark through the new positional target interface. Core
acquisition subsequently rejected a safe foreign call with non-scalar inputs
while capturing dependency `text-2.1.4`, after exporting its 55 modules. No
package manifest was published, so that attempt produced neither a strict
audit nor a THC guest execution. This is a dependency-admission failure, not a
benchmark-selector failure.

A second unchanged-project attempt at driver checkpoint `857808c9`, including
the later safe-FFI policy, passed that text capture. It then failed the isolated
build identity check for `binary-0.8.9.3-inplace`: Cabal legitimately builds this
repository dependency in-place because it depends on the project-local
containers library. That attempt also published no package manifest and ran no
THC guest. Supporting this dependency style requires content-sensitive cache
keys for the local dependency closure, not ignoring changed store identities;
see the [driver's source-dependency rules](../../../../docs/driver.md).
