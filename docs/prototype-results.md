# THC: Cadenza runtime port and typed constructor data

Historical kernel snapshot, recorded 2026-09-22 before the ordinary Map example and strict lifted-field support. The measurements and implementation limits below describe that stage. See the [current build and status](../README.md) and [Map report](map-example.md) for later work. Cadenza itself remained unchanged.

## What changed

Lexical bindings use indexed Truffle frames with primitive slots. Closures capture only free locals in final StaticShape fields; closed closures and empty PAP prefixes are shared. Self-tail loops restore both arguments and captures while preserving bloom ancestry. Recursive closures retain their indirection cells, but initialized local slots publish direct values.

Application follows Cadenza's generated exact/under/overapplication specializations, three-target direct caches, indirect fallback, packet convention, Java vararg bridge and tail trampoline. Haskell adds lazy update/blackhole handling and forcing before overapplication. Failed-thunk rethrows deoptimize before Kotlin exception machinery; that cold-path graph expansion is gone.

Constructors now have fixed StaticShape layouts derived from actual GHC primitive representations. `Cons` stores direct head/tail references; the fixture's `Box Int#` stores a direct long; `Nil` is shared. Cases compare constructor-layout identity and restore primitive fields directly. There is no per-cell field array and no stored Long wrapper for an Int# field. Void/coercion slots consume no payload storage. Unsupported or unresolved field representations fail explicitly.

GHC supplies a context-aware safe-evaluation certificate using `exprOkForSpecEval`, excluding enclosing recursive groups as CorePrep does. This eliminates artificial thunks around safe constructor/PAP arguments without strictifying divide-by-zero or recursive dictionary knots. `exprIsHNF` is retained as the fallback for earlier exports.

## Actual compiled representation

The [graph report](graph-inspection.md) links original BGVs, full scheduled phase graphs and actual-node renderings. The summation worker and warmed host both pass a structural gate: primitive i64 recurrence phis, with no allocation, boxing, heap loads or calls in the continuing summation blocks. Its previous Env/Object-array backedge allocation is gone.

The list builder's actual graph contains four allocations per element: **Cons + direct-long Box + tail Thunk + typed capture**. After mid-tier lowering there are four NewInstances and no NewArray or allocating Long box. Lazy list elements still allocate; the thunk and capture remain separate objects. Underapplication still crosses Cadenza's PAP allocation boundary and retains its copied prefix array. Changing-capture continuation steps now allocate a direct-long Box, but no argument thunk/capture; factory closures are initialized lazily and reused after update.

## Validation

At this snapshot, the full `scripts/try.sh` pipeline passed **16 tests**, including **19 real Haskell entries / 133 native-GHC oracle rows**, checked before and after installing last-tier guest code. All 19 entries execute compiled code. Tests also cover sharing, unused and demanded bottoms, memoized failures, productive cyclic lists, 100,000-step self/mutual/changing-capture loops, escaping recursive closures and thunks, 64-bit field/capture values, nullary identity, constructor discrimination, multi-stage PAPs and cache saturation.

Compiler regressions run under Core lint and verify total subtraction, literal nonzero division, unsafe division by zero, lazy bottoms/PAPs and recursive dictionary exclusion. The same sources build the native oracle with GHC -O2, Core lint and STG lint. Source and executable hashes are retained with the run.

## Warm timing comparison

**These ratios are estimates: background CPU contention prevented a clean hardware steady state.** Compilation was warm and stable, but timing windows varied substantially in both engines. Contacts, crash reporting and Spotlight CPU activity were observed during the run. No unrelated services were modified. The earlier, quieter frame-only pass measured Fibonacci at 1.17× GHC; the final pass below measured 1.64×. The Fibonacci kernel does not exercise the changed constructor layout, illustrating why these differences cannot be assigned to that change.

Apple M3 / 16 GiB / macOS 15.5; GHC 9.14.1; GraalVM 25.3.4.1 / JDK 25; Kotlin 2.4.20. Three fresh processes per workload. Each JVM warmed for at least 10 seconds and 20,000 calls, then ran five two-second windows. Native processes warmed for one second, then ran five two-second windows. Engines ran serially with counters disabled. The table uses the median of the three per-process medians.

| Workload | Native GHC ns/call | THC ns/call | THC / GHC |
|---|---:|---:|---:|
| `sumLoop` | 6,176.7 | 2,278.4 | 0.37× |
| `fib` | 170,185.2 | 279,711.1 | 1.64× |
| `under` | 9.4 | 61.6 | 6.58× |
| `caseList` | 501.6 | 1,792.5 | 3.57× |

The summation result is roughly 2.7× faster than native in this pass. Partial application and lazy lists remain slower. This measures host-invoked kernels, including Polyglot call overhead; the tiny partial-application kernel is especially sensitive to that boundary. Inputs vary over a 16-value cycle and every result contributes to a consumed checksum. Startup, loading and warmup are excluded.

All **120 measurement windows** lasted at least 2 seconds and matched native cycle checksums. There were **zero logged Truffle compilation/deoptimization events inside those windows**, and final last-tier verification caused no recompilation. This does not census all host JIT/GC events. The raw window/fork ranges and drift remain in the summary; the large outliers have not been silently discarded.

The earlier generic runtime [baseline](prototype-results-before-frames.md) and [first frame-only pass](../bench/results/cadenza-frames/summary.json) are preserved. The frame-only pass also suffered contention, particularly on lists. The final typed-cell pass supersedes the provisional 9.35× list ratio; its observed ratio is 3.57×, still requiring a quiet-machine confirmation before treating it as stable.

## Evidence and reproduction

- [All windows](../bench/results/cadenza-data/timings.tsv), [summary with ranges/drift](../bench/results/cadenza-data/summary.json), [validation](../bench/results/cadenza-data/validation.json).
- [Environment](../bench/results/cadenza-data/environment.json), [source/export hashes](../bench/results/cadenza-data/source-sha256.json), [binary hashes](../bench/results/cadenza-data/binary-sha256.json), [compiled-entry counters](../bench/results/cadenza-data/compiled-guest-counters.txt), [native oracle](../bench/results/cadenza-data/oracle.tsv).
- [Actual compiler graphs](graph-inspection.md), including the unchanged summation gate and the new typed-list allocation evidence.

```sh
scripts/try.sh
scripts/dump-graph.sh caseList 100
scripts/benchmark.sh bench/results/another-run
```

## Remaining implementation limits

At this snapshot: single guest thread; non-tail recursion and nested thunk forcing used JVM stack. The exported-Core subset did not provide general boot-library closure, ordinary Main/IO, FFI, strict lifted constructor fields, floating representations or multi-register constructor fields. These fixtures establish the tested runtime mechanisms, not application-wide Haskell compatibility or performance.
