# Baseline bytecode and typed-frame candidate: compiled graphs and allocation

These captures use the same current Map Core corpus and default policy (penalty-free recursion depth 2, both budgets 12,000). The candidate is `work/typed-frames-v4`, JAR `df636064bc4f104fd5755d2f23416405790df9efd7da16ee6cf342fb12a98d44`. The baseline diagnostic composite `work/bytecode-baseline-current-core` combines unchanged original source-notes-v2 libraries/source (JAR `8f4d77ad37da5bd4d21295e625d43067bc114e085d7af1df25204e74b77fc23a`) with the candidate4 Core/native corpus. Its manifest names both origins; neither original frozen directory was changed.

Exact allocation falls **9,575,492.5→8,329,466.5 bytes/workload (−13.0127%)**. All three 256-call samples agree for each runtime, after at least 12,000 calls and 15 seconds of warmup. Native checksums, installed guest code, source attribution, zero traps, and clean allocation/JFR/final verification windows pass.

Candidate sampled allocation weight is Bin 73.29%, Object[] 11.75%, JVM Long 10.27%, I# 4.32%, Thunk 0.18%, captures 0.17%. Actual bytecode-layout factory inspection proves generated class identities: Bin has primitive size, Object key/value, and DataValue child fields; I# has a primitive Long; the hot capture has one Long and two DataValue fields. JFR total weight agrees with exact projected allocation within 0.0033%; this agreement does not turn class shares into exact counts. Both profiles retain full class/site/stack tables and raw JFR.

The frozen native binary accepts RTS statistics. Three independent (512−256)/256 slopes equal **7,074,185 native heap bytes/workload**. The 256−0 slope is about 136 bytes/call higher, consistent with startup overhead. GHC counts the whole process; the JVM counter counts the calling thread. Raw commands/checksums/statistics remain in `native-allocation/`.

## Actual graph changes

Eight roots match by exact source location plus name, including worker 27→23 and entry 30→26. A ninth baseline argument-thunk root is reported separately; absence of a separate compilation is not by itself proof of no dynamic thunk work. Across the eight common roots: **RecCell tests 385→0; Thunk tests 90→12; JVM Long allocation sites 148→77; guest calls 152→172**. All 172 candidate guest targets are constant. More exposed branching calls can coexist with fewer dynamic allocations.

| Role | Mid-tier nodes old→new | Guest calls old→new |
|---|---:|---:|
| lambda def, ww, ds1 @ vendor/containers-0.8/src/Data/Map/Internal.hs:649 | 860→375 | 0→0 |
| lambda k, x, l, r @ vendor/containers-0.8/src/Data/Map/Internal.hs:4272 | 878→396 | 0→0 |
| lambda k, x, l, r @ vendor/containers-0.8/src/Data/Map/Internal.hs:4302 | 13091→7653 | 29→35 |
| lambda n @ examples/THC/MapWorkload.hs:30 | 227→191 | 1→1 |
| lambda sc, x, ds1 @ vendor/containers-0.8/src/Data/Map/Strict/Internal.hs:497 | 10068→7861 | 35→42 |
| lambda ww @ n/a | 23452→16859 | 53→77 |
| lambda ww, ds @ vendor/containers-0.8/src/Data/Map/Internal.hs:3426 | 11955→996 | 18→1 |
| lambda ww, ds2 @ vendor/containers-0.8/src/Data/Map/Strict/Internal.hs:649 | 10055→3517 | 16→16 |

The candidate has 172 Object[] packet sites and no Long[] thunk packets. The common baseline has 134 Object[] plus 18 Long[] sites. Lookup retains zero calls/packets/JVM Long boxes, with one Haskell I# data-cell allocation; it is not allocation-free.

Candidate Long sites: 74 generated `handleToLong$Apply_`, two `handleLoadLocal$Long`, one `handleMergeConditional$Long`; no bloom-origin late Long allocation. A concrete fold path commits Object[][boxed zero header, boxed primitive accumulator phi 5289, typed DataValue child] at node 5467, and that array flows to residual callBoundary 5355. The zero header folds later; the accumulator box survives at the Object call boundary. The complete SSA path is retained in the packet audit; these node IDs identify only that captured compilation.

## Execution sampling limits

The candidate JFR contains 408 execution samples and 5906 allocation samples. Its first THC frames include generated `continueAt` lines 5155/5172 (229 samples), `Calls.direct` (82), `MatchData.matches` (55), and `Force.execute` (41). The generated line excerpts prove the leading locations are compiler-directive positions that PE can erase; **do not read 56.1% as interpreter dispatch cost**. Sampled stacks also attribute some allocation to callers such as Calls/MatchData after inlining/materialization. Actual graph source origins establish the allocation operations more precisely. Execution counts remain distinct from weighted allocation bytes. Half the execution stacks were truncated by JFR; raw recordings and explicit truncation counts are retained.

