# Real Set diagnostic validation

This is a fixed-input correctness check of the existing native-GHC library artifact, with diagnostic execution explicitly enabled. It does not change the strict library support claim or measure performance. The covered 22 native rows execute without unsupported traps on both AST and bytecode after tuple-valued local join results are lowered into typed frame slots.

The captured run uses the unchanged GHC 9.14.1 / containers 0.8 exports and oracle from `build/libraries/cases.json`. All 46 artifact hashes match that manifest. Of its 79 source/input hashes, only the current audit implementation, capability file, and library preparation checker differ; the old/current hashes of those tools are recorded, and a separate current audit is generated. Actual exporter/library source changes fail the check. The original frozen audit and oracle are not overwritten. The installed runtime is recorded by jar hash and checkout commit: `2d40d8bc72a1fdf481ab5d6b50c553a44491990a`, which is main `e6a171d` plus tuple join commit `0ca363f7d174650ac25c1d9c5931d159f831038e` cherry-picked without changes. The captured host is macOS AArch64 with GraalVM 25.3.4.1 / JDK `25.0.4.1+1-LTS-jvmci-25.3-b22`.

## Reproduce

Build `installDist` from a checkout containing the tuple join result implementation. Supply an existing library manifest whose referenced source/export/oracle paths remain available. This experiment never regenerates the vendored sources or the library corpus.

```sh
export JAVA_HOME=/path/to/pinned/graalvm/Contents/Home
export THC_RUNTIME_ROOT=/path/to/built/runtime-checkout
bash bench/experiments/set-diagnostic/run.sh \
  /path/to/existing/build/libraries/cases.json /tmp/set-diagnostic
```

The script verifies the recorded artifact hashes, inventories the exact reachable joins, reruns the current static audit, checks every native row in an interpreter-only context, and then checks a fresh compilation-enabled context. It warms the three designated warm rows, explicitly compiles the exact `setAggregate` target, checks those rows with compiled-entry evidence, and verifies that target remains valid. It then runs the 19 cold rows, trains all 22 twice, recompiles the entry, and verifies every row again with a valid exact entry after replay. Cold discovery is allowed to invalidate an earlier compilation. Background/threshold compilation is disabled; normal inlining and graph limits are unchanged.

Every value check also requires diagnostic policy, a nonempty deferred unsupported frontier, zero unsupported traps, and zero blackholes. Strict loading must reject the same linked program. The interpreter run separately requires actual calls into both min/max helpers and `glue`. Results and hashes are written to `evidence.json`; full logs and the separate current audit remain in the output directory.

## Covered joins and observed results

The complete supplied `Data.Set.Internal` module contains four tuple-result joins. Only two are reachable from `setAggregate`; the two under `$wpoly_go` are excluded. Both reachable joins take one scalar argument and return two lifted components:

| Owner | Local join | Original source | Calls over 22 rows, each backend |
| --- | --- | --- | ---: |
| `main:Data.Set.Internal.$wgo` | `$j_shRd` | `maxViewSure.go`, lines 1795–1798 | 298 |
| `main:Data.Set.Internal.$wgo1` | `$j_shRt` | `minViewSure.go`, lines 1781–1784 | 271 |
| `main:Data.Set.Internal.glue` | — | Calls the min/max helpers | 622 |

Before the tuple join change, the runtime already had genuine pointer equality and tuple call results, but input `8` failed with `Expression does not produce a tuple`. The actual guest stack was `setAggregate → $waggregate → aggregate1 → $sdifference → merge_$smerge1 → glue → $wgo1`; this reaches the **minViewSure** join. The inventory also records the independent syntactic path through `$w$sgo4 → glue` to each helper.

After the change, all 22 rows pass in both backends, before and after explicit compilation. The exact warm and final compiled targets remain valid. Each complete diagnostic run records 214,017 local join transfers, zero unsupported traps, and zero blackholes. The transfer count includes other local joins and warmup/replay; it is not a count of just the two tuple joins and is not a performance result. Exact counts in the captured evidence are observations; the reusable assertions require positive helper calls and join transfers.

## Remaining frontier

Strict Set is still rejected. The current syntactic audit has 634 supplied bindings, 71 reachable bindings, three missing globals, and 17 issues: 13 aggregate-representation, two constructor-field-representation, one constructor-kind, and one unsupported primitive (`readMutVar#`). There are zero aggregate-boundary issues. The remaining paths enter exception/backtrace machinery, including unresolved/void tuple components and `State#` handling. Passing these 22 covered inputs does not execute or implement those paths.

The missing globals remain `GHC.Internal.Exception.$fExceptionErrorCall_$ctoException`, `GHC.Internal.Exception.Backtrace.collectExceptionAnnotationMechanismRef`, and `GHC.Internal.Stack.withFrozenCallStack1`, all in `ghc-internal`. Full identities and reachable paths are preserved in the captured evidence.

## Optional focused graph

```sh
bash bench/experiments/set-diagnostic/graph-min-view.sh \
  /path/to/existing/build/libraries/cases.json ast /tmp/set-min-view-graph
```

This mode trains the actual workload rows, compiles the real exported `$wgo1` target, and replays all 22 native rows, requiring the helper's installed code to remain valid and diagnostic traps to remain zero. It dumps the BGV and final backend CFG with normal inlining; no synthetic helper arguments or replacement bodies are used. It does not time execution. The helper itself returns a tuple across a real call boundary, so its completion slab and recursive callee traffic must not be mistaken for local-join carriers. Large graph binaries are not committed.

The captured AST graph passes all rows and remains compiled. A supplementary replay of the same unchanged runtime, with graph dumping disabled and a compiled-entry assertion added, records 359 compiled entries; this confirms that workload replay enters the compiled helper. It has 1,586 nodes before high-tier lowering and 3,487 in the final low-tier graph. The two outer component stores receive distinct Object `ValuePhiNode`s. No allocated object has the handoff carrier class, no local join is a residual call target or allocated packet, and no physical `VirtualFrame`/`FrameWithoutBoxing` slot memory remains. The six surviving `handoff__` accesses are source-attributed: two `FunctionBody → TupleShape.finish` stores at outer completion, two residual callee consumption reads, and two release clears. Thus the same-frame join results remain scalar SSA values until actual function completion.

This is not an allocation-free Set graph: it retains real tree nodes, thunks, the residual call's argument array, and ordinary instrumentation. Three exceptional guard groups remain, totaling nine reads: three each of `LocalJoinJump.target`, `LocalJoinTarget.group`, and `LocalJoinTarget.index`. The graph does not prove that every join guard disappears. It also does not establish a multiple-register machine-call return ABI. `captured/graph-evidence.json` records phase counts, the component value inputs, source-attributed accesses, remaining guards, and full graph/CFG hashes. The graph summary intentionally checks the captured AST structure; the bytecode diagnostic execution is covered by the separate 22-row checks.
