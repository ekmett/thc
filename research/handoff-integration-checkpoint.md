# Historical dense-handoff integration

This preserves the complete historical section from
[THC 2d7c255e](https://github.com/ekmett/thc/blob/2d7c255e07a777c0b889f29a4790709343318c84/docs/handoff-slabs.md).
It records that section's earlier implementation/workflow context, not current
instructions. No new run or automation change is reported. The original body
below is unchanged apart from relative-link repair.

Use the [current handoff protocol and named-mode tests](../docs/handoff-slabs.md)
for present behavior and verification.

---

## Integration coverage

This section retains the historical reference-return v3 integration from
experiment commit
`80ba011b1bd954fc75f5bb090730c61f8e94e0e2` onto main `7dc2281`. The archived
186-test suites on `experiments/handoff-slabs` belong to the earlier frozen
caller-demand baseline; they are not current-main validation.

The tests at that checkpoint covered deep non-tail calls, ancestor tail cycles,
mixed Long and reference returns, lazy PAP prefixes, escaping captures, retained frames,
deoptimization, exceptional cleanup, physical-representation aliases, and
lexical scope forks across non-inlined calls. Its full suite also compared
20 real-GHC corpus entries and 318 native-oracle input/result pairs on both
backends, with cold paths and explicit guest recompilation, and checked the
aggregate rejection frontier and caller-demand gate.

The recorded integration runs passed **236/236 tests with handoff disabled and
236/236 with handoff enabled**, with no failures, errors or skips. They used
that checkpoint's generated GHC fixtures, independently of the archived
experiment's test outputs. These counts do not describe the current suite.

The retained command sequence used GHC 9.14.1 and GraalVM 25.3.4.1, with
`JAVA_TOOL_OPTIONS` inherited by the forked test JVM. It is historical
reproduction context, not the recommended current workflow above:

```sh
scripts/prepare-tests.sh
./gradlew --no-daemon test --rerun-tasks
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew --no-daemon test --rerun
```

No new benchmarks or rollout decision accompanied that integration. The frozen
v3 reference measurements remain smoke checks only.
