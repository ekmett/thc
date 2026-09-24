# Genuine foreign-call reader integration correction

Parent requested a pure-Python compatibility check against the integrated root
auditor at `3e2c124e039926c165e907b034cc80bb9197b50b`. The original reader's
expectation was stale: all eight unknown foreign roots formerly had only the
aggregate-host-result issue plus one missing global. The six genuine main-unit
declarations naming known MD5 symbols now correctly receive an additional
`foreign-call-representation` rejection with exact detail
`Invalid MD5 foreign call: static ghc-internal function target`.

First attempt failed with `Foreign frontier changed: md5Init`, exit 1. Raw
command/status/output remain under:
`/home/ekmett/.codex/worktrees/98d0/cult/build-agent-logs/20260924-002200-8ldeiiiu/`.
No unchanged retry occurred.

One-file correction committed in isolated checkout
`/home/ekmett/ai/thc-foreign-call-signatures-01a0cdeb`:
`13061840cc38df64266c87853957aa958c12e421`.
Only `compiler/test-fixtures/check-foreign-call-metadata.py` changed (6 additions,
2 deletions), SHA256
`99cb06514d5634cc3a7196c905935c48dcacaa9396e077aed5fd40680574a577`.
It requires the exact additional issue for the three exact structured MD5
symbols, retains the one missing-global requirement, and leaves dynamic/unknown
and ordinary same-named Haskell controls unchanged. No relabeling to
`ghc-internal`, runtime/auditor relaxation or export mutation occurred.

Corrected reader passed both normal and `-O`, using root auditor/capabilities
through a small in-memory harness that sets the reader's ROOT and Python import
path to the root checkout. Raw gated command/status/output:

- `20260924-002244-0eeuervi/`: normal, exit 0.
- `20260924-002244-os140ff2/`: optimized Python, exit 0.

Both are under the same `build-agent-logs` directory. Each verifies both genuine
Core stages: 8 exact descriptors and 8 strict foreign frontiers, one accepted
ordinary Haskell control, 61 pre-/59 post-Tidy unique renamings against the
original descriptor-free exports, and 16 malformed descriptor reader controls.
These are reader checks, not new FFI/guest execution.

The retained four export hashes are unchanged from the original exporter run:

```
before-pre  d2f40f09535ed05e216e93040b220199373b12f17614f3e14601676912f7c895
before-post 14731cb33eccaad857b97425db99e75843534bffd309657058b3991f667bc9cd
after-pre   f3b981479ac4bfae0b16f8ec2bf0dfa2f66092456c7093ef78ce53139a52e34c
after-post  e3ef6c6120ba6e995f660f018de522231579161d33e1fd682ba91e6743f70e05
```

No builds, fixture preparation, native execution, JVM runs, root source edits,
push or PR. The separate production closure review is recorded in
`20260924T042056Z-md5-callee-closure-review.md`.
