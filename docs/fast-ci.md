# Continuous integration checks

Pull requests run `automation` for CI scripts/workflows and `fast-check` for the
changed components. `fast-check` runs compiled smoke plus affected per-commit
tests in both handoff modes, Python checks and cheap driver units. Shared changes
select the full inventory before the explicit cadence policy is applied.

`Build` runs per-commit coverage. `Hourly` runs established passing coverage at
minute 31; an hourly failure blocks further development until fixed. `Intensive`
runs daily at 07:17 UTC for expensive fixtures and public package integration.
These scheduled workflows use the same group preparer and test runner as Build.
Changed tests still follow their declared cadence; deferred coverage is reported
in `selection.json` and the phase summary, never counted as executed.

The selection is recorded under `build/fast/results/`, together with commands,
timings and fresh results. JVM XML and HTML reports use separate directories for
each handoff mode. Cached compilation and verified fixture inputs avoid repeated
setup; selected tests still execute on every run. The generated primop checklist
is checked against the pinned GHC API.

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
selected PR check may compile a new full-Core test without executing it; use the
separate full-Core workflow or local fixture setup for its runtime checks.

Per-commit, hourly and nightly reports together describe the coverage; a smoke
selection does not establish whole-program compatibility. See
[contributing](contributing.md) for local commands and
[Core compatibility checks](coverage.md) for native comparisons.
