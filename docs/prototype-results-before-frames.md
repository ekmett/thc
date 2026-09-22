# Baseline before the Cadenza frame and closure port

Historical baseline, recorded 2026-09-22 before the Cadenza frame and closure port. Validation counts, behavior and limits below describe that earlier implementation. See the [current build and status](../README.md) and [Map report](map-example.md) for subsequent work.

## What runs

A GHC **9.14.1** plugin exports optimized `ModGuts` after the Core pipeline and before Tidy/CorePrep/STG. THC reads executable JSON trees produced directly by the GHC API, links reachable definitions across source modules, and builds Truffle ASTs. It does not parse pretty-printed Core or replace fixture functions with handwritten JVM implementations. The native oracle compiles the same Haskell sources with `-O2`, Core lint and STG lint.

The runtime has ordinary updatable lazy thunks with blackhole detection; recursive bindings and productive CAFs; boxed algebraic data and cases; captured closures; exact, partial and overapplication; and a signed 64-bit kernel ABI. Cadenza contributes the three-target direct-call caches followed by indirect dispatch, exact-call packet specialization, Java varargs bridge, bloom-filter tail-call detection, trampoline and peeled self loops. There is no normalization/neutral path.

The host entry now resolves its binding and call target **once, at load time**. A bounded Truffle call-target cache handles subsequent calls. The old per-call name/map lookup has been removed from the execution path.

## Validation

At this snapshot, the complete `scripts/try.sh` pipeline passed **10 tests**, with no failures or skips. Sixteen actual Haskell entries produce **112 native-GHC reference results**, checked from a cold runtime and again after explicitly installing last-tier guest code. Counters confirm execution of compiled guest code for every entry; automatic JIT is allowed during initial checks.

Coverage includes closure capture, PAP allocation, overapplication, a shared thunk evaluated exactly once, unused divergent arguments/fields, productive cyclic data, cross-module calls, a five-target cache saturation case, and demanded blackholes. Self and mutual tail paths run for 100,000 steps, with a 10,000-element list traversal. The mixed self/mutual test catches loss of ancestor bloom bits. A 20,000-call host regression covers the previously failing bridge compilation. Strict lifted constructor fields are explicitly rejected by a GHC-exported regression fixture rather than silently given incorrect laziness.

An important implementation finding was Graal graph expansion through Kotlin-generated null-check failures and stack-trace sanitization. Generated parameter/call/receiver assertions are disabled; controlled explicit checks remain on runtime invariants and host arguments. Frame descriptors are allocated with ASTs, cold tail/error paths are profiled or deoptimized, and application shapes are specialized before packet allocation. Compilation errors remain fatal, with a 100,000 weighted graph-size budget and a 30-second compilation timeout.

## Steady-state timing method

Apple M3, 16 GiB, macOS 15.5; GraalVM 25.3.4.1 (Java 25.0.4.1), Kotlin 2.4.20. Three fresh JVM processes per workload. Each performs initial guest compilation, then **at least 10 seconds and 20,000 calls of warmup**, followed by **five two-second measurement windows**. Native GHC uses one second of warmup and five two-second windows per process. Engines run serially. Runtime diagnostic counters are disabled in timed runs.

Every call uses `base + (i & 15)` and contributes to a consumed checksum. Timed batches contain 256 calls. Different iteration counts are checked by comparing the exact checksum of each 16-input cycle across all engines, processes and windows. Inspection of optimized native Core confirms the dynamic fixture call remains inside each batch. This measures **host-invoked kernels**, including Polyglot `Value.execute` versus native dynamic-call overhead, and batch/timer overhead; it is not an isolated guest-instruction benchmark. Parsing, module loading, startup and warmup are excluded from reported windows.

Truffle compilation traces are saved alongside phase markers. The results below report the median of the three per-process medians (five windows each), rather than one short timing.

## Results

**THC remains slower on these kernels: 2.03–12.85× native GHC time per call.** Values are median nanoseconds per call; brackets give the minimum–maximum across all 15 windows per engine.

| Entry / inputs | Native GHC ns/call [range] | THC/Graal ns/call [range] | THC/native |
|---|---:|---:|---:|
| `sumLoop` / 10000–10015 | 5,055 [4,964–5,330] | 42,946 [42,812–46,262] | 8.50× |
| `fib` / 12–27 | 140,304 [139,952–147,223] | 284,967 [267,687–299,054] | 2.03× |
| `under` / 100–115 | 4.18 [4.17–4.45] | 53.69 [53.35–56.48] | 12.85× |
| `caseList` / 100–115 | 373.34 [372.56–377.05] | 4,537 [4,524–4,573] | 12.15× |

All **120 windows** completed, each lasting at least two seconds, with matching cycle checksums. There were **zero logged Truffle compilation/deoptimization events inside measurement windows**, and final verification caused no recompilation. This does not establish perfect JVM steady state: THC `fib` and `under` improved by up to 4.52% and 4.21% from first to last window within a process. Native windows also drifted. The traces do not census HotSpot host compilation or GC; engine order was always JVM then native, without thermal/frequency control.

## Remaining limits

- Single guest thread; every thunk is updatable. No concurrent blackholing, STM, async exceptions, or one-shot thunk specialization. Blackholes are prototype diagnostics.
- Tail calls have bounded host-stack behavior on the tested paths. Non-tail recursion and nested thunk forcing still consume JVM stack; small Fibonacci tests do not establish general stack safety.
- Exports cover supplied source modules and their source imports. Preinstalled boot libraries, ordinary Haskell `Main`/IO, FFI, and a general runtime service layer remain absent.
- Strict lifted constructor fields, unresolved representations, unsupported constructor layouts, literals, and primops fail explicitly. General GADT/coercion support, floating operations, join-specific lowering, and fusion are not validated here.
- These OPAQUE-annotated fixtures preserve runtime mechanisms deliberately. They do not establish application-wide Haskell compatibility or representative performance.

## Reproduce and inspect

Use the prerequisites in [the project README](../README.md). Save a fresh run separately:

```sh
scripts/try.sh
scripts/run.sh selfMutualTail 100000 --compile
scripts/benchmark.sh bench/results/recheck
```

Raw evidence: [all windows](../bench/results/steady/timings.tsv), [summary](../bench/results/steady/summary.json), [environment](../bench/results/steady/environment.json), [validation](../bench/results/steady/validation.json), [compiled-entry counters](../bench/results/steady/compiled-guest-counters.txt), [native oracle](../bench/results/steady/oracle.tsv), and [source hashes](../bench/results/steady/source-sha256.json). Per-process TSVs and phase/compilation logs are in [the recorded run directory](../bench/results/steady/). See [fixture details](../examples/README.md) and [export schema](../compiler/README.md).

The subsequent [actual Graal graph inspection](graph-inspection-before-frames.md) finds residual Env/Object-array materialization and Long boxing on the summation backedge, including after the warmed host entry inlines all guest calls.
