# Actual matching-root cycle graphs

These captures run the same synthetic `A → B → C → D → E → C` Core with instrumentation disabled, changing inputs, warmup and requested compilation. Every captured run verifies its checksum and installed guest code. They are graph experiments, not the Map throughput measurements.

| C root | Compilation | After PE nodes | Before-high nodes | After-mid nodes | Before-high loops | Late Object[] | Late TailCall | Late Long | Residual calls |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Old bytecode | 2267 | 113 | 110 | 348 | 0 | 1 | 1 | 5 | 0 |
| New bytecode | 2254 | 204 | 158 | 367 | 1 | 0 | 0 | 1 | 0 |
| New AST | 2115 | 189 | 111 | 161 | 1 | 0 | 0 | 1 | 0 |

All three inlining trees contain C, D and E as inlined targets. Inlining alone did not close the old cycle. In old C, commit node 2712 materializes `TailCall[...2679]` and argument array `2679 = Object[][null, n, acc]` together. AllocatedObject2713 is the exception input of Unwind2481. The old host-entry and host-bridge graphs contain the outer trampoline's executable nodes and retain argument arrays. Zero residual calls in the old C graph does not imply a native cycle there.

New bytecode C has LoopBegin2990 in B1 and LoopEnd2991 in B7, with B7 returning to B1. Its two loop-carried `i64` phis have these exact inputs:

- Phi3089 starts from Unbox227 and receives Add3401 = Phi3089 + (-3) on the backedge.
- Phi3090 starts from Unbox295 and receives Add3403 = Phi3090 + 3 on the backedge.

These are the remaining count and accumulator after inlined D/E return to C. The loop carries primitive values rather than an exception or packet. There are no residual calls, unwind nodes, TailCall allocations or Object[] allocations. Box3393 follows exit Merge1019 in B10 and feeds Return3051. Its lowered Long allocation4437 is also outside every cycle in the scheduled after-mid CFG. There is no per-iteration box in this C graph. The new entry root contains the same primitive cycle, and its host bridge no longer contains the trampoline code.

AST C has the corresponding LoopBegin353/LoopEnd1835 and `i64` Phi1945/Phi1946, whose backedge values are Add2299 = n - 3 and Add2301 = acc + 3. Its sole lowered Long allocation2556 is outside the loop. The two backends therefore implement the same important mechanism for this fixture. Their final graphs differ: the bytecode graph undergoes counted-loop strip mining and has more after-mid nodes. Node totals do not establish which loop runs faster. One remaining detail is ancestry loading: bytecode Unbox2102 lowers to Long.value FloatingRead4370 in B2 of the outer strip-mined loop; AST loads its bloom outside the loop. This is a surviving field read, not a Java call or allocation. No bodyIdentity field walk survives in the bytecode C graph.

The earlier standalone D compilations in the new runs still show a packet and TailCall escape, correctly targeting C rather than D. The claim here concerns C's final graph, its inlined D/E bodies, and the final entry/host graphs. It is not a claim that every separately compiled root has zero allocations.

[Old bytecode CFG](old-bytecode-cycle-C-cfg.svg), [new bytecode CFG](new-bytecode-cycle-C-cfg.svg), and [AST CFG](new-ast-cycle-C-cfg.svg) show the actual scheduled blocks, edges and compiler node IDs. For readability they omit debug-state, constant and bookkeeping labels; they are not source-level reconstructions. Full selected-phase JSON for each C root is under `cycles/*/C-graphs/`. [Machine-readable proof](cycle-graph-proof.json) records exact phi inputs, allocation positions, exception escapes and computed cyclic blocks. [Raw BGV archive](selected-bgv.tar.xz) retains the original graphs.

The candidate source files match commit `cb7488259e8418ee1fdff5c5d37569e40b44af29`; the candidate JAR is `570901a5300dac18d63fb7f93177b76992108e65b37f998fe6f412610045d7f0`. The old bytecode source matches `0ef73a049d0090613918479d44b8a01a8ecc4ae1`, JAR `aaa573a73d97cb1b198030064deef5966a4ec764e39d6a72f12d7134e293bb39`. [Manifest](manifest.json) records archive-member hashes and verifies this mapping. Subsequent thunk/writeback changes are outside these captures.
