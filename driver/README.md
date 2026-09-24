# Cabal library driver

`thc plan-package` configures an ordinary Cabal package and prints a JSON
description of its selected components, unit IDs, dependencies, module
declarations and native Cabal output layout. It links `Cabal` and `Cabal-syntax`
directly. There is no subprocess call to the `cabal` command and no replacement
dependency solver.

`thc run` is the first executable slice. It asks Cabal's library to configure and
build one selected executable, exports that executable's `Main.main :: IO ()`
through `Thc.Plugin`, rejects unsupported reachable Core through the strict
audit, then invokes the THC JVM runtime. It never executes the native Cabal
binary. The standalone `build` and `repl` commands remain absent.

## Build and exercise

Use GHC 9.14.1 with its bundled Cabal/Cabal-syntax 3.16. The API bounds are narrow
because Cabal's configuration and symbolic-path interfaces are version specific.
Put that compiler and its matching `ghc-pkg`/`runghc` on `PATH`. No Hackage
download or cabal-install executable/library is needed.

From `driver/`:

```sh
runghc Setup.hs configure --builddir=../build/driver-package \
  --package-db=clear --package-db=global
runghc Setup.hs build --builddir=../build/driver-package

../build/driver-package/build/thc/thc plan-package test/fixtures/tiny \
  --dist-dir "$PWD/../build/tiny-plan" --enable-tests --enable-benchmarks

python3 test/test_driver.py \
  --driver ../build/driver-package/build/thc/thc \
  --scratch ../build/driver-tests
```

To exercise `run`, first build the plugin and JVM launcher from the repository
root using the pinned GHC and GraalVM JDK 25:

```sh
export JAVA_HOME=/path/to/graalvm-jdk-25
compiler/build.sh
scripts/gradle.sh installDist --offline
build/driver-package/build/thc/thc run driver/test/fixtures/run-pure \
  --exe completed --thc-root "$PWD" --dist-dir "$PWD/build/run-package"

python3 driver/test/run_integration.py \
  --driver "$PWD/build/driver-package/build/thc/thc" \
  --runtime "$PWD/build/install/thc/bin/thc" \
  --thc-root "$PWD" --scratch "$PWD/build/run-integration"
```

`run` requires a real Cabal executable and its `Main.main :: IO ()`. It builds
that component with Cabal's own `LocalBuildInfo`, then invokes GHC again with
the selected source directories, language/CPP/GHC options and installed package
IDs to export Core. The strict `--io-main` audit checks the erased
`State# RealWorld -> (# State#, () #)` boundary and every reachable dependency.
The runtime supplies the zero-width state carrier, executes the action, and
verifies its boxed unit result. The successful fixture performs `newMutVar#`,
`writeMutVar#`, and `readMutVar#`; the integration check also changes its expected
read value and requires a guest failure. Console IO such as `putStrLn` still
fails strict audit. There is no diagnostic trap fallback or native execution.

This first slice requires an executable without internal library or build-tool
dependencies. It expects a source `.hs` main and an installed THC JVM launcher
(`--runtime` overrides `<thc-root>/build/install/thc/bin/thc`). Native Cabal
output is built but never launched by `thc run`. The runtime currently executes
the IO action in the interpreter; it does not claim a compiled guest entry.

On coordinated development hosts, wrap each configure/build/test command in the
shared `resource_run.py --build-dir /absolute/checkout/build -- COMMAND` gate.
The test command holds one lease while it runs its serial native fixture build;
do not wrap its child commands in another lease.

The Python test entrypoint also works without arguments, as required by the
repository's full Python inventory:

```sh
python3 driver/test/test_driver.py
python3 -O driver/test/test_driver.py
```

Run these from the repository root (or use an absolute script path from any
directory). Each invocation creates a fresh isolated directory beneath
`build/driver-test-bootstrap/`, compiles the ordinary Cabal `Setup.hs`, and uses
it to configure/build the driver before running the same integration tests.
It checks GHC/ghc-pkg **9.14.1** and the bundled global Cabal/Cabal-syntax
**3.16.0.0**, honors `GHC` and `GHC_PKG` executable overrides, and excludes user
package databases and ambient GHC package environments. The fixture uses the
same selected compiler and compiled Setup executable. There is no download,
project solver, source mutation or nested resource gate; CI/the caller owns the
enclosing build lease. `unittest` assertions remain active under Python `-O`.

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
`run_integration.py` separately checks THC IO execution on both backends and
requires unsupported console IO to fail before launch.

Future work should use cabal-install's project APIs for genuine project solving,
support internal package libraries in the export graph, and expand strict IO
capabilities. GHCi and Sulong integration are outside this package slice.

Source and fixture licensing follow THC's `UPL-1.0 AND BSD-3-Clause` terms; the
repository's license notices are included for standalone package distribution.
