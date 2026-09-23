# One-step Force checkpoint

This feature branch preserves the tested one-step `Force` candidate on commit
`2844d484e2a6c697b889067bd029321fc2306999`. The implementation and two added
`ThunkRetentionTest` cases are the exact frozen sources used by the 179-test
suite and isolated Linux comparisons. This checkpoint is not a main rollout.

The source change replaces repeated forcing with one state dispatch. Its safety
requires every successful memoized result to be terminal WHNF; the existing
update path rejects a Thunk result before publishing it. See the
[invariant audit](evidence/force-invariant-audit.md).

The [sealed report](evidence/README.md) contains both policy comparisons, all raw
windows, test XMLs, source hashes, graph archives, allocation profiles, and the
portable verifier. It is copied byte-for-byte; recorded absolute paths describe
the original capture environment and are not required for verification.

From the repository root, run:

```sh
python3 experiments/one-step-force/verify-checkpoint.py
```

This verifies every source listed in the frozen manifest against this checkout
and then runs the sealed evidence verifier. It requires Python 3.10+ and does
not run a JVM, benchmark, graph capture, or network operation. Rebuilding and
rerunning tests requires the repository's pinned GHC/Graal setup and fixture
preparation scripts. No new test or performance run was made while committing
this checkpoint.
