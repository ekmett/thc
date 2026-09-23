# Int-thunk graph findings

The captured final-source caller allocates the intermediate `I#` in `inline-copy`, while `inline-direct` has no `I#` allocation. The preserved screening source produces a different caller graph that eliminates `inline-copy`'s intermediate `I#`. The graphs and the allocation counter agree in both revisions. Removing the result indirection also leaves state dispatch in the mixed consumer, and a published explicit `I#` takes a shorter runtime path than the finalized primitive thunk.

## Evidence scope and source revisions

This inspection reads the existing BGV-derived GraphInspect JSON and run logs; it does not execute the guest or create another timing sample. `graphs/structural-summary.json` now covers 54 graphs: 9 captures × producer/batch × before-high/after-mid/after-low. The detailed allocation and read evidence below uses `graph-00005.json` (`2: Before phase HighTierLowering`) and `graph-00007.json` (`4: After mid tier`) inside each named parsed compilation directory.

The final source is `src/main/kotlin/thc/runtime/IntThunkExperiment.kt`, SHA-256 `4056f5f1bb51b66d6e101edd1b735a40543eb28cc793946960545dd85d7f4b02`. The preserved screening source is `bench/experiments/int-result-thunks/screen-version/IntThunkExperiment.kt`, SHA-256 `a8c74c68989e4fa36d8821aeec49c4019076c8fee1977fc5ade7baa14e356aad`. The diff adds the `partial-half` scenario, its stride, and its expected-count/checksum/accounting cases. The force-all source-level operation remains the same, but the compiled methods are different source revisions. The screening capture also passes readRepeats=4 rather than final-source readRepeats=1; force-all selects one repeat independently of that option.

`ExperimentalIntThunk.kt` is identical in both revisions: SHA-256 `7c7701132e6c25b2eea6ca57f89b8c89e8dec063c35b53b7db7f9e627f41e945`. This is evidence of allocation-elimination sensitivity to these captured caller compilations, not proof of nondeterminism with identical source/options or a diagnosis of the exact compiler phase responsible.

## Allocation evidence

All paths below are relative to `graphs/`. A compilation ID identifies the `parsed-TruffleHotSpotCompilation-ID[IntBatchRoot@…]` directory. Static site counts are not dynamic allocation counts; loop peeling/unrolling yields 19 thunk/carrier sites and 19 capture sites in each creation graph.

| Capture | Batch compilation | Before-high nodes / blocks | After-mid nodes / blocks | I# committed objects / NewInstance sites | Measured bytes per allocated cell |
|---|---|---:|---:|---:|---:|
| `force-all-ordinary` | 1878, `IntBatchRoot@62deb540` | 1177 / 209 | 4605 / 598 | 1 / 1 | 64.0008544921875 |
| `force-all-inline-copy` | 1876, `IntBatchRoot@7aefa27` | 1160 / 203 | 4547 / 594 | 1 / 1 | 72.0008544921875 |
| `force-all-inline-direct` | 1845, `IntBatchRoot@1c30adc5` | 1170 / 206 | 4556 / 597 | 0 / 0 | 56.0008544921875 |
| `partial-half-inline-copy` | 1866, `IntBatchRoot@a9b8ad9` | 1161 / 203 | 4547 / 594 | 1 / 1 | 64.0008544921875 |
| `screen-source-force-all-inline-copy` | 1883, `IntBatchRoot@4d436a09` | 1235 / 201 | 4810 / 631 | 0 / 0 | 56.0008544921875 |

The before-high committed `I#` is node 6917 / virtual 4936 in ordinary, and node 6912 / virtual 4939 in final-source copy and half-copy. Their after-mid `NewInstanceNode`s are respectively 18995 (block 577), 18974 (block 570), and 18977 (block 570). No such committed object or allocation node appears in direct or screen-source copy.

The generated class is `com.oracle.truffle.api.staticobject.GeneratedStaticObject$$1` in these JVMs, identified as `I#` through its sole `field__0` long and allocation source, not merely the numeric suffix. Its source chain is generated factory → `DataLayout.allocate` (`DataValues.kt:150`) → `DataLayout.createLong` (`DataValues.kt:143`) → `IntProducerRoot.execute` (`IntThunkExperiment.kt:50` in final source) → the inlined Truffle call → `Calls.direct`. `GeneratedStaticObject$$2` is the capture carrier, with `capture__0__primitive`, `capture__1__object`, and `CapturedFrame.layout`. Generated suffixes are JVM-local.

The final-source and screen-source boxed producer standalone graphs each allocate one `I#` (producer compilation IDs 1784 and 1782). The screen-source batch inlines that producer and eliminates its allocation; the final-source batch inlines it and keeps the allocation. The direct producer (1757) and direct batch (1845) have no `I#` allocation. Both copy caller graphs have no residual producer invoke; their remaining explicit calls are exception/class-name/stack-trace machinery. Thus absence of the screen caller allocation is not explained by moving the same allocation into an out-of-line producer.

The final-source hot-path accounting is consistent with ordinary thunk 24 + capture 24 + I# 16 = 64 bytes, candidate 32 + capture 24 + I# 16 = 72 bytes, and candidate 32 + capture 24 = 56 bytes. Half-copy adds a 16-byte `I#` for half the cells, yielding 64 bytes per allocated cell. These are allocation totals, not retained-heap sizes: the copied temporary box may subsequently die.

