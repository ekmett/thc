# Continuous integration checks

Pull requests run `automation` for CI scripts/workflows and `fast-check` for the
changed components. `fast-check` runs compiled smoke plus affected per-commit
tests in both handoff modes, Python checks and cheap driver units. Shared changes
select the full inventory before the explicit cadence policy is applied.

`Build` runs per-commit coverage. `Hourly` runs established passing coverage at
minute 31; an hourly failure blocks further development until fixed. `Intensive`
runs daily at 07:17 UTC for expensive fixtures and public package integration.
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

Hosted Build and scheduled jobs restore installed tools independently of source
changes. Missing GHC/Cabal, GraalVM and LLVM installations run concurrently with
submodule checkout on the same runner. Setup joins every process before building
and terminates the other processes on failure; timings and logs are retained in
`build/ci/setup-results`. Verified tools are saved before compilation starts.

Application compilation builds the Cabal driver and Gradle `installDist` first.
The following test-support stage builds shared Java test classes, diagnostics and
CMake fixture tools. Neither stage runs tests. Scheduled jobs share both stages'
outputs; protocol and patched-artifact checks run only during verification.
Hosted stages reuse one job-owned Gradle worker, stopped on success or failure.
Per-stage timings and Gradle task profiles accompany the job's logs.

`cabal.project` pins the Hackage `index-state`. CI updates the index only when that
snapshot is absent from its cache. To update dependencies deliberately, change
that timestamp and run `cabal update`; editing `thc.cabal` does not refresh it.
Cabal and Gradle dependency caches fall back across project edits, while Cabal's
checkout outputs retain their project-specific key. Cabal and Gradle validate
the restored build inputs normally.

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
