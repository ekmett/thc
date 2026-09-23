# Development

Use a branch and a focused pull request against `main`. Put the change, validation
results and remaining limitations in the PR description. Use separate issues for
separate tasks; PRs carry the implementation discussion and merge status.

`Build` runs the full test suite, native oracles, both execution backends, the
dense handoff control, library checks, and the explicit Map/Set frontiers. It
also checks the merge automation. A trusted workflow verifies both build jobs
and the `automation` job in the latest Build run and attempt, and publishes the
`required-tests` commit status required by `main`, including for administrators.
This explicit status also covers bot-dispatched builds, whose workflow job checks
are not always eligible for GitHub PR requirements. A failed,
skipped or missing required check does not pass the bot's gate.
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

The bot runs after completed builds, queue-label changes and merges, with a
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
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true scripts/gradle.sh --no-daemon test --rerun-tasks
scripts/try-libraries.sh
THC_DIAGNOSTIC_UNSUPPORTED=true scripts/try-map.sh
python3 -m unittest discover -s .github/scripts -p 'test_*.py'
```
