# Development

`make` builds the runtime and Haskell components. `make test` prepares native
fixtures and runs the JVM tests; `make test TESTS='thc.RuntimeTest'` selects one
JUnit class. `make jit-test` runs the separate advisory JIT retention suite.
`make jar` rebuilds only the runtime JAR, and `make probe ARGS='...'` invokes
the diagnostic runner. `make clean` removes the Gradle and Cabal build products;
`make distclean` also removes `.gradle`, `.kotlin`, and `.gradle-user-home` in the
checkout. Neither cleanup target requires a JDK or removes an external cache.
Use `GRADLE_FLAGS=--offline` or `CABAL_FLAGS=--offline` for an offline build.

Use a descriptive branch name, such as `pinned-arrays` or `fast-ci`, and a
focused pull request against `main`. Explain the problem, the change and how you
checked it. Keep issues about the work to do; PRs carry the implementation
discussion and merge status.

When adding a primop, update its existing entry in
[`scripts/core-capabilities.json`](../scripts/core-capabilities.json) after both
backends and the native checks pass. Then refresh the generated
[primop checklist](primops.md):

```sh
python3 scripts/generate-scalar-signatures.py --write
python3 scripts/primop-coverage.py --write-checklist
python3 scripts/primop-coverage.py --check
python3 scripts/test-primop-coverage.py
```

The scalar signature command needs the pinned GHC 9.14.1. The checklist is
derived from the same contracts as the auditor; don't edit its checkboxes by
hand. Partial support stays explicit, including local-only SIMD and managed
address restrictions.

`Fast checks` is the required PR workflow. It runs compiled smoke tests and
checks for the changed components on Linux, in both handoff modes. Compiler,
calling-convention and other broad changes run more tests. Cached toolchains,
compilation outputs and verified native/Core inputs save setup work; the selected
tests still execute on every run.

`Build` runs the full suite on main after merges: native comparisons, both
backends, both handoff modes and library checks on Linux and macOS. A failed full
build stops further automatic merges until a successful build containing the fix.
A full build that is still running does not hold up an otherwise ready PR.

`JIT stability` is a separate advisory workflow. It checks that ShortByteString
call targets stay compiled throughout a warmed run, retaining failed rows and
target snapshots in its artifacts. Code retirement is tracked in
[#72](https://github.com/ekmett/thc/issues/72); a failure here does not block
merges or stop the merge bot. The complete native-result comparisons, strict
closure checks and handoff cleanup checks remain in the required test suite.
Run the advisory checks locally with:

```sh
python3 scripts/prepare-short-bytes-slices.py
./gradlew --no-daemon jitStabilityTest --rerun
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew --no-daemon jitStabilityTest --rerun
```

The local task exits unsuccessfully if a stability assertion fails; only CI
allows that failure. Its XML and reports are separate from ordinary tests.

The merge bot runs code from `main`. It checks the required workflow's exact
commit and current attempt, then publishes the `required-tests` status enforced
by branch protection. Missing, skipped or failed required jobs do not pass.
The full Build matrix retains both platform build jobs, eight library jobs and
its automation checks. Library artifacts belong to one platform and run attempt;
use **Re-run all jobs** when repeating that workflow.

After all its checks pass, the bot may attempt a protected merge for GitHub's
`clean` or `unstable` state; GitHub can still refuse it.

The repository owner applies `auto-merge` when a PR is ready to land. The bot
verifies the latest label application in GitHub's event history was by `ekmett`;
a collaborator's label application does not authorize it. Agents authenticated
as `ekmett` can queue work. The merge bot takes non-draft PRs
from branches in this repository, updates one against current `main`, explicitly
dispatches tests when needed, and squash-merges its checked commit. It never
approves reviews or bypasses branch protection. Remove the label to withdraw a
PR. Fork contributions can run the ordinary read-only PR tests and be reviewed
and merged manually.

Use `bulk-merge` instead to allow the bot to combine up to four PRs whose changed
files do not overlap. It tests the combined commit against its base, then merges
that exact composition. Once the combined run is queued, the bot cancels active
individual Fast runs for those same component commits to free the runner.
Cancelled individual runs never count as passing. If the batch fails, or its
three infrastructure attempts are exhausted, the bot switches its members to
`auto-merge` and requires fresh individual tests. If a member changes or withdraws
permission, the batch is discarded and the remaining work is reconsidered.

A conflicting or failing PR stays open for its author to fix. The bot can move
past those PRs; it waits for an active build before updating another branch.
Dependent changes should name their prerequisite PRs and wait to be labelled
until those prerequisites have landed.

GitHub can refuse an automatic branch update, including when workflow edits
require permissions the built-in Actions token does not have. For a permission
or method rejection (HTTP 403 or 405), a maintainer must merge current `main`
into the PR branch, resolve any conflicts and push; the bot leaves that PR open
and can process others. HTTP 409 or 422 defers the update until a later run
rechecks the current head; if it persists, update the branch manually. A known
rate-limit response also waits for a later run. A rejected update never causes
the bot to dispatch or merge the stale head. Updated commits still need the
normal required checks.

The bot runs when checks change, after queue-label changes and merges, with a
scheduled reconciliation for missed events. Its Actions summary records what it
did. `Merge bot` can also be dispatched manually. It needs only the built-in
Actions token: no personal token, external service or signing key.

Tests run with read-only repository permissions. The privileged bot checks out
only `main` and consumes GitHub API metadata; it does not run PR code or download
PR artifacts. GitHub's strict required checks and expected-head merge guard
handle changes racing with the bot.

GitHub's native merge queue currently requires an organization-owned repository.
This small serial bot provides the needed coordination for this personal repo.

To reproduce the main checks locally:

```sh
scripts/try.sh
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew --no-daemon test --rerun
scripts/try-libraries.sh
THC_DIAGNOSTIC_UNSUPPORTED=true scripts/try-map.sh
python3 -m unittest discover -s .github/scripts -p 'test_*.py'
```
