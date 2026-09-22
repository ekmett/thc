# Removing avoidable call packets

Specializing thunk calls before constructing their argument packet removes the packet arrays that survived across inlined calls. The retained change reduces measured allocation by **327,000 bytes per Map workload, or 2.84%**. Its controlled timing comparison shows **no demonstrated CPU gain**: the candidate/baseline ratio is 0.9948, within the observed variation.

This report's first candidate is commit `9a60b99b5a4a2196dd5184e783c7e3f852444a15`, compared with baseline `60148ed860a93c8846201c325abc8ec83aee7aaf`. Graph capture used the frozen candidate JAR snapshot, independently of subsequent working-tree experiments. [Source and JAR hashes](call-packet-graphs/phase1-provenance.json) identify that exact capture. The Haskell data representation is unchanged. The two subsequent bloom-box experiments described below were rejected. The intermediate restoration commit `69b366aaacac5c1da9292632d98a6aedc80faba1` has runtime sources byte-for-byte equal to phase one, and its rebuilt runtime JAR has the identical SHA-256 `d1dd45b7afb8fbcc30d8c5e7712201adb6e1ce04d7122505ac0c402a92f6a3f7`. These measurements describe that exact phase-one binary. The subsequent [call-target inlining investigation](map-inlining.md) traces a shared-profile counting problem and evaluates a separate runtime control-flow candidate.

## The avoidable allocation

The old `Force` implementation first selected a one- or two-element packet, depending on whether the thunk had captures, then dispatched the call. The two array shapes merged before the target specialization. Graal retained that merged array even when it fully inlined the callee.

In the original lookup graph, compilation 2757 / graph 5, commits **8086 and 8089** create length-one and length-two packets and feed phi **6750**. The peeled copy uses commits **8102 and 8105**, feeding phi **3450**, which has a real indexed load at **7560**. All four arrays survive after mid-tier lowering despite zero residual calls in this lookup graph. These paths run when forcing a previously unforced result thunk; ordinary tree descent was already allocation-free.

The new `DispatchThunkTarget` specializes on the target and capture presence before building a fixed-size packet. It retains the three-target direct cache, indirect fallback, optional environment slot, bloom header, lazy update, and tail-call handling. Once the call inlines, scalar replacement can remove the packet. [The implementation](../src/main/kotlin/thc/runtime/Application.kt) preserves the existing calling convention.

This was distinct from `Application`'s temporary argument-value array, which was already eliminated in the inspected compiled Map roots. The Java call bridge also already avoided Kotlin spread-argument copies. Neither needed another representation change.

## Compiler evidence

Counts below are actual `Object[]` allocation sites **after mid-tier**, after array allocation lowering. They are static sites across all retained paths, not dynamic allocations per loop iteration. Runtime root IDs disambiguate roots with identical display names; both captures execute the same exported program.

| Root | Baseline compilation | First candidate compilation | Baseline arrays | Candidate arrays |
|---|---:|---:|---:|---:|
| Insert | 2568 | 2600 | 28 | 18 |
| Adjust | 2708 | 2730 | 8 | 8 |
| Lookup | 2757 | 2780 | 4 | **0** |
| Weighted fold | 2794 | 2808 | 21 | 18 |
| Query loop | 2813 | 2831 | 16 | **0** |
| Range loop, root 31 | 2829 | 2849 | 37 | 28 |
| Balance, root 10 | 2816 | 2834 | 0 | 0 |
| Balance, root 15 | 2844 | 2856 | 22 | 18 |
| Entry | 2880 | 2888 | 1 | 1 |

The [lookup gate](call-packet-graphs/phase1-summary.json) passes: zero late Object-array allocations and zero residual method calls. Both lookup and the query loop lose all of their packet arrays. Every selected phase-one candidate root has zero surviving arrays originating in thunk forcing. Inlining decisions also change slightly, so differences in total counts should not be interpreted as a fixed number of packets removed from each dynamic call.

The remaining insertion arrays flow directly into residual `callBoundary` invocations. For example, baseline commit **30227**, virtual array **18899**, length four, reaches call target **24996** through allocated object **30228**. The fold also retains a packet stored in an escaping `TailCall` object. These arrays serve the current Truffle call/exception ABI; they are not a redundant intermediate copy. Reusing a node-owned array would require resolving recursive reentry and escaping tail-call ownership first.

