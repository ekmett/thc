# Dense handoff checkpoint

This feature branch preserves the exact reference-return v3 prototype whose
archived suites passed **186/186 with handoff enabled and 186/186 by default**.
The committed src/compiler/scripts/tools tree matches every one of the 133
SHA256 entries in the [complete overlay manifest](evidence/noninline-call-abi-review-v3/complete-overlay-manifest.json).

The branch starts at public commit `a53aee40c84c039cb12d1d17f018d00453e713ca`.
Commit `36cc182b843545a6485fa7241b8ec0846349dd9d` reconstructs the frozen
caller-demand baseline used during testing; the following implementation commit
applies the archived full v3 prototype patch unchanged. This baseline has the
frozen always-on caller-demand implementation. It deliberately predates the
later default-off caller-demand gate on main `2844d48`. Merging or rebasing this
branch onto current main requires retaining that gate and fresh integration
validation. The archived test results do not validate such an integration.
Handoff itself remains default-off, enabled with `-Dthc.handoffSlabs=true`.

The language protocol transports arguments in reusable dense Long/reference
fields and scalar results through a private completion token and primitive
register. Supported single-reference results use the ordinary Object return.
The actual Truffle/native call ABI remains `Object[] -> Object`. See the
[v3 report](evidence/noninline-call-abi-review-v3/README.md) for ownership,
reentrancy, tail-call, and reference-lifetime limits. The
[v2 report and graph audit](evidence/noninline-call-abi-review-v2/README.md)
retain the earlier scalar checkpoint, complete source overlay, patches,
synthetic measurements, and graph review. Historical local snapshot paths in
those reports identify the capture environment; source reproduction uses the
included archives.

The v3 reference measurements are explicitly **smoke checks**, with one-second
warmup and 100ms windows, not steady-state throughput evidence. The prepared
long-window campaign was deferred. No new benchmarks, graph captures, or JVM
test runs were performed while committing this checkpoint.

From the repository root, verify the checked-out sources, both complete source
archives, immutable evidence hashes, archived test XML totals, and smoke status:

```sh
python3 experiments/handoff-slabs/verify-checkpoint.py
```

To rebuild tests, use the pinned GHC 9.14.1 and Graal 25.3.4.1 setup described in
the repository, prepare generated fixtures with `scripts/prepare-tests.sh`, and
run `scripts/gradle.sh --no-daemon test`. To rerun the enabled suite, pass
`-Dthc.handoffSlabs=true` into the test JVM (for example via
`JAVA_TOOL_OPTIONS`) and use `--rerun-tasks` so the default result is not reused.
The complete archived overlay can also reproduce the source by replacing
src/compiler/scripts/tools on the stated public base. The full prototype patch
alone assumes the frozen caller-demand overlay, not the bare public base.