Source matching compares `data.layout == layout`; StaticShape property reads additionally contain shape-class guards. Determining whether a constructor match can safely establish its storage class is a concrete follow-up. No runtime change is implied by these measurements.

## Evidence

`bytecode-diagnostics.json` aggregates counters, class weights, execution summaries and graph totals. `bytecode-graph-comparison.json` preserves matched and unmatched roots. Each capture directory contains exact commands/input hashes, validation, selected root identities, packet audits, raw BGVs and parsed PE/inlining/before-high/after-mid graphs. BGVs retain complete source positions; parsed diagnostic text bounds repeated source stacks. Graph capture and JFR were separate from timing, and static-site counts need not be identical to timing-process compilations. The reproducible helpers are recorded in each capture config. The two shape-mapping helpers use actual bytecode programs and no workload execution.

## Published evidence and reproduction

This directory is a compact snapshot of the **original bytecode → typed-frames-v4** comparison. The parent directory name does not mean the comparison isolates entry contracts: it includes the cumulative call-path, exact-field and typed-frame changes through candidate4. It predates the owned-storage experiment and is independent of its later rejected low-battery timing run. No throughput number is inferred from graph or profile captures.

- [Matched roots and exact counters](bytecode-graph-comparison.json), [allocation and execution summaries](bytecode-diagnostics.json), and the [fold call-packet SSA slice](candidate/fold-packet-ssa.json).
- [Candidate graph capture](candidate/graphs/capture-config.json) and [baseline graph capture](baseline/graphs/capture-config.json) preserve exact commands, input hashes and output hashes. Neighboring validation files retain zero traps, source attribution and warmed installed-code checks.
- [Candidate allocation capture](candidate/allocation/capture-config.json), [baseline allocation capture](baseline/allocation/capture-config.json), and [native allocation slopes](native-allocation/summary.json) retain separate measurement scopes. Shape mappings come from actual factory allocation, not guessed generated class numbers.
- [Raw artifact references](raw-artifacts.json) name all **24 BGVs (622,104,094 bytes)** and both JFRs. They remain at the original `work/` paths. **Compressed BGV publication is pending**; these are provenance references, not downloadable archives. Complete parsed graphs, packet paths and sampled hot stacks also remain in the original captures.
- [Original tool source inventory](tools/original-files.json) records exact, unmodified source copies below `tools/original/`, including capture orchestration, graph parsing, packet tracing, JFR profiling and factory inspection. [Manifest](manifest.json) hashes every file in this compact publication except itself.

Run the original capture helper from a checkout containing the frozen runtime/Core directories, with all listed original helper sources restored at their recorded repository-relative paths. `capture-config.json.commands` supplies the exact executed commands. The top-level invocations were:

```sh
JAVA_HOME=/Users/ekmett/cadenza/.toolchains/graalvm-25.3.4.1+1.1/Contents/Home
python3 work/perf-sprint-call-paths/tools/diagnose.py graph work/typed-frames-v4 NEW_CANDIDATE_GRAPH_DIR --java-home "$JAVA_HOME" --backend bytecode --depth 2 --budget 12000 --oracle work/perf-sprint-typed-frames/oracle16.tsv
python3 work/perf-sprint-call-paths/tools/diagnose.py profile work/typed-frames-v4 NEW_CANDIDATE_PROFILE_DIR --java-home "$JAVA_HOME" --backend bytecode --depth 2 --budget 12000 --oracle work/perf-sprint-typed-frames/oracle16.tsv
```

For the baseline, substitute `work/bytecode-baseline-current-core` and fresh output directories. The capture helper verifies the frozen manifest before and after use, requires a new output directory, warms for at least 12,000 calls and 15 seconds, validates the 16-input native checksum cycle, and rejects compilation/deoptimization events during measurement and final verification. It fixes both inlining budgets at 12,000 and penalty-free recursion depth at 2; this depth is not a hard recursion cap. Shape and JFR postprocessing commands are recorded in [diagnostic provenance](diagnostic-provenance.json). The old shape helper is specific to these runtime snapshots; later allocation-key constructor APIs need the updated helper.

The copied sources are archival: no JVM, BGV reparse, compression or new benchmark was run while assembling this publication. Numeric graph summaries compare the latest successful compilation of each root within each capture; cross-run matching uses normalized source plus name, with unmatched roots explicit. Compiler decisions may differ in an uninstrumented timing process.