All nine captured batch graphs have zero committed `Object[]` packets before high tier and zero `Object[]` NewArray sites after mid tier. The direct producer's three-slot call packet versus the boxed producer's two-slot packet does not remain as a materialized guest call array in these captures. Each batch has one after-mid `java.lang.Long` allocation site for its boxed return/host boundary; that is distinct from the generated `I#`. Cold exception-related arrays and instance sites remain and must not be counted as allocations on every element.

The four final-source allocation diagnostics have raw `allocatedBytes / allocatedCells` values 1136671560 / 17760256 (ordinary), 1222129832 / 16973824 (copy), 884487352 / 15794176 (direct), and 1472220360 / 23003136 (half-copy). Screen-source copy is 1119371960 / 19988480. The common fractional term is exactly 56 bytes per batch call divided by 65536 cells. These are single 100ms diagnostic samples, not additional performance evidence. The independently audited final-source confirmation matrix has 27/27 valid logs and agrees on ordinary/copy/direct allocation totals 64/72/56. Do not combine the screen-source copy56 result with the final-source confirmation timings.

## Repeated reads of still-rooted cells

In final-source `reread-ordinary`, batch compilation 1870 (`IntBatchRoot@67ddfa61`), after-mid graph has 1697 nodes / 260 blocks. The ordinary path loads root-array element 5232, `Thunk.state` 5234 (block 241), `Thunk.value` 5236 (block 243), then `I#.field__0` 5237 (block 249). The result-object dependency is explicit:

`5236 Thunk.value → 5169 compression → 3061 ValuePhi → 3477 Pi → 5171 address → 5237 I#.field__0`.

The phi also has a direct-value input because the mixed consumer supports an already explicit `I#`; the actual ordinary reread roots remain evaluated ordinary thunks. Its ordinary state switch is node 3049 with keys [1,2,3], and state 2 takes the result-value branch.

In final-source `reread-inline-direct`, compilation 1838 (`IntBatchRoot@57bea848`), the graph has 1669 nodes / 253 blocks. It loads root-array element 5119, candidate state 5120 (block 10), and candidate payload 5122 (block 13). Payload depends directly on the root object:

`5119 root → 4379 compression → 3388 Pi → 4383 address → 5122 payload`.

There is no `Thunk.state` or `Thunk.value` load. The `REF_RESULT` alternative still loads candidate `entryOrFailure` 5123 (block 14), then `I#.field__0` 5125 (block 236); preserving an existing shared `I#` does not remove its indirection. The candidate state switch (2980, keys [1,2,3,4]) first merges READY_INT and REF_RESULT, followed by equality 2988 / If 2987 distinguishing them. The captured READY_INT read therefore removes the dependent result-object load but still pays candidate state dispatch. Graph structure alone does not promise a latency win.

## Reads after publishing the forced value

Final-source `published-read-ordinary` compilation 1868 (`IntBatchRoot@67ddfa61`) and `published-read-inline-direct` compilation 1854 (`IntBatchRoot@740f290b`) have the same relevant after-mid structure and IDs: 1669 nodes / 253 blocks, root element 5122, candidate state 5123, direct `I#.field__0` 5124, candidate payload 5125, candidate `entryOrFailure` 5126, and referenced `I#.field__0` 5128. Neither has an ordinary `Thunk.state` or `Thunk.value` load. This describes matching structure, not byte-identical complete JSON or machine code.

The setup publishes `forceValue` into the root array. For ordinary mode, entries become explicit `I#` objects, so execution takes the noncandidate arm of If 2975 to the direct field read 5124 (block 241). For direct mode, entries remain finalized candidates, so execution takes candidate block 10, reads state 5123, dispatches switch 2983, then equality 2991 / If 2990 to payload 5125 (block 13). The direct mode thus carries extra state checks even though its payload is in the same object. Both compiled graphs retain both alternatives because the consumer accepts either representation; counting identical static graphs would miss the different actual route.

The graph supports the mechanism for a slower published candidate read, but does not isolate a quantitative cause of the measured ratio. Different cell footprint/cache locality and generated machine code can matter too. Graph relative-frequency/default-probability fields are not measurements of these workloads' branch frequencies.

All read-only diagnostic runs allocate about 56 bytes per root call, with no per-cell creation: reread ordinary 62104/1109, reread direct 63896/1141, published direct 63112/1127 are exactly 56 bytes/call. Published ordinary is 125704/2241 = 56.0928157 bytes/call, a 208-byte total excess that should not be interpreted as a per-read allocation.

## Boundaries of the conclusions

This demonstrates the intended allocation saving for the private direct-result ABI in this genuine Truffle harness. Copying an ordinary result can sometimes reach the same allocation total through caller optimization, but the preserved final-source graph shows it is not assured. Neither observation supplies generic THC compiler integration, an ABI for arbitrary producers, universal elimination guarantees, or a general runtime performance win. The read graphs show that in-place payload and cheap already-published constructor reads remain different design objectives.

The extraction helper was adjusted to match actual BGV root names (`IntBatchRoot`/`IntProducerRoot`) and follow the read address association and compressed-reference edge. These were analysis-tool corrections only. No experiment source, guest build, or runtime process was changed by this inspection.
