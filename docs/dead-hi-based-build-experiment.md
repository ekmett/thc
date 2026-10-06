# Retired native .hi loading experiment

This branch preserves the direct JVM .hi reader experiment retired on 2026-10-06.
It is not a supported build or an integration branch. Do not merge it into main.
The existing GHC interface acquisition that produces CBD is separate and remains
supported. CBD decoding must not become more expensive to accommodate this work.

The branch retains these independent source snapshots as ancestors:

- `aa1f1edf4`: root native reader and unfinished foreign integration, including
  the requirements/audit in `docs/native-core-loading-design.md`. The foreign
  execution slice was incomplete because genuine exception-bridge support was
  still missing. This is the source state at the branch tip.
- `384027035`: independently verified compiler-owned wired preparation plus its
  observable regression, based on published main `2ba667096`. Existing reader
  tests and execution tests passed in both handoff modes. This follow-up was not
  published on main.
- `ad768f7921f2e190fd8ff6b8275b5d674aba1653`: incomplete, rejected CBD recovery-fact
  experiment. It adds eager callable-type parsing during ordinary CBD decoding.
  Producer/consumer integration is unfinished; it has no runtime or performance
  qualification. None of this snapshot was published on main.

The two worker snapshots were joined with history-preserving `ours` merges.
They remain independently recoverable with `git show <commit>:<path>` or a
checkout of their commits; this branch does not pretend their unfinished trees
form a working combined build. No generated binaries, caches, or private
benchmark logs are included.

Published main when archived was `2ba667096a8df11aabdebb873b5986c1867bcbf3`.
Its CBD codecs/exporter are unchanged from pre-native baseline
`07fcadc6ea101fe7900e45c285b4a0f36ad58990`. Direct native loading, its fixture
plumbing, generated runtime catalogues and documentation are being removed from
main. Independent CBD ownership-safety corrections remain.

Original `native-hi-*` worker branch tips are also preserved as ancestors.
These retain alternate and superseded implementation attempts without adding
them to the final source tree. Native Image CBD capture is separate work.
