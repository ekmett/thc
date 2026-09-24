# Boxed Int and Char cache experiment

The optional `-Dthc.boxedValueCache=true` cache reduced allocation in this Map workload, but did not demonstrate a speedup. It remains **off by default**.

The same bytecode runtime JAR, Core corpus and JVM options were compared with just this flag changed. Median times were 1.517914 ms with caching off, 1.551316 ms with caching on, and 1.268840 ms for native GHC. The cache-on median was 2.20% slower. These are medians of three process medians, each with five measured windows. All 45 windows passed checksum and duration guards; all six JVM processes passed compilation-event and final installed-code guards. [Raw results and configuration](../bench/results/boxed-values/throughput/summary.json).

| Process fork | Cache off (ms) | Cache on (ms) | GHC (ms) |
|---|---:|---:|---:|
| 1 | 1.495495 | 1.485965 | 1.271703 |
| 2 | 1.517914 | 1.551316 | 1.262267 |
| 3 | 1.869822 | 2.010013 | 1.268840 |

The third JVM fork was substantially slower in both modes while native timing stayed near 1.26 ms. The machine was charging on AC throughout, with no recorded power warnings, but that does not exclude host JIT, GC or other contention. This run supports keeping the cache disabled; it does not establish a stable 2.20% regression. All windows, including that variation, are retained in the [timings](../bench/results/boxed-values/throughput/timings.tsv) and [power record](../bench/results/boxed-values/throughput/power-status.json).

Separate diagnostic runs measured allocation on the executing JVM thread, including host entry, after warmup: **8,329,466.5 bytes/workload off**, **8,115,653.5 on**, a reduction of 213,813 bytes or 2.567%. Each mode gave the same result in all three 256-call batches. These are whole `mapAggregate` invocations, not individual insertions. Allocation on other threads is not counted. The allocation samples precede JFR recording; diagnostic elapsed times are not benchmark results. [Allocation evidence](../bench/results/boxed-values/allocation/summary.json).

The cache handles only the actual pinned GHC constructors:

| Constructor identity | Primitive payload | Inclusive range |
|---|---|---|
| `ghc-internal:GHC.Internal.Types.I#` | `IntRep` | −16…255 |
| `ghc-internal:GHC.Internal.Types.C#` | `WordRep` | 0…255 |

[DataLayout](../src/main/kotlin/thc/runtime/DataValues.kt) constructs a private immutable table per layout. Every value passes through its owned StaticShape factory and constructor-class registration, and its primitive field is initialized once before publication. No table or value is shared across layouts. Array storage retains separate backing storage for each value. AST construction evaluates its operand once and calls the primitive path; bytecode construction uses the same layout cache. Out-of-range values and other constructors retain ordinary allocation. The cache does not alter thunk forcing or make lazy fields strict.

Those ranges match [GHC 9.14.1's RTS constants](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/rts/include/rts/Constants.h#L65-L69), but the mechanism differs. GHC's STG-to-Cmm constructor path substitutes precomputed closures for qualifying **literal** Int/Char payloads; variable payloads follow its ordinary allocation path. Its copying collector can subsequently redirect surviving small boxes to the static tables. THC's experiment instead performs a range/table lookup on dynamic construction. That can save allocations earlier, while adding a branch/load and potentially impeding scalar replacement. [Compiler path](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/compiler/GHC/StgToCmm/DataCon.hs#L330-L349), [collector path](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/rts/sm/Evac.c#L852-L879), [pinned source identities](../bench/results/boxed-values/ghc-sources/provenance.json). This concerns Haskell constructor boxes, independently of `java.lang.Long` call-ABI boxing.

Validation passed 153 tests with the default configuration, 153 with caching enabled, and 41 focused semantic tests with caching, constructor-class matching, unchecked owned storage and array storage enabled together. The new tests exercise real exported constructor identities, boundary values, full-width integers, Unicode payloads, separate layouts/contexts and compiled transitions between cached and uncached values. The full 153-test suite also passed separately with `-XX:+UseCompactObjectHeaders`. All 18 native-oracle Map inputs matched both before and after requested compilation in four separate configurations: default, cache enabled, constructor-class matching enabled, and compact headers enabled; every configuration recorded zero unsupported traps. These are compatibility checks, not combined-flag performance results. [Map commands, results and hashes](../bench/results/boxed-values/validation/map/checks.json). A subsequent fixture-only cleanup moved the Char metadata dependency into the ordinary `CBVAudit` export; its five focused tests also passed without changing the runtime JAR. [Test results](../bench/results/boxed-values/validation/test-summary.json).

The measured v7 JAR has SHA256 `a73ec0e731b96b63523933cafc876b5e1c64884e24a42ded1b504b60d5bb0d7d`. The [frozen manifest](../bench/results/boxed-values/validation/frozen-v7-manifest.json) records source, test, compiler, Core, native and library hashes; [the runtime patch](../bench/results/boxed-values/validation/runtime.patch) records the experiment against its base revision. Core and native inputs were copied unchanged from v6. Full runtime binaries and raw JFR are not bundled; their identities are retained.

The selected compiled graphs show two distinct cache effects. Lookup's one `I#` allocation becomes an exact generated-storage object constant (node 5338); that site has no surviving range test, table lookup or allocation. The worker has 50 dynamic table loads. In one concrete path, unsigned `(key + 16) < 272` selects cache-hit block 454 and existing object load 53330; miss block 455 allocates and initializes fresh `I#` node 68408. Both reach phi 32774. There is no cache-helper call on this path. [Scheduled SSA and CFG evidence](../bench/results/boxed-values/graphs/cache-ssa.json).

| Root | Mid-tier nodes off → on | Guest calls / packets off → on | JVM `Long` sites off → on |
|---|---:|---:|---:|
| Lookup | 375 → 365 | 0 / 0 → 0 / 0 | 0 → 0 |
| Fold | 996 → 996 | 1 / 1 → 1 / 1 | 2 → 2 |
| Worker | 16,864 → 17,362 | 77 / 77 → 75 / 75 | 37 → 35 |

The worker's `I#` allocation sites fall from 57 to 50, while `Bin` sites rise from 136 to 156 as the inlining shape changes. Its graph grows by 498 nodes, although emitted code shrinks from 153,340 to 152,316 bytes. These site counts use the recorded `NewInstance`/`NewArray` convention; other allocation-lowering nodes also occur. They are not independent dynamic object counts. Fold's observed counts agree, without an assertion of identical topology. [Counts and provenance](../bench/results/boxed-values/graphs/comparison.json). Lookup and fold have matching source locations; the worker has no source location and is matched through its unique in-capture label and recorded root identity.

All 50 dynamic cache-load results have a nullable, nonexact `DataValue` stamp. The exact generated-storage type of the fresh miss object is lost at the hit/miss merge. That precision loss is visible, but its cost is not established: the audited identity-preserving paths reach stored fields and call packets without a direct downstream null, type, layout or field check. The audit does not cross those heap/call boundaries. These graphs therefore do not show an added shape check or lost scalar replacement causing the timing result. The [graph review](../bench/results/boxed-values/graphs/README.md) preserves that distinction; a more precise table type remains an unmeasured follow-up, not an established fix.

The [compact evidence bundle](../bench/results/boxed-values/README.md) includes a Python-only verifier for hashes, all 45 timing windows, guest compilation guards and test totals.
