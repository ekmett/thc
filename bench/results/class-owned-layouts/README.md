# Class-owned constructor layouts

With compact object headers enabled in both modes, moving each constructor's layout from the object to its generated carrier class reduced executing-thread allocation from **7,959,001.5 to 6,653,506 bytes per Map workload (16.4%)**. The instrumented factory objects shrink from 40 to 32 bytes for `Bin`, 24 to 16 for `I#`, and 16 to 8 for the shared `Tip` value. Captured frames are unchanged.

The same-JAR local throughput comparison measured 1.753779 ms with class ownership disabled, 1.487220 ms enabled, and 1.268367 ms for native GHC. That is 15.2% less elapsed time and 1.17× GHC in this run. Both JVM modes slowed across forks; all measurements are retained. This compares two modes of the new prototype, whose disabled mode adds a `LayoutDataValue` subclass. It does **not** establish a 15.2% improvement over the previous runtime. The Linux comparison measures that separately.

| Fork | Owned off (ms) | Owned on (ms) | GHC (ms) |
|---|---:|---:|---:|
| 1 | 1.580920 | 1.362211 | 1.263907 |
| 2 | 1.753779 | 1.487220 | 1.268367 |
| 3 | 1.873616 | 1.552333 | 1.324982 |

The bytecode backend, Core corpus, GraalVM version and JAR are fixed. The three other storage/cache flags are disabled. Each JVM warms for at least 45 seconds and 30,000 calls; native warms for 10 seconds. Three process forks each contribute five windows of at least two seconds, with rotated engine order. All checksums, final installed-code checks and measured Truffle-event guards pass. Absence of those events does not establish absence of host JIT, GC or machine contention.

The fieldless `DataValue` base authenticates construction without retaining its allocation key. A private `ClassValue` registry permanently reserves a carrier for one layout and publishes the completed descriptor. Expected-layout operations compare the object's class directly. The generic descriptor getter is a cold boundary; it is not the matching or field-access path. If a shared array-storage carrier is already owned, a new layout uses separate layout-bearing storage before publishing any values. Old class-owned values never change meaning.

- [throughput/](throughput/) contains every window, process log, command, input hash and recorded power state.
- [allocation/](allocation/) contains three exact 256-call executing-thread allocation batches per mode and separate sampled JFR summaries. Profile elapsed times are not throughput measurements; sampled allocation weights are not exact object counts.
- [graphs/](graphs/) shows the exact class-match-to-field-load path. All selected lookup/fold/worker layout loads disappear; remaining Map key/value checks and call packets are identified separately.
- [integration/](integration/) records the subsequent default-on runtime: 167 full tests with the new defaults, 167 with layout-bearing storage selected explicitly, five focused header-off tests, both AST/bytecode full18 Map checks and actual launcher VM flags. Typed cases and leading-case return experiments remain opt-in. This integration is not the measured prototype JAR.
- [prototype/](prototype/) contains the exact patch, source, factory-size measurements, bytecode inspection and four 157-test runs, all without failures, errors or skipped tests.
- [map/](map/) contains all 18 native-oracle inputs checked before and after requested compilation in four configurations, with zero unsupported traps.
- [diagnostics/](diagnostics/) identifies the graph/profile captures and their input provenance. Raw BGV and JFR files are omitted; their hashes identify the originals.

The frozen runtime JAR SHA256 is `1d51bca562a5f7848cd2f9b88cde6102e00509760bf2a70713f0afedc4fcb192`. This is the isolated representation prototype, before its integration with typed cases and leading-case partial inlining. Original absolute paths in records identify the measured inputs; they are not required to verify the published evidence.

```sh
python3 bench/results/class-owned-layouts/verify.py
```

The verifier checks published hashes, recomputes timing medians, validates compilation/warmup guards, rechecks exact allocation samples, test totals, Map results and compact object sizes. It cannot reconstruct omitted binaries or host conditions from hashes.
