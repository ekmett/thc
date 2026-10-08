# Continuous integration checks

Pull requests run `automation` for CI scripts/workflows and `fast-check` for the
changed components. `fast-check` runs compiled smoke plus affected per-commit
tests in both handoff modes, Python checks and cheap driver units. Shared changes
select the full inventory before the explicit cadence policy is applied.

`Build` runs per-commit coverage. `Hourly` runs established passing coverage at
minute 31; an hourly failure blocks further development until fixed. `Intensive`
runs daily at 07:17 UTC for expensive fixtures and public package integration.

For a bounded manual qualification, dispatch **Hourly qualification** with an
exact existing Hourly `group` and `platform=ubuntu-latest` or `macos-latest`.
The defaults select `scalar-memory-utilities` on Ubuntu. This runs setup, shared
compilation, the selected fixture prerequisites and both handoff modes in one
job with a ten-minute total limit. Compilation and execution have a seven-minute
step limit to leave time for cleanup and evidence upload. A timeout is failed
qualification evidence; it does not increase the preparation budget. Selection
and platform validation happen before setup. Normal command failures retain
logs/traces; hard job cancellation can interrupt artifact collection. This manual-only
workflow does not clear the full Hourly failure gate or qualify other groups.
Scheduled coverage is unchanged.

These scheduled workflows use the same CMake fixture graph and test runner as Build.
Build compiles and runs its tests in one job per platform. Hourly and Intensive
compile once per platform, then share those outputs with their test groups.
Each entry workflow declares only its own jobs: Windows Runtime belongs to
Hourly, and package integration and the library matrix belong to Intensive.
Hosted jobs install CMake and Ninja; persistent runners must provision CMake 3.24+
and Ninja alongside the pinned compiler. Quarantined producers are absent from
scheduled generation as well as test selection. The separate package integration
build runs nightly; ordinary hourly tests reuse their own common compilation.
Changed tests still follow their declared cadence; deferred coverage is reported
in `selection.json` and the phase summary, never counted as executed.

The selection is recorded under `build/fast/results/`, together with commands,
timings and fresh results. JVM XML and HTML reports use separate directories for
each handoff mode. Cached compilation and verified fixture inputs avoid repeated
setup; selected tests still execute on every run. The generated primop checklist
is checked against the pinned GHC API.

Hosted Build and scheduled jobs restore independent cache layers alongside
submodule checkout, with up to four setup processes on the same runner. Each
tool installer waits only for its own caches. GHC and LLVM installations remain
independent of project changes; Cabal project outputs participate in the same
restore queue for compilation jobs. Setup terminates the other processes on
failure. Timings, logs and a Perfetto trace are retained in
`build/ci/setup-results`. Verified tools are saved before compilation starts;
mutable dependency caches are saved at job completion.

The coordinator invokes the official `actions/cache@v4` restore entry point
from the runner's action directory. The composite declares that action so the
runner downloads it first; `actions/github-script` supplies its Node runtime
and cache-service environment. Cache keys, paths and fallback prefixes are
declared once in the composite. Lookup-only cache steps register post-job saves
without downloading the archives again.

Commit builds run `ci-commit` in the CMake/Ninja graph with four workers.
Linux, macOS and automation jobs start independently. Each platform checks
Hourly health before setup, preserving the stop-on-regression policy without
waiting for the unrelated automation tests.
Gradle application packaging, Cabal tools and source-only Python checks can start
independently. CBD model checks depend on their encoder and compact tool; Haskell
tests depend on their executables; JVM tests depend on Java compilation and their
selected fixture targets. Checks produce fresh results on every invocation.
Cabal has one producer for its shared plan and package database. A Ninja job pool
allows one Gradle invocation to mutate its project state at a time; it does not
block unrelated work. Gradle and Cabal also use four build workers. Ordinary JVM
tests distribute classes across four isolated JVMs within each handoff mode;
the modes run sequentially to keep the total at four test workers. Methods remain
serial within a class because some tests change process-wide runtime settings.

Scalar fixtures use small native-GHC boundary sets with representative compiled
entries in both backends. General arithmetic, memory and loader suites own their
negative controls; scalar fixtures do not repeat pre/post/inlining matrices or
assert compiler-internal target counts. Mixed-backend continuation coverage uses
five cases for strict inputs, typed PAPs, tuple transport, masking and async delivery.
Tuple arithmetic checks native results and a BigInteger model at signed
endpoints, multiply overflow and representative carry boundaries. It uses one
Core stage and checks first compiled calls in both backends. Cross-call carry
transport stays in WordCarryTest; annotation and malformed-shape checks use
representative two-field and three-field operations.

