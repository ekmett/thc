# Development

## Implementation style

Follow the [agent and contributor guidance](../AGENTS.md). Keep simple primop
behavior in its Java JVM implementation, and inline forwarding-only
helpers instead of adding wrapper layers or temporary carrier objects. Retain
real compiler/ABI boundaries and ownership, lifetime, and synchronization rules;
verify allocation and call elimination rather than inferring it from source size.
Trust GHC's type checking: use lowered scalar carrier types where they suffice,
without redundant integral `RuntimeRep` identity checks. Preserve meaningful
carrier, aggregate, ABI, ownership and memory-safety distinctions.

Runtime code, generated nodes, fixtures, tests and tools use Java; Gradle uses
Groovy and Java build logic. Do not reintroduce a Kotlin compiler or runtime
dependency. The shared state/void carrier is the unique `thc.runtime.Unit.INSTANCE`.
Preserve Truffle child annotations, typed execution paths, cold error boundaries
and first-compiled-call checks during conversion. Source translation alone does
not establish a performance improvement.

Use Haskell for GHC-facing fixture generation and native-oracle tooling, and
Java for JVM checks and independent runtime models. Introduce Python only for
a concrete Python-specific need. Migrations must preserve native comparisons,
negative controls, strict Core audits, provenance, and all active CI callers.

## Haskell lint