[Selected candidate BGVs](call-packet-graphs/phase1-selected-bgv.zip) preserve the insert, lookup, and query-loop graphs. The [full baseline/candidate packet trace archive](call-packet-graphs/phase1-packet-traces.zip) records exact source positions, lengths, alias paths, and sinks for every selected root. The [summary](call-packet-graphs/phase1-summary.json) is readable without unpacking the larger traces. The baseline BGVs remain in the [Map graph archive](map-graphs/final-selected-bgv.zip).

## Measurement and validation

The first candidate passed **39 JVM tests**; the restored version passes **41**, including additional compiled ancestry and conservative tail-bounce regressions. Both pass all **18 Map inputs before and after requested last-tier compilation**, with zero unsupported traps. The Map workload still uses explicit diagnostic mode; the unresolved dependency frontier described in the [Map report](map-example.md) is unchanged.

| Measurement | Baseline | First candidate | Change |
|---|---:|---:|---:|
| Current-thread allocated bytes/workload | 11,496,058.72 | 11,169,058.72 | **−2.844%** |
| Median workload time, controlled rerun | 3.066937 ms | 3.051040 ms | −0.518% |

Allocation uses `ThreadMXBean` after at least 15 seconds of warmup and requested compilation, with three windows of 256 changing-input calls. It includes host entry work on that thread, excludes allocations by other threads, and measures volume rather than retained heap. [Allocation samples and method](../bench/results/map-thunk-packets/allocation-summary.json) are retained.

Timing used three fresh processes per engine and five two-second windows per process, rotating baseline, candidate, and native execution order. All **45 windows** passed checksum and compilation-state validation. There were zero measured Truffle compilation/deoptimization events and zero unsupported traps. Candidate window times varied from 2.867 to 3.496 ms, so the 0.52% difference does not establish a speedup. Native GHC's median in this controlled run was 1.285420 ms; the candidate/native ratio was 2.3736. See the [summary](../bench/results/map-thunk-packets/comparison/summary.json), [validation](../bench/results/map-thunk-packets/comparison/validation.json), and [configuration](../bench/results/map-thunk-packets/comparison/run-config.json).

## Reproducing the graph check

[The packet audit tool](../tools/audit-call-packets.py) follows materialized array aliases to real indexed reads, residual calls, or escaping object stores. It separately counts the later allocation nodes, avoiding false allocation claims from pre-lowering boxing nodes. In particular, constant-zero bloom boxing disappears later; large bloom-mask boxes require separate analysis.

Run graph capture after throughput measurement has finished:

```sh
THC_DIAGNOSTIC_UNSUPPORTED=true \
scripts/dump-map-packets.sh work/graphs/map-packets
```

The script disables backend CFG dumps while retaining scheduled BGVs, then parses only the useful PE, inlining, before-high, and after-mid phases. Pass a prior packet audit JSON as the second argument for a comparison. `THC_GRAPH_CLASSPATH` can select a frozen runtime snapshot. A failing lookup gate returns nonzero rather than silently claiming the arrays disappeared.

## Rejected experiment: reusing the root's bloom-mask box

The rejected second candidate, `98c5e3ff15dd3886e3226cd99bc19d50426014c4`, reuses an immutable boxed copy of a function root's own bloom mask when the current mask exactly equals it. Inherited ancestry bits retain the ordinary boxing path. The calling convention and stored Haskell values are unchanged. This historical candidate passed **42 tests** and all **18 Map inputs before and after compilation**, with zero unsupported traps. [Frozen source/JAR provenance](call-packet-graphs/phase2-provenance.json) identifies the captured binary.

| Measurement | Original baseline, rerun | Combined candidate |
|---|---:|---:|
| Median workload time | 3.147179 ms | **2.897271 ms** |
| Current-thread allocated bytes/workload | 11,496,058.72 | **11,113,186.72** |

For this rejected candidate, the measured candidate/baseline time ratio was **0.92059**, an observed 7.94% reduction. Allocation volume is **3.330% below the original baseline** and **0.500% below the first candidate**. Native GHC measured 1.300037 ms in this run, giving a candidate/native ratio of **2.2286**. The same 45-window validation passed, with no measured Truffle events or unsupported traps. The baseline's second process was slower (3.480 ms median versus 3.147 and 3.019 ms), and one baseline window reached 5.081 ms. This desktop measurement therefore does not assign the entire timing gain to the small allocation reduction. [Timing summary](../bench/results/map-bloom-headers/comparison/summary.json), [validation](../bench/results/map-bloom-headers/comparison/validation.json), and [allocation method/results](../bench/results/map-bloom-headers/allocation-summary.json) retain the full evidence.

