# Development

Use the [pinned GHC and GraalVM toolchain](../README.md) and initialize the
upstream submodules before building. See [Windows](windows.md) for native
PowerShell setup.

## Build and test

| Command | Purpose |
| --- | --- |
| `make` | Build the runtime and Haskell components |
| `make runtime`, `make haskell` | Build either component separately |
| `make test TESTS='thc.RuntimeTest'` | Prepare native/Core fixtures and run one JUnit class |
| `make test-modes TESTS='thc.RuntimeTest'` | Run that class in both handoff modes |
| `make jar` | Rebuild the runtime JAR |
| `make probe ARGS='...'` | Run a runtime diagnostic |
| `make lint-haskell` | Lint tracked Haskell sources |
| `make docs`, `make docs-check` | Build and check the documentation site |

Select an exact nonquarantined class with `TESTS` for focused development.
`make fixtures` builds all admitted fixture files; [quarantined fixtures](fixture-quarantine.log) are excluded
from local and CI selections and from direct Gradle execution. Make and direct Gradle
use Gradle's standard shared dependency cache; an explicit `GRADLE_USER_HOME`
is honored. `make clean` removes Gradle and
Cabal build products; `make distclean` also removes the checkout's `.gradle` and
`.gradle-user-home`. Neither removes an external cache. Use
`GRADLE_FLAGS=--offline` or `CABAL_FLAGS=--offline` for an offline build.

Make and CI select the same explicit [CMake/Ninja file graph](fixture-build.md).
Every admitted group has a file rule. Ninja reuses unchanged inputs.
Exact class and method selectors prepare only their dependencies;
fixture-free classes do not run the Haskell producers. Wildcards and unrecognized
selectors stop before preparation; they cannot fall back to the quarantined corpus. Local preparation disables ambient GHC package environments;
fixture dependencies must be declared by their producers. `GHC` selects the
compiler and its companion package manager (`GHC_PKG` can override the latter).

After preparing fixtures, run selected JVM tests without preparing them again:

```sh
./gradlew --max-workers=4 --continue \
  testDefault --tests 'thc.RuntimeTest' \
  testDense --tests 'thc.RuntimeTest'
```

These tasks share compilation and run one handoff mode at a time, distributing
classes across up to four isolated JVM workers. Add `installDist` when testing
the command-line driver.
Selectors apply to the preceding task. Results are in
`build/test-results/testDefault` and `build/test-results/testDense`, with HTML
under `build/reports/tests`. See [handoff storage](handoff-slabs.md) for the
mode distinction.

Normal CI runs the driver's parser, plan rejection, index, cache, linkage and
subprocess ownership controls without configuring or building guest packages:

```sh
cabal test driver-tests -fdevelopment --test-options=--unit-only --test-show-details=direct
```

Package integration checks retain their focused selectors for explicit runs.
The driver suite's public-package smoke compares Integer, Text, ByteString and
memory operations with native GHC on both backends and in both handoff modes.
Build the runtime before running it directly:

```sh
make runtime
cabal test driver-tests -fdevelopment --test-options=--public-packages-only --test-show-details=direct
```

It prepares pinned Core by default. Set `THC_TEST_INSTALLED_CORE=required` to
require complete installed Core; `THC_INSTALLED_CORE_GHC`,
`THC_INSTALLED_CORE_GHC_PKG` and `THC_INSTALLED_CORE_GHC_SOURCE` select its compiler,
package tool and configured GHC sources when needed.

Package-aware Core audits ask the matching runtime's `CoreForeignOverride.owner`
which static or dynamic operations own THC state, then keep the auditor's ABI, head, operand and
archive-provenance checks. `thc run --verify-artifacts` passes its selected
runtime automatically. A standalone audit can pass `--runtime /path/to/thc`
(or `THC_RUNTIME`). For fixture preparation before an installed distribution
exists, prepare only the Java classifier:

```sh
./gradlew foreignOwnershipCommand
export THC_FOREIGN_OWNERSHIP="$PWD/build/foreign-ownership.json"
```

`bin/audit-core.py --ownership-command` accepts the same command file explicitly.
The CMake graph declares this producer for `weak-explicit` and the two Python
audit checks. Other standalone package-aware producers require the same explicit
preparation. Pure audits do not launch it. Each audit batches its distinct
foreign identities into one cold process without starting a guest context;
reports record the actual runtime class/JAR digest and request/response hashes.
The auditor never builds a missing runtime or acquires libraries.