Install [HLint 3.10](https://github.com/ndmitchell/hlint/releases/tag/v3.10), then
run `make lint-haskell` (or set `HLINT=/path/to/hlint`). It checks tracked `.hs`
and `.lhs` sources, including tools, examples and unit tests, using `.hlint.yaml`.
Fixture sources and producers (`compiler/test-fixtures`, `test/fixtures`, and
`test/haskell-fixtures`), vendored compiler sources and frozen benchmark snapshots
are excluded. Build products and untracked files are not traversed. Stage new
modules to include them.
HLint does not preprocess `.hsc` templates or check `.hs-boot` declarations;
their native GHC build remains the check for those files.

`HLINT_FLAGS` accepts ordinary HLint options, for example
`make lint-haskell HLINT_FLAGS='--report=build/hlint.html'` after creating `build`.
To inspect Windows CPP branches, also run
`make lint-haskell HLINT_FLAGS=--cpp-define=mingw32_HOST_OS`.

The command exits nonzero for hints. The Haskell lint workflow retains JSON
reports for both CPP profiles: parse/tool errors fail the job, while style
hints are advisory and remain visible. Review useful suggestions individually.
Keep suppressions narrow and explain them rather than hiding all existing hints.

## Build and test

Use proportionate verification for a compiler/JIT with reasonable GHC semantics:
straightforward implementations and ordinary regression/boundary tests for simple
operations; deeper checks for actual failures, concurrency and memory safety.
Formal equivalence or exhaustive testing is not the completion criterion. Shared
representation choices (including Sulong pointers) do not make operations partial,
and unrelated runtime gaps must not hold up a working feature batch.

For native Windows, use the [PowerShell build and test guide](windows.md).
It records the pinned tools, tested runtime/exporter slice, and remaining platform limits.

`make` builds the runtime and Haskell components. `make test` prepares native
fixtures and runs the JVM tests; `make test TESTS='thc.RuntimeTest'` selects one
JUnit class. `make test-modes` prepares fixtures once and runs separate default
and dense JVMs from one compilation; `TESTS` selects the same class in each.
`make jit-test` runs the separate advisory JIT retention suite.
`make jar` rebuilds only the runtime JAR, and `make probe ARGS='...'` invokes
the diagnostic runner. `make clean` removes the Gradle and Cabal build products;
`make distclean` also removes `.gradle`, `.kotlin`, and `.gradle-user-home` in the
checkout. Neither cleanup target requires a JDK or removes an external cache.
Use `GRADLE_FLAGS=--offline` or `CABAL_FLAGS=--offline` for an offline build.

Batch related primops, fixture changes and proofs into substantial tested
commits. Rebuild and run focused tests locally whenever useful during development;
workers should own substantial chunks without repeated per-operation handoffs.
For an integration checkpoint, build the source batch once and run both handoff
modes against those artifacts. Group integration
and pushes so the same source batch does not repeatedly trigger builds and CI.
There is no required commit count; preserve the complete checks and failed evidence.

Use a descriptive branch name, such as `pinned-arrays` or `fast-ci`, and a focused
pull request against `main`. Explain the problem, the change and how you checked
it. Keep issues about the work to do; PRs carry the implementation discussion
and merge status.

Publish tested substantive worker checkpoints promptly on their owned branches
so contributors on other machines can use them. Designated integration owners
on any host may manually merge reviewed, tested commits onto the latest published
main and push a normal fast-forward. Never force-push or change protections.
Record the exact tested revisions and dependencies, and reuse worker test
evidence: only substantive merge conflicts need focused retests, not unchanged
fixture generation or test suites. There is no all-worker, GitHub Actions, or
full-matrix publication barrier. GitHub Actions results are informative, not
publication gates. Broader regression runs continue in the
background on immutable revisions; address failures promptly. Keep the coverage
counts and generated documentation tied to each published tree, and report the
checks actually performed. The retained merge-bot implementation and its
`auto-merge` label are not the current integration workflow.

After preparing the affected native/Core fixtures, combine installation and
both mode tasks in one invocation. Gradle shares Java source processing and compilation,
native compilation and ABI probes across these tasks:

```sh
./gradlew --max-workers=2 --continue installDist \
  testDefault --tests 'thc.runtime.HandoffTest' --tests 'thc.RuntimeTest' \
  testDense --tests 'thc.runtime.HandoffTest' --tests 'thc.RuntimeTest'
```

The named tasks always run fresh tests and explicitly set their fork's
`thc.handoffSlabs` property. The dedicated
`HandoffTest.requestedModeReachesTestProcessAndContext` check observes that property
and the runtime context without changing the selected mode. Other focused
handoff tests deliberately enable the protocol within a scoped helper and
restore the previous property; see the [handoff guide](handoff-slabs.md).
XML and HTML remain separate under
`build/test-results/testDefault`, `build/test-results/testDense` and the matching
`build/reports/tests` directories. `--tests` and `--rerun` apply to the preceding
task: select only `testDense` to repeat that mode, or use `testHandoffModes
--continue` for both complete inventories. Avoid `--rerun-tasks`, which forces
compilation dependencies to run again. Existing `test` and its
`JAVA_TOOL_OPTIONS` selection remain supported.

For first-compiled-call checks, a valid guest target is not by itself sufficient:
HotSpot can retire Truffle's shared call-entry stub while retaining the guest
code. Private compilation setup must restore that prerequisite, as the public
`EntryValue.compile` path does, without executing a settling guest call. The
original-Core continuation and mask tests include forced-stub-retirement controls;
keep their first-effect and no-replay assertions intact.

Root control policy is also compilation metadata. `GuestRoot.prepareForCall`
fixes the delimited-continuation flag before Truffle publishes the call target;
guest code reads that flag instead of resolving concrete root types in a cold
handler during partial evaluation. This avoids a first-compilation class-hierarchy
bailout without executing guest code, retrying compilation, or preloading unrelated
classes. Preserve the fresh floating-tuple compilation check and the metadata,
clone and continuation-policy controls when changing root initialization.

### Generated instruction metadata

The pinned Truffle 25.3.4.1 processor emits one large
`BytecodeRootGen.Instructions.getArguments` method. Our operation inventory can
exceed the JVM's 64 KiB method limit there. This is instruction introspection,
not one giant primop executor. The processor offers no option to split it.

[`gradle/bytecode-metadata.gradle`](../gradle/bytecode-metadata.gradle) runs the
[Java normalizers](../buildSrc/src/main/java/thc/buildlogic/BytecodeNormalizers.java).
The metadata pass splits complete case/return groups into bounded private helpers at the end of
`processMainJava`, before Gradle snapshots that task's output. Every argument
description is retained verbatim; the interpreter and primop implementations are
untouched. This normalization removes no metadata and requires no GHC. It is
idempotent and rejects unrecognized generator shapes or processor versions;
review it when upgrading Truffle. Separate, hash-pinned
[protocol artifacts](../tools/truffle-protocol/README.md) add unprofiled runtime
branches and explicit root materialization/completion declarations. They are
rebuilt from upstream source and never replace shared Maven cache files.

Run `./gradlew testBytecodeMetadataSplit` for compiled before/after opcode checks
and malformed-input controls. Add
`-Pthc.metadataFixture=/absolute/path/to/BytecodeRootGen.java` to compare every
argument description in an existing generated source without modifying it.
The check is also part of Gradle's `check` task.

Build configuration uses Groovy, with Java build logic in `buildSrc`.
`processMainJava` runs the pinned annotation processor with `-proc:only`,
then all six pinned normalizers transform its generated sources in order.
`compileJava` consumes original and normalized sources with `-proc:none`;
generated language-service resources are packaged separately. Test annotation
processing remains enabled on its own pinned processor path. The build has no
Kotlin plugin, compiler, KAPT stage or Kotlin runtime dependency.
`./gradlew testJavaBuildPipeline` checks the realized task and toolchain contract.

The final normalizer constructs the scalar and compact application children from
immutable instruction operands when cached bytecode nodes are prepared. It does
not seed generated specialization bits or invent a target observation. A cold
compiled call uses the existing generic application algorithm; ordinary
interpreter calls still populate the adaptive target cache. This preserves PAP,
overapplication and typed-result ownership without a warmup call.

`testBytecodeColdApplyPreparation` checks exact generated bodies, operand offsets,
instruction width, processor version, idempotence and malformed-input rejection.
`BytecodeColdApplicationTest` retains strict first-compiled-entry checks, source
replay, clone independence, invalid-function behavior and the ordinary observed
target-cache path. Only exceptional null diagnostics cross the shared cold-call
boundary; dispatch, argument forcing and guest execution do not.

The same pinned normalization adds a read-only `Builder.isParsingSources()`
accessor. Callers can test the builder's existing mode before resolving lazy
source arguments; ordinary `BytecodeConfig.DEFAULT` construction does not need
debug data. `./gradlew testBytecodeSourceModeAccessor` checks the generated
accessor and rejects unknown processor versions or builder shapes.
`BytecodeLazySourceModeTest` checks explicit source-information replay without
changing root identity, instructions or instruction arguments.

Cold bytecode compilation uses the existing cached-node transition before
partial evaluation. The compiler can attach the `FrameSlotKind.Long` singleton to
exact, evaluated wide-integer formals with one ingress store; cached-node
construction recognizes only that approved marker. Conflicting slot metadata
remains unknown, and ordinary writes retain the DSL's widening behavior. Async,
captured, loop, typed-input and unknown formals keep the adaptive path. Selected
wide add/subtract/multiply operations use stateless DSL declarations with actual
carrier checks, not fabricated specialization history. Narrow-integer carriers
are not selected by this wide-only rule.

`testBytecodeStaticPreparation` checks the pinned preparation transform;
`BytecodeStaticEntryTest` covers immediate compiled entry, source replay, clones
and widening. The strict JSON/compact interoperability checks must continue to
enter the original installed target on their first invocation without warmup.

`scripts/try.sh --handoff-modes` prepares the full fixture set once and batches
installation, diagnostic tools and both test forks. Native ABI probes still run
once per Gradle graph because their complete host/compiler/header inputs are not
modeled for safe cross-invocation caching. No fixture stamp or provenance hash is
rewritten to approve stale artifacts.

When adding a primop, update its existing entry in
[`scripts/core-capabilities.json`](../scripts/core-capabilities.json) after both
backends and the native checks pass. Then refresh the generated
[primop checklist](primops.md):

```sh
cabal run exe:thc-primops -- scalars --write
cabal run exe:thc-primops -- coverage --write-checklist
cabal run exe:thc-primops -- coverage --check
cabal test primop-tools
```

The scalar signature command needs the pinned GHC 9.14.1. The checklist is
derived from the same contracts as the auditor; don't edit its checkboxes by
hand. Record concrete missing behavior separately; runtime representations alone
do not make implemented operations partial.

`Fast checks` is an informative PR workflow, not a publication gate. It runs compiled smoke tests and
checks for the changed components on Linux, in both handoff modes. Compiler,
calling-convention and other broad changes run more tests. Cached toolchains,
compilation outputs and verified native/Core inputs save setup work; the selected
tests still execute on every run.

`Build` runs the full suite on main after merges: native comparisons, both
backends, both handoff modes and library checks on Linux and macOS. Review those
results when integrating changes, and carry failures and untested limits into
the next handoff.

`JIT stability` is a separate advisory workflow. It checks that ShortByteString
call targets stay compiled throughout a warmed run, retaining failed rows and
target snapshots in its artifacts. Code retirement is tracked in
[#72](https://github.com/ekmett/thc/issues/72); a failure here does not block
the required PR checks. The complete native-result comparisons, strict
closure checks and handoff cleanup checks remain in the required test suite.
The active fixture producer is `scripts/prepare-short-bytes-slices.py`, also
used by `scripts/prepare-tests.sh` and the advisory workflow. It produces native
oracle rows, original Core and a checked manifest under `build/short-bytes-slices`.
An existing preparation can be revalidated with its `--check-only` option;
changed or missing inputs require fresh preparation, not edited hashes.

Run the advisory checks locally, selecting each handoff mode explicitly:

```sh
python3 scripts/prepare-short-bytes-slices.py
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=false ./gradlew --max-workers=2 --no-daemon jitStabilityTest
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew --max-workers=2 --no-daemon jitStabilityTest
```

`jitStabilityTest` always starts a fresh test process and selects only the
`jit-stability` tagged checks; the ordinary mode tasks exclude those checks.
It does not generate its Haskell fixtures. Both commands use the same
`build/test-results/jitStabilityTest` and `build/reports/tests/jitStabilityTest`
locations, so preserve the first reports before running the second when both
sets of evidence are needed. The examples replace `JAVA_TOOL_OPTIONS`; retain
any other required JVM flags explicitly.

The local task exits unsuccessfully if a stability assertion fails; the advisory
CI workflow allows that failure while retaining evidence. It remains separate
from ordinary native-result correctness checks.

## Inactive merge-bot reference

Manual integration by designated owners is the active workflow described above.
The retained merge-bot code, workflow and `auto-merge` label are not an active
submission or integration path. Do not queue work, dispatch the retained bot or
wait for its historical status/merge sequence as a publication prerequisite.

## Broader local checks

To reproduce the main checks locally:

```sh
scripts/try.sh --handoff-modes
scripts/try-libraries.sh
THC_DIAGNOSTIC_UNSUPPORTED=true scripts/try-map.sh
python3 -m unittest discover -s .github/scripts -p 'test_*.py'
```
