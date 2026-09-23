# Native-compiler follow-ups: non-reproductions

Three further bounded bytecode replays pass all 8,028 comparisons and 2,760
required positive compiled-entry checks, with 17 clean diagnostics and 8
expected frontiers. None reproduces the original miss or establishes a fix.
All use the unchanged fingerprinted manifest identified in `evidence.json`,
default native Graal, G1, 6 GiB heap, 2 MiB stack and compact object headers.
No guest calls, settling, retries or relaxed gates are added.

## Dispatch observations

Two build-only extensions of the previously failing root-entry trace were tried
once each. Both dump cache information only after the original assertion fails.

- A per-call HostDispatch recorder stores the dispatch node and selected target
  just before the existing call. The failure does not reproduce, no dump fires,
  and there is no direct/indirect cache-route attribution.
- A construction-only weak registry leaves HostDispatch.execute and
  TargetCache.call unchanged. It retains up to 32,768 weak dispatch references
  for post-failure matching against the host root from the original trace.
  Again no failure or dump occurs. Matching would describe later cache state,
  not dynamically prove which node handled a call. Weak refs avoid retaining
  completed contexts, but allocation, class loading and the original trace can
  still affect timing. Cloning and cleared/overwritten entries limit coverage.

Frozen commands, overlays, JARs and handoffs are under
`build/host-dispatch-attribution/` and `build/host-registry-attribution/` in
`/home/ekmett/ai/thc-sequence-entry-attribution-01a0cdeb`.

| Artifact | SHA256 |
| --- | --- |
| Per-call diagnostic JAR | `0fc4c912debebd19a7ae24c435242a005ca492330452456f32b899b4a9918450` |
| Per-call log | `cfcee0c6adb07060674520354ab18f24562a46eeb3eb1d1d2487150a1d8fed52` |
| Weak-registry diagnostic JAR | `bb8a662b68dfb973c8d92074c99e18115e418aadbe9627ebff804157a41f27d2` |
| Weak-registry log | `47e01f5c0c608ac9404eaefe49add3c36abac022ebdf2a4df531cf9688f6d17e` |

## Unmodified checker with external phase timestamps

A separate run uses the original frozen `ce0b5621…` runtime/checker, without a
source/compiler overlay. Bash timestamps receipt of existing stdout lines;
HotSpot logs native UTC and uptime. The actual compiler banner identifies the
Native Image shared library. Its Serial GC suffix describes libgraal, while
the host JVM lifecycle log explicitly reports G1.

The last earlier workload diagnostic is received at epoch1790180251.145357.
The seven successive Sequence frontier rejections end at1790180298.675010,
47.530 seconds later; the first accepted Sequence interpreted result arrives
at1790180305.027241, 53.882 seconds after that earlier diagnostic.
These are output-receipt times, not instrumented guest-entry timestamps.

The checker does no accepted workload calls while processing those frontiers.
Pinned PolyglotContextImpl.eval bytecode invokes parseCached at BCI62 before
CallTarget.call at BCI73, so a parse rejection does not invoke that eval's
resulting target. This does not trace every internal runtime boundary invocation
or prove the shared stub was wholly idle.

The run installs callBoundary once at0.822s/16:17:06.464 UTC. No boundary
retirement/replacement occurs through the last lifecycle records at134.576s.
It exits0. Thus it measures a long frontier-processing interval but does NOT
locate retirement inside one. It cannot retroactively explain the prior failing
or cold-flushed runs. Logging/output handling still affects timing.

Exact command, from `/home/ekmett/ai/thc-sequence-boundary-lifetime-01a0cdeb`:

```sh
mkdir -p build/native-phase-lifetime
. /home/ekmett/thc-benchmarks/2026-09-23-083914/environment.sh
set -o pipefail
env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS "$JAVA_HOME/bin/java" \
  -Xmx6g --enable-native-access=ALL-UNNAMED -Xss2m \
  -XX:+UseCompactObjectHeaders -Djdk.graal.ShowConfiguration=info \
  '-Xlog:gc=info,codecache*=trace,nmethod+install=debug:file=build/native-phase-lifetime/lifecycle.log:utctime,uptime,tid,tags:filecount=0' \
  -cp '/home/ekmett/ai/thc-sequence-current-main-01a0cdeb/build/root-boundary-repair-validation/lib/*' \
  thc.LibraryCheckKt \
  /home/ekmett/ai/thc-sequence-current-main-01a0cdeb/build/libraries/cases.json bytecode 2>&1 | \
  while IFS= read -r line; do printf '%s\t%s\n' "$EPOCHREALTIME" "$line"; done \
  > build/native-phase-lifetime/timestamped-check-bytecode.log
```

Retained logs in `build/native-phase-lifetime/`:

```text
63cb95748935a1fcf5d40729b64dd5817266c4ff4fc9c77ef9e9bca0397d888f  timestamped-check-bytecode.log
7ca0fa7747b4bd2b7666b63686119b1456de8506adcb6c0d49749d88d205643a  lifecycle.log
fd22734b3514df4f55c90d2299c24f0034b5f52f783004e13dd4ce7884c400ef  parse-before-execute.txt
```

Earlier unmodified failures and the root-only diagnostic reproduction remain
separate evidence. These passing observations neither repair the pinned runtime
nor make the strict Sequence integration gate reliably pass.