The own-mask optimization is visible in insertion graph **2613**: eight packet commits use the same preexisting `Long` constant **5433** as their header. Those paths have no dynamic header-box allocation. The complete root still contains **eight** late `TailCheck` Long allocations, down from twelve, plus six primitive-argument Long boxes. Lookup remains free of arrays and Long allocations.

Inspection also found an introduced cost on dynamic-mask paths. In query graph **2834**, nonconstant mask **183** is boxed at **16328**, merges with cached Long **1688** at object phi **1689**, and is immediately unboxed at **9704** in an inlined callee. A second path is **16345 → 9868 → 10888**. The query graph contains zero packet arrays but five late `TailCheck` Long allocation sites, where the first candidate had none. Two have the explicit value paths just described; others are associated with frame-state paths, so their presence alone does not establish a per-iteration allocation cost. This regression is why the candidate was not retained despite its observed timing result.

Static graph size also changes independently of allocation: insertion grows from 4,466 to 4,741 nodes before high-tier lowering, with 21 residual call boundaries in both captures. The query graph grows from 3,133 to 3,200 nodes. The second balance root instead shrinks substantially, with residual calls falling from 21 to 16. These differences reinforce why the timing gain cannot be explained by counting boxes alone. [Exact phase-two nodes, paths, and inventories](call-packet-graphs/phase2-summary.json), the [query BGV](call-packet-graphs/phase2-query-bgv.zip), and [full trace archive](call-packet-graphs/phase2-packet-traces.zip) preserve the observations.

## Rejected experiment: an early constant guard

Candidate `3d82a88ad155b0cfced239d500726fa176fc4f0c` guarded cached-header selection with `CompilerDirectives.isPartialEvaluationConstant(mask)`. This removes the dynamic boxed-object phi regression, but it also removes every intended shared-header improvement. The check runs during partial evaluation, before the later simplifications that expose the useful constant masks in these graphs.

In corrected insertion graph **2577**, all 18 committed packet headers still originate from boxing nodes; none references a shared cached `Long`. After mid-tier, twelve `TailCheck` Long allocations and six primitive-argument Long allocations remain, exactly as in phase one. Corrected query graph **2803** has no packet arrays and no `TailCheck` Long allocations, also matching phase one. Across all ten selected roots, packet counts, Long counts by origin, before-high and after-mid node counts, and residual call counts match phase one.

Measured allocation returns to **11,169,058.71875 bytes per workload**, exactly the phase-one measurement. All 18 Map inputs pass with zero traps. The experiment was rejected because it offered no retained graph or allocation benefit. The runtime was restored to the measured phase-one sources and binary; there was no further throughput run for that identical binary. [Corrected graph comparison and exact header nodes](call-packet-graphs/phase3-summary.json), [selected insertion/query BGVs](call-packet-graphs/phase3-selected-bgv.tar.xz), [source/JAR provenance](call-packet-graphs/phase3-provenance.json), and [allocation samples](../bench/results/map-bloom-headers-pe-constant/allocation-summary.json) preserve this negative result.

## Further execution information visible in Core

The graphs expose another opportunity without changing data layout: retain facts about values already evaluated to weak head normal form and values that cannot be recursive indirection cells. In phase-two lookup graph **2781**, strict `Bin` child-field loads **1015/1111** pass through loop phis and retain a `Thunk` test at **3868**, plus late state/value reads **8276/8277**. The corresponding local reads also retain possible `RecCell` handling. These child values are already evaluated by the constructor's strict-field contract.

That observation does not justify removing all forcing. The initial tree argument may be a thunk, and the map's lazy value field can legitimately require evaluation at return. Facts must survive lexical bindings and the self-call merge while preserving those entry/result paths. GHC Core provides useful information here; a demand signature by itself is not proof that an incoming value is already evaluated. This report identifies the opportunity and the concrete nodes; it does not claim that general strictness-based lowering has been implemented.

Two other observations need similar care. The range graph retains a primitive capture-restore arm even where the captured value is subsequently tested as a `Map` node; more precise binder representation facts could exclude that arm, but static presence does not establish a hot allocation. Conversely, the surviving `Long.longValue` calls in the fold follow residual guest calls on conditional return paths. They are not evidence of a redundant intermediate packet.
