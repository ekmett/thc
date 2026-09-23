# One-step Force: Default

The one-step candidate measured 1.574656 ms/workload versus 1.588273 ms for the existing forcing loop (-0.857% elapsed time), with GHC at 1.341233 ms. This is a small difference from one controlled three-process comparison, not a substantial throughput claim. Both policies and every measured window are retained.

| Engine | Fork 1 median ms | Fork 2 | Fork 3 |
| --- | ---: | ---: | ---: |
| baseline | 1.588273 | 1.586198 | 1.589277 |
| candidate | 1.573962 | 1.578864 | 1.574656 |
| native | 1.343184 | 1.335962 | 1.341233 |

Only `thc/runtime/Force.class` differs between the decompressed runtime JARs. Both use the same dependencies, 17 frozen Core modules and native Linux binary. Caller-demand is absent/off. The [source patch](provenance/source.patch), [per-class comparison](provenance/jar-entry-diff.json), and all 179 passing test XMLs are retained. The Core was exported on macOS; GHC 9.14.1 built the native oracle on Linux using vendored containers-0.8. This is a bytecode throughput comparison; AST and bytecode each passed all 18 native-oracle inputs before and after requested compilation for both runtimes, with no unsupported traps.

Pinned GraalVM 25.3.4.1+1.1/JDK 25, i9-12900K Linux, affinity CPUs 0–15, `-Xms4g -Xmx4g`, compact headers, class-owned layouts on; typed cases, leading-case returns, separate constructor matching, unchecked storage and boxed-value cache off. Explicit `Default` policy, recursion depth 2 and both inlining budgets 12,000; source notes on and instrumentation off. Warmup requires both 45 seconds and 30,000 guest calls, plus 10 seconds for GHC. Three fresh processes per engine, five windows of at least two seconds per process, rotated serial engine order. All 45 windows passed native checksums and the no-Truffle-events measurement/final-verification guard. No power warnings were emitted. These guards do not prove the absence of host compilation, GC or unrelated machine activity. Raw [host telemetry](execution/host-samples.jsonl) and [driver](execution/driver.json) record governor, frequencies, temperature, load and CPU counters. Governor was left at `powersave`; no claim of perfect hardware isolation is made.

Run `python3 tools/verify.py` from this directory. It checks the exact inventory, archived harness, test XML totals, inputs/options, native checksum formula, all preflights, every raw window and recomputed medians. No JVM, source corpus or network is required. [Capture driver](tools/capture.py) and [harness](tools/compare-map-runtimes.py) retain the original absolute server commands and paths for replay with those frozen inputs.
