# Fast checks

Pull requests run `automation` and `fast-check`. The automation job tests the CI
scripts with normal Python and `-O`, then lints the workflows. A PR from this
repository runs `fast-check` on the persistent Linux runner, reusing its toolchain,
Gradle dependencies, build outputs and daemon. Fork PRs use a GitHub-hosted runner.
The event is checked before selecting the persistent runner and checked again on
that runner before checkout. The pinned Graal/JDK and GHC versions are verified;
the input identity does not hash the installed GHC library tree.

`fast-check` compiles the project and runs a fixed smoke suite plus changed tests
and explicitly mapped consumers of changed code, fixtures or preparers. The smoke
suite covers AST and bytecode execution, host compilation, handoff, typed inputs,
tuple inputs and native explicit-64 primops. The selector maps the bit, integer,
signed-narrow and other reviewed families to their own tests. Known additive
primitive entries and whole new dispatch arms select the native family that
exercises them. Edits to existing shared dispatch, compiler code, unknown fixtures
or unmapped dependencies run the full test inventory. A changed test is never
dropped to meet a time budget. The ownership rules are in
`.github/scripts/fast-tests.json`.

For a narrow selection, `.github/scripts/fast-fixtures.json` names each required
native/Core preparation group. Local stamps include the declared source bytes,
toolchain identity and every output byte. Missing, changed or linked inputs or
outputs cause preparation again. Unknown selected classes and full selections
use `scripts/prepare-tests.sh` on a local receipt miss. A hit verifies the pinned
source/toolchain identity, vendored source pins, and the exact paths, bytes and
modes of the reviewed Core/native fixture outputs before reusing them. GHC
objects/interfaces, JVM-generated sources and classes, and task state are outside this
receipt. A changed preparation plan or new output root declines reuse until its
scope is reviewed. The groups retain independent native oracles and pre/post
Core audits. These are local accelerators, not cached test results or a transfer
archive.

The selected Python tests run with normal Python and `-O`. Gradle runs the selected
JUnit classes in ordinary and dense handoff modes, rerunning the `test` task while
keeping compilation and dependency outputs reusable. Existing test reports are
moved aside; the runner requires fresh, nonempty, successful XML for every selected
class and the same testcase set in both modes. The primop checklist is checked
against the installed GHC API on every run. Selection, commands, timings, native
input decisions and fresh results are retained in `build/fast/results/`.

The full cross-platform `Build` runs after merge on main. The nightly JIT
stability workflow is advisory and separately records compiled-code behavior.
Neither the smoke selection nor a warm local run is a claim about hosted PR
latency; use the workflow's phase timings and elapsed time to assess that.

For automatic merging, the repository owner applies `auto-merge`. The bot
checks and merges one PR at a time against current main. A completed failing
main `Build` pauses automatic merges; a pending main `Build` does not.
