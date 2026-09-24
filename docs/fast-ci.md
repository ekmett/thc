# Fast checks

`Fast checks` adds two checks, `automation` and `fast-check`. The ordinary warm
PR target is 2–3 minutes, not a claim that the full test suite takes that long.
Compiler, ABI, build-system, unknown-source and unmapped shared-helper changes run
the full JVM and Python test inventory. A changed slow test is never dropped to
meet a budget. Both default and dense handoff modes run in either lane.

The initial compiled smoke suite is:

- `HostEntryCompilationTest`: real AST/bytecode split targets and host compilation.
- `TypedInputScalarSourceTest`: typed source inputs and generalization.
- `RuntimeTest`, `BytecodeBackendTest`, `HandoffTest`: runtime/backend/transfer checks.
- `TupleInputNativeTest`: real GHC source, native results and compiled entries.
- `Explicit64PrimopsTest`: native integer primop comparisons.

The selector always includes those tests, adds every changed test and reviewed
source-to-test dependencies, and widens when it cannot prove a smaller set is
sufficient. Reviewed mappings cover bit operations, scalar floating/raw-bit casts,
grouped integer/floating vectors, and six native fixture families, including their
preparers and independent oracle tests. The primop test context selects its three
consumers. New primitive entries in `core-capabilities.json` and whole new narrow
mask arms in `Program.kt` select their native family only when the existing content
is unchanged; whole new arity/execution dispatch arms and bytecode name-to-existing-operation
arms for those families are handled the same way. Other shared dispatch, vector-memory proof and representation edits
still widen. Fast-check automation edits select smoke plus control tests; merge and
library scripts also select their own Python tests. New dependencies must be
reviewed in `.github/scripts/fast-tests.json`; there is no force-narrow option.
A missing base, rename, deletion, dirty checkout or ambiguous helper cannot
silently narrow tests. The ordinary `Build` runs the full suite after merge.

## What is cached

Three independent caches avoid repeating useful work:

1. The pinned Graal and GHC installations. Setup still verifies the requested
   versions. The fixture key additionally fingerprints the actual compiler,
   installed interfaces/libraries, JDK release, platform and relevant environment.
2. Gradle dependencies, wrapper and content-addressed Kotlin/KAPT/Java build
   outputs. Gradle validates task inputs. The fast init script disables caching
   Test outputs; task-scoped `test --rerun` also prevents up-to-date skips.
3. Native oracle data, original Core and their preparation records. The archive
   is keyed by actual source dependencies, toolchains and workspace path, not the
   whole Git commit. Every file, original recorded hash and safe destination is
   checked before restoration. Unknown, stale, incomplete, linked or corrupt
   inputs are rejected and prepared again. Original provenance is not rewritten.

PR jobs only restore caches. A successful push to current main can publish them.
A main-branch dispatch can also publish when its nonempty `expected_sha`, checked
out commit, Actions SHA and current remote main all agree. This supports merges
made with `GITHUB_TOKEN`, which do not trigger another push workflow. An arbitrary
dispatch branch cannot seed the main caches. Hashes verify integrity and freshness;
they do not replace this producer-trust rule.

No previous test result is accepted. Existing XML is moved aside before each
test task. The runner requires nonempty fresh XML for exactly the selected class
set, no failed/errored/skipped cases, and matching default/dense testcase sets.
It retains failed runs and checks Python tests with and without `-O`. The primop
checklist is checked against the installed GHC API on every run, including hits.

## Bootstrap and measurement

This change does not alter `Build`, branch protection or merge-bot policy. Merge
the new workflow alongside the existing gate first; a successful current-main
run seeds the caches. Moving the full cross-platform workflow after merge and
making a full-workflow failure stop automatic merging are separate policy changes.

Cold native preparation, a toolchain-cache miss and widened checks may take much
longer than the ordinary target. `build/fast/results/` retains the selection,
commands, exit codes, phase timings, cache outcomes, fresh XML and an Actions
summary. Automation must pass before the runtime job can seed caches. The runtime
job's elapsed time starts just after checkout and includes tool setup,
cache transfer/validation, tests and cache saves, but not queue time or the final
artifact upload or the preceding automation job. Do not report summed warmed
JUnit case times as gate latency.

For a local run, use the pinned environment and a fresh report directory:

```sh
python3 .github/scripts/fast_ci.py start
python3 .github/scripts/fast_ci.py identify
python3 .github/scripts/fast_ci.py run --base BASE_COMMIT --head HEAD
python3 .github/scripts/fast_ci.py finish
```

No cache archive is required: a miss prepares and packs inputs before testing.
The helper supports only a clean global GHC installation; user package databases
and automatic package environments are rejected. Cache identity intentionally
includes absolute paths because existing Core/provenance references use them.
Use `--report-dir`, `--identity` and `--bundle` to preserve repeated experiments.

The workflow pins action commits and keeps cache writes separate from restores.
The Gradle init script uses the documented
[`TaskOutputs.doNotCacheIf`](https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/TaskOutputs.html)
mechanism to exclude test outcomes while leaving compilation cacheable.

## First local measurement

On eak-quartus (Linux x86-64), revision `7e0cacadeb0a32a638355401b0b64fdc4a6a1bc0`
passed the seven-class smoke suite after all generated fixtures, vendor files and
project build state were moved aside. The input archive restored 976 files and
all seven compilation tasks came `FROM-CACHE`. The runner took **69.8 seconds**:
1.72s identity, 1.89s selection, 5.06s restore, 9.93s Python checks, 23.97s default
and 27.02s dense. Each mode ran 44 fresh tests with no failures/errors/skips.

This was a no-source-change, installed-toolchain/local-cache measurement, not a
GitHub-hosted result. Hosted GHC/Graal downloads and cache transfer still need to
be measured. The cold preparation plus uncached JVM compilation took 296.15s
(Gradle reported 43s). Raw command/exit/timing/XML records are retained under
`bench/experiments/fast-ci/evidence-x86_64/`; no runtime assertions or limits changed.
