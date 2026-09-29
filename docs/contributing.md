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
| `make jit-test` | Run advisory compiled-code retention checks |
| `make jar` | Rebuild the runtime JAR |
| `make probe ARGS='...'` | Run a runtime diagnostic |
| `make lint-haskell` | Lint tracked Haskell sources |
| `make docs`, `make docs-check` | Build and check the documentation site |

Omit `TESTS` for the complete JVM test suite. `make clean` removes Gradle and
Cabal build products; `make distclean` also removes the checkout's `.gradle` and
`.gradle-user-home`. Neither removes an external cache. Use
`GRADLE_FLAGS=--offline` or `CABAL_FLAGS=--offline` for an offline build.

After preparing fixtures, run selected JVM tests without preparing them again:

```sh
./gradlew --max-workers=2 --continue \
  testDefault --tests 'thc.RuntimeTest' \
  testDense --tests 'thc.RuntimeTest'
```

These tasks run separate JVMs with explicit default/dense handoff settings and
share compilation. Add `installDist` when testing the command-line driver.
Selectors apply to the preceding task. Results are in
`build/test-results/testDefault` and `build/test-results/testDense`, with HTML
under `build/reports/tests`. See [handoff storage](handoff-slabs.md) for the
mode distinction.

`bin/try.sh --handoff-modes` compares the Core corpus against native GHC;
`bin/try-libraries.sh` checks library examples. Foreign-call changes can also
need `make foreign-exception-test-modes`, which requires complete installed
Core and matching configured GHC sources. See [Core compatibility checks](coverage.md)
and [foreign code](interface-foreign.md) for setup and limits.

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
and the limitations of `-Pthc.stockTruffle=true`.

## Continuous integration

Pull requests run [Fast checks](fast-ci.md): compiled smoke tests plus tests
selected from changed files. Main runs the full cross-platform suite; JIT
stability checks run separately. Check the selected tests and failure logs when
CI differs from a local run.
