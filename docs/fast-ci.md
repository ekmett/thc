# Continuous integration checks

Pull requests run `automation` for CI scripts/workflows and `fast-check` for the
changed components. `fast-check` runs compiled smoke tests plus changed tests
and their mapped consumers, in both default and dense handoff modes. Shared
compiler/runtime changes or unmapped dependencies select the full inventory.

The selection is recorded under `build/fast/results/`, together with commands,
timings and fresh results. JVM XML and HTML reports use separate directories for
each handoff mode. Cached compilation and verified fixture inputs avoid repeated
setup; selected tests still execute on every run. The generated primop checklist
is checked against the pinned GHC API.

## Maintain test selection

[fast-tests.json](../.github/scripts/fast-tests.json) maps source changes to
consumer tests. [fast-fixtures.json](../.github/scripts/fast-fixtures.json) names
the native/Core preparation groups those tests require. Update the relevant
mapping when adding a test or changing its fixture dependencies; unknown cases
fall back to broader preparation and testing.

Test the CI scripts with:

```sh
python3 -m unittest discover -s .github/scripts -p 'test_*.py'
python3 -O -m unittest discover -s .github/scripts -p 'test_*.py'
```

Full-Core tests need their own configured compiler and installed Core. A narrowly
selected PR check may compile a new full-Core test without executing it; use the
separate full-Core workflow or local fixture setup for its runtime checks.

The main `Build` workflow runs the complete cross-platform suite after merge.
The separate JIT stability workflow reports compiled-code retention. A smoke
selection does not establish whole-program compatibility; see
[contributing](contributing.md) for local commands and
[Core compatibility checks](coverage.md) for native comparisons.
