# Caller demand under Default: actual graphs and allocation

The proper caller-demand experiment keeps the hot insertion call **inlined on both sides** under Graal's `Default` policy. Exact allocation falls from **6,742,507 to 6,705,107 bytes/workload** (−37,400 bytes, **−0.555%**). Each value repeats in all three 256-call samples. This is a diagnostic addendum to the [matched throughput comparison](../castlemeadow-caller-demand/README.md), which found **no speedup**: 1.589148 → 1.593387 ms (+0.27%). Diagnostic elapsed times are not benchmark results.

Both inputs use bytecode runtime JAR `4e4568c2bdb19bba7a69cf366966bdac0f987d02c30f0ee46ed42aa03abf79bf`. The control strips only `callDemand` metadata: 7,742 certificates containing 7,686 strict positions across 17 Core modules. `balanceR_`'s four callee `entryStrict` positions remain false. This is the proper caller transformation, distinct from the earlier manual callee-ABI probe. [Structural proof](provenance/call-demand-structural-proof.json) records every Core hash; both variants pass all 18 native-oracle inputs before and after requested compilation ([raw checks](preflights/), [proof](provenance/caller-demand-preflight.json)). Instrumented checks reduce argument-thunk evaluations from 16,408 to zero; three unrelated `lvl` evaluations remain.

## What the actual worker graph shows

The selected source-less worker is identified by its unique name/source pair, not by a fixed root number: control root 23 / compilation 3479, enabled root 22 / compilation 3457. The removed thunk changes root numbering. [Comparison](graph-comparison.json) and the original [control](graph-control/call-tree.json) / [enabled](graph-enabled/call-tree.json) call trees show root child node 2, `lambda sc, x, ds1`, as `Inlined` on both sides at frequency 6666.333333. Its initial PE size is 854 versus 817 nodes. Default's recorded `call diff` is a different policy metric from Agnostic's immediate-child benefit; it must not be read as that earlier score.

| Selected worker, latest installed compilation | Control | Caller demand |
|---|---:|---:|
| Before high-tier lowering nodes | 4,487 | 4,521 |
| After mid-tier nodes | 9,896 | 9,731 |
| Native code bytes | 98,401 | 101,346 |
| Residual guest-call sites, all constant targets | 52 | 60 |
| `Object[]` / `Long[]` packet sites | 49 / 3 | 57 / 3 |
| `Long` allocation sites | 22 | 29 |
| `Thunk` / associated capture allocation sites | 8 / 8 | 0 / 0 |

These are static sites in one selected compilation, not dynamic invocation or allocation counts. In the control's scheduled mid-tier graph, node **48308** allocates `Thunk` and node **48317** allocates its capture in block **118**; their source stacks reach `BytecodeRoot.MakeThunk.create` and `CaptureLayout.captureValues`. Seven more such pairs occur elsewhere. None remain in the enabled worker. [Node evidence](allocation-node-evidence.json) lists every pair and every surviving packet/box site; [full summaries](graph-control/summary.json) and [packet audits](graph-enabled/packet-audit.json) retain source and dataflow evidence. Static packets and boxes increase while whole-workload allocation decreases slightly, so counting eliminated thunk sites overstates the practical benefit.

Separate JFR recordings cover 4,096 calls after a settling interval. They sample no thunk/capture allocations in the enabled run, while constructor values, reference-array packets and `Long` still dominate sampled allocation. The class weights are sampling estimates, not exact per-class allocation counters; absence from a sample is not proof of universal absence. Exact totals come from `ThreadMXBean.getThreadAllocatedBytes` around the workload on the invoking thread, with JFR disabled. [Control](profile-control/) and [enabled](profile-enabled/) retain original recordings, class/site weights and execution-sample tables. Execution samples and allocation weights are separate measurements.

## Capture and reproduction

Captures ran serially on Linux x86-64, the same i9-12900K host and pinned GraalVM 25.3.4.1+1.1 (Java 25.0.4.1) installation used by the timing comparison. The driver restricts processes to CPUs 0–15; both variants use `-Xms4g -Xmx4g`, compact headers, `Default`, recursion depth 2 and both inlining budgets 12,000. Class-owned layouts are on; typed-case, leading-case, constructor-class, unchecked-storage and boxed-value-cache options are all off. Source notes are on and instrumentation is off for captures. Full command vectors and all input/output hashes are in each `capture-config.json` and [driver log](execution/driver.json).

Every capture warms for at least 45 seconds and 30,000 calls, validates the native checksum, checks final guest code installation and rejects compilation/deoptimization events inside allocation, JFR, verification or graph-measure phases. All four captures passed. The cycle varies input 10000–10015. The Core was exported on macOS, then JIT-compiled on Linux; the native oracle was built on Linux using pinned GHC 9.14.1 and vendored containers-0.8 ([native provenance](provenance/native-build.json)). No macOS native binary was used.

The two original BGVs are included in [selected-worker-bgv.tar.xz](selected-worker-bgv.tar.xz): **2,074,796 bytes compressed / 57,043,882 bytes raw**. [Archive manifest](selected-worker-bgv-manifest.json) records their hashes and all eight selected phase topology digests (After PE, After Inline, before high-tier lowering, after mid-tier). Full node properties/source stacks remain in the BGVs. Other raw BGVs remain at their recorded server paths in [raw artifact references](raw-artifacts.json). The archive was extracted and reparsed independently: all eight phase topologies matched ([proof](reparse/topology-verification.json), [commands](reparse/reparse-execution.json)). The digest covers node IDs/classes/block membership, every edge and every block; it excludes node properties and elapsed timings.

Verify the portable evidence without a JVM:

```sh
python3 tools/verify.py
```

Reparse independently with the exact pinned Graal distribution, writing to a new directory:

```sh
python3 tools/reparse.py --java-home /path/to/graalvm-25.3.4.1+1.1 /tmp/caller-demand-reparse
```

The archived [capture driver](tools/capture-driver.py) and [capture tools](tools/capture/) preserve the actual experiment, including local server paths. Rerunning the guest requires the exact frozen runtime/Core inputs named by their manifests; this compact bundle includes their hashes and provenance rather than the binaries and large source corpus. No further policy sweep was performed.