`HandoffTest`, `AstStackTest`, `CoreUnitLoadTest` and `ManagedStackSnapshotTest`
run five of their 46 behavioral test methods on commits, plus the handoff-mode
proof. The fixed sample covers lazy argument ownership, sharing/masking through
stack spills, the first compiled bytecode spill, lazy Core demand and snapshots
surviving unwind. `cadence.partialJunit` in `fast-tests.json` names these methods.
The nightly Intensive workflow runs all 47 methods, including the partial set,
in both handoff modes. Ordinary local class selectors also run the full classes.
Selection and result validation reject missing methods; no random sampling or
ordering-dependent rotation is used.

The existing source ownership selector chooses affected Python and Haskell checks
and patch controls. Unknown changes or an unavailable comparison base retain the
full admitted checks. Runtime per-commit coverage remains unchanged. The selection,
individual command timings, Ninja log and Gradle profiles accompany the job logs.
Scheduled jobs still share common compilation before distributing their groups.
The job's Gradle worker is stopped on success or failure.

Each build coordinator writes `build-trace.json` alongside its timing report;
the existing CI result artifacts include it, the individual fragments and raw
Ninja logs under `traces/`, including on failure. Platform and scheduled result
artifacts are retained for seven days, Fast results for fourteen days. Open the
JSON in Perfetto to see command spans, completed Ninja edges
and Gradle tasks on one timeline. Gradle task outcomes distinguish execution,
cache hits, skipped tasks and up-to-date outputs. Ninja records successful edges
only; a failed command retains its outer span and log. Multi-output edges appear
once, and an incremental run excludes old Ninja log entries. Lanes display
overlap, not operating-system thread identities. Ninja timestamps are aligned
to the launching CMake command, so they include a small process-start offset.
Setup cache restores, checkout and installers are recorded separately in
`setup-results/timings.json` and `setup-results/build-trace.json`.

For a direct Gradle build, use
`./gradlew -Pthc.buildTrace=/absolute/path/build-trace.json installDist`.
The listener records task start/end times through Gradle's public event API;
it does not sample the JVM or instrument task code. A reused report directory
keeps trace fragments in a separate directory for each invocation; the combined
file describes the latest coordinator invocation.

`cabal.project` pins the Hackage `index-state`. CI updates the index only when that
snapshot is absent from its cache. To update dependencies deliberately, change
that timestamp and run `cabal update`; editing `thc.cabal` does not refresh it.
Cabal and Gradle dependency caches fall back across project edits, while Cabal's
checkout outputs retain their project-specific key. Cabal and Gradle validate
the restored build inputs normally.
Cabal defaults to four jobs in `cabal.project`. Its job semaphore shares those
slots with GHC's module compiler, avoiding four Cabal builds each spawning four
GHC workers. An explicit `cabal build -jN` changes the shared budget.

Intensive also caches the driver's content-addressed Core bundles, replay
interfaces and native companions under `THC_CACHE_HOME`. Their existing input
identities determine reuse; the archive cache key identifies the set of stored
objects, not a source commit. This large cache is restored only for its nightly
consumers. The cold acquisition check uses a separate empty cache, and runtime
checks still execute after restoration.

## Maintain test selection

[fast-tests.json](../.github/scripts/fast-tests.json) maps source changes to
consumer tests. [fast-fixtures.json](../.github/scripts/fast-fixtures.json) names
the native/Core preparation groups those tests require. Update the relevant
mapping when adding a test or changing its fixture dependencies. New and unknown
tests remain per-commit. `cadence.hourlyJunit` lists classes with repeated passing
CI evidence; `cadence.nightlyFixtures` names expensive providers. All transitive
dependency consumers of a nightly provider run nightly too. Committed smoke
always stays per-commit. Explicit local selection without `--cadence` remains
unfiltered.

Test the CI scripts with:

```sh
python3 -m unittest discover -s .github/scripts -p 'test_*.py'
python3 -O -m unittest discover -s .github/scripts -p 'test_*.py'
```

Full-Core tests need their own configured compiler and installed Core. A narrowly
selected PR check may compile a new full-Core test without executing it. The
current full-Core fixtures are quarantined pending explicit file dependencies;
compilation does not establish their runtime behavior.

Per-commit, hourly and nightly reports together describe the coverage; a smoke
selection does not establish whole-program compatibility. See
[contributing](contributing.md) for local commands and
[Core compatibility checks](coverage.md) for native comparisons.
