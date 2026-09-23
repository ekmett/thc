# Actual THC tuple graphs

This experiment runs real GHC 9.14.1 exported Haskell through the production AST and bytecode runtimes. It performs fixed-input correctness checks and compiler graph inspection; it does not measure timing or throughput.

The recorded installed runtime jar was built from commit `7427ab0c48fada18431d4c892430a338292a1661` and remained unchanged throughout the graph batch. Source hashes are taken from that exact commit, rather than later working-tree edits. It ran on GraalVM 25.3.4.1 / JDK `25.0.4.1+1-LTS-jvmci-25.3-b22`, macOS AArch64. No guest call receives `forceInlining`, and the normal runs leave the compiler's inlining policy and graph limits at their defaults. The residual controls disable guest inlining and explicitly compile the reachable callees before compiling the selected caller.

`TupleRuntimeGraph.hs` contains opaque two-argument producers and scalar consumers. Both independent `Int#` inputs survive in Core. Cases exercise a direct pair, forwarding, two outstanding tuple results, mixed primitive/boxed fields whose boxed payload contains an unused bottom, and a directly unused bottom field. Native GHC supplies 55 oracle rows across zero, mixed signs, values beyond 32 bits, and signed 64-bit extrema. The checker requires retained exact producer calls and logical tuple-component metadata. No tuple implementation or aliases are synthesized.

The launcher loads only the selected entry's reachable exported bindings, warms all native rows a fixed number of times, compiles the exact entry, and checks every native row twice. It checks `isValidLastTier` after each compiled pass; a successfully installed graph that immediately deoptimizes does not pass. Instrumentation and diagnostic unsupported execution are disabled.

## Reproduce

Use a checkout containing the tuple runtime and current exporter. Build its pinned Core plugin and `installDist` first. If running this experiment from a separate checkout, set `THC_EXPORT_ROOT` and `THC_RUNTIME_ROOT` to the built checkout.

```sh
export JAVA_HOME=/path/to/pinned/graalvm/Contents/Home
export GHC=/path/to/ghc-9.14.1
export GHC_PKG=/path/to/ghc-pkg-9.14.1
export THC_EXPORT_ROOT=/path/to/tuple-runtime-checkout
export THC_RUNTIME_ROOT=/path/to/tuple-runtime-checkout
bench/experiments/tuple-runtime-graphs/prepare.sh /tmp/tuple-graphs/fixture
bench/experiments/tuple-runtime-graphs/run-one.sh \
  /tmp/tuple-graphs/fixture/core/TupleRuntimeGraph.json \
  /tmp/tuple-graphs/fixture/oracle.tsv pairCase ast inline /tmp/tuple-graphs/ast-pair
```

Run each of `pairCase`, `forwardedCase`, `outstandingCase`, `mixedCase`, and `lazyMixedCase` with both `ast` and `bytecode`, then repeat with `residual` in place of `inline`. Output includes the real BGV, scheduled graphs, backend CFG through final code analysis, native checks, source/artifact hashes, and a graph summary. Large graph files are deliberately not checked in.

`run-one.sh` uses the repository's `GraphInspect.java`. The summary identifies the selected entry's graph by its final explicit compilation: background/threshold compilation is disabled, and residual callees are compiled first. The inline auditor rejects carrier allocations, object/array allocation nodes, field stores, unknown memory reads/writes, and residual guest calls in the selected caller. It separately reports the required final scalar-result Long box. The residual auditor requires a real `OptimizedCallTarget.callBoundary` and typed `handoff__` field traffic.

## Observed result and limits

All twenty combinations (five entries, two backends, normal and disabled guest inlining) pass the native oracle and remain compiled after execution. In the ten normal-inlining configurations, tuple carriers and their field traffic disappear from the final caller graphs. The mixed case also removes the temporary Box allocation and never demands its unused bottom field. Final AArch64 LIR computes both dynamic leaves in scalar registers, subject to ordinary register allocation and spills.

The checked-in `captured/evidence.json` records the native checks, source/runtime artifact hashes, graph hashes, memory locations and phase counts. `captured/lir-excerpts.txt` shows actual post-allocation arithmetic and return instructions; full CFG/LIR is reproduced by the script.

| Entry | AST before/after lowering | Bytecode before/after lowering |
| --- | ---: | ---: |
| `pairCase` | 28 / 106 | 55 / 130 |
| `forwardedCase` | 28 / 106 | 55 / 130 |
| `outstandingCase` | 42 / 117 | 67 / 140 |
| `mixedCase` | 36 / 113 | 63 / 136 |
| `lazyMixedCase` | 28 / 106 | 55 / 129 |

“Before” is immediately before high-tier lowering; “after” is the final low-tier graph. Each normal-inlining pre-lowering graph has exactly one allocating box for the final Long and no surviving tuple carrier allocation, field access, or guest call. The bytecode graphs also retain virtual frame metadata for deoptimization; those nodes are not heap allocations.

The selected scalar entry still returns Object, so a final `java.lang.Long` box remains. Its TLAB/header/value stores are visible in low-tier graphs and are not counted as eliminated. The final LIR has `RETURN ... additionalReturns: []`: this is scalar replacement within the inlined caller, not a multiple-register machine-call ABI. Residual controls retain the typed slab and its ownership/generation accesses.

These graphs establish the behavior of these actual compiled entries, not the frequency with which arbitrary application calls inline. They do not replace protocol tests for one caller switching between fresh and residual results, polymorphic call sites, environments/PAPs, exceptions, or deoptimization ownership. Those require separate runtime tests. The earlier `tuple-return-contract` directory contains a standalone directive/materialization mechanism probe and must not be substituted for this production evidence.