`bin/try.sh --handoff-modes` compares the Core corpus against native GHC;
`bin/try-libraries.sh` checks library examples. The full-Core foreign-exception lane is quarantined during dependency repair;
`make foreign-exception-test-modes` stops before preparation. Its intended inputs
include complete installed Core and matching configured GHC sources. See [Core compatibility checks](coverage.md)
and [foreign code](interface-foreign.md) for setup and limits.

Track CI performance against its expected duration, separately from the failure
timeout. Set job and command timeouts to roughly two to three times the expected
runtime on that platform, including preparation and ordinary cache variation.
Commit CI should still finish below seven minutes: use recorded timings and
Perfetto traces to catch drift and fix its cause. A timeout is a hang safeguard;
placing it only 10–20% above normal runtime creates noisy failures and wasted
rebuilds.

## Known compiler limitation

Use the pinned GraalVM Community Edition distribution used by CI. A recorded
Linux run on Oracle GraalVM 25.3.4.1 (JDK
`25.0.4.1+1-LTS-jvmci-25.3-b22`) returned the wrong value for a compiled AST loop
that swaps scalars around an empty-tuple argument. Its saved compiler graphs
first lose the required result in the Enterprise `LoopInversionPhase`.
The corresponding regression passes on current Community Edition CI;
that does not establish a fix for the Oracle build. See
[issue #1066](https://github.com/ekmett/thc/issues/1066) for the affected build,
graph evidence and remaining reproducer work.

## Find and change the implementation

[Architecture](architecture.md) describes acquisition, linking and execution.
The main source directories are:

| Directory | Contents |
| --- | --- |
| `src/main/java` | Runtime, AST and bytecode backends, loaders and interop |
| `src/compiler`, `src/driver`, `src/cbd` | Core exporter, Cabal driver and compact format |
| `src/runtime` | Public Haskell runtime APIs |
| `src/test`, `src/fullCoreTest`, `t` | JVM/Haskell tests and native/Core fixture producers |
| `src/tools`, `bin` | Tools and command entry points |
| `src/build`, `src/gradle` | Java build helpers and Gradle configuration |

Use the nearest existing test suite. For runtime changes, check both backends
and handoff modes where applicable. Native GHC supplies independent expected
results; ownership, exception and sharing tests check behavior that a final
value alone cannot establish. [Graph inspection](graph-inspection.md) explains
how to investigate compilation and allocation.

Describe the changed behavior and checks run in the pull request. Keep public
Haskell APIs documented with Haddock and Java APIs with Javadoc; update the
relevant user guide when a capability or limitation changes.

## Haskell lint

Install [HLint 3.10](https://github.com/ndmitchell/hlint/releases/tag/v3.10), or set
`HLINT=/path/to/hlint`. `make lint-haskell` checks tracked `.hs` and `.lhs` sources
with `.hlint.yaml`. Stage new modules to include them. Fixtures, pinned upstream
sources and frozen snapshots are excluded. GHC still checks `.hsc` templates and
`.hs-boot` declarations.

Use `HLINT_FLAGS` for extra options, for example
`make lint-haskell HLINT_FLAGS=--cpp-define=mingw32_HOST_OS`. Local lint exits
nonzero for hints; CI reports style hints and fails on parse/tool errors.

## Generated references

When changing primitive capability metadata, regenerate its references with the
pinned compiler:

```sh
cabal run exe:thc-primops -- scalars --write
cabal run exe:thc-primops -- coverage --write-checklist
cabal run exe:thc-primops -- coverage --check
cabal test primop-tools
```

Do not edit the generated [primop checklist](primops.md) by hand. The
[behavior reference](primop-behavior.md) records operational limits separately.

## Generated instruction metadata

The pinned Truffle processor can produce instruction metadata exceeding the JVM
method-size limit. THC's [build normalizers](../src/build/java/thc/buildlogic/BytecodeNormalizers.java)
split that metadata before compilation and reject unrecognized generated source.
Review them when upgrading Truffle. The [runtime protocol artifacts](../tools/truffle-protocol/README.md)
are separate from upstream Maven artifacts; that reference describes their APIs
and their implementation. The [patch inventory](truffle-patches.md) describes
effects on other languages sharing the runtime and the limitations of
`-Pthc.stockTruffle=true`. Update it whenever a patch changes.

## Continuous integration

Pull requests run [Fast checks](fast-ci.md): compiled smoke tests plus tests
selected from changed files. Main runs the full cross-platform suite. Check the
selected tests and failure logs when CI differs from a local run.
