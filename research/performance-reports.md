# Historical performance and compiler investigations

These reports retain measurements, rejected experiments and compiler artifacts
from their named revisions. They do not describe current support or establish
current performance. No new benchmark or application run is reported here.
Current behavior and build instructions are in [the project README](../README.md)
and [the runtime guide](../docs/README.md).

* [Initial kernel baseline](prototype-results-before-frames.md).
* [Pre-frame summation graph](graph-inspection-before-frames.md).
* [Typed-frame and constructor kernel checkpoint](prototype-results.md).
* [Thunk call-packet experiments](call-packets.md).
* [Map inlining experiments](map-inlining.md).

Graph, image and measurement artifacts stay at their original paths. The two
sections below preserve the front-page/index summaries from
[THC 040b1433](https://github.com/ekmett/thc/tree/040b14338accd292e6c71b8f80bf31cb50ea5e40),
with relative links repaired. Their wording and figures belong to that revision;
follow the named underlying reports for source and binary identities.

## Archived front-page performance section

The recorded Map comparisons are encouraging: about **1.19 times native GHC's
elapsed time** on both an ARM64 Mac and an x86-64 Linux machine. These are
warmed-up results for one workload, not a claim about arbitrary Haskell.
The [entry-contract report](https://github.com/ekmett/thc/blob/040b14338accd292e6c71b8f80bf31cb50ea5e40/docs/entry-contracts.md) describes the measurements;
[retained runs and graph reports](https://github.com/ekmett/thc/blob/040b14338accd292e6c71b8f80bf31cb50ea5e40/docs/README.md#performance-and-runtime-design)
include the inputs, variation and remaining costs.

Constructor layouts belong to their generated storage classes where possible.
With compact headers, a Map `Bin` is 32 bytes and an `I#` is 16 bytes. The
[class-owned layout experiment](../bench/results/class-owned-layouts/) records the
allocation comparison. Compact headers are the default; the reports document
controls for that and the opt-in storage experiments.

```sh
scripts/benchmark.sh
THC_DIAGNOSTIC_UNSUPPORTED=true scripts/benchmark-map.sh
THC_BACKEND=ast THC_DIAGNOSTIC_UNSUPPORTED=true scripts/benchmark-map.sh work/bench-ast
```

Run the corresponding `try` script first. Benchmarks vary their inputs, consume
the results, warm the JVM and compare against native GHC. Graph capture is a
separate run.

## Archived runtime-index performance section

The recorded Map measurements are workload-specific. The controlled ARM64 run
reduced bytecode time from 2.36 ms to 1.52 ms against native GHC's 1.28 ms. The
matched Linux i9-12900K run measured 1.60 ms against 1.34 ms. Both are about
1.19 times GHC's elapsed cost. Each retained report records warmup, process
variation and native correctness checks; these are not general Haskell timings.

* [Entry contracts and type preservation](https://github.com/ekmett/thc/blob/040b14338accd292e6c71b8f80bf31cb50ea5e40/docs/entry-contracts.md), with the
  [controlled local runs](../bench/results/constructor-class/powered-default/),
  [hosted runs](../bench/results/hosted-2026-09-23/) and
  [Linux runs](../bench/results/castlemeadow-2026-09-23/).
* [Class-owned layouts](../bench/results/class-owned-layouts/): allocation,
  compact headers, retained graphs and comparison switches.
* [Typed execution and tail cycles](https://github.com/ekmett/thc/blob/040b14338accd292e6c71b8f80bf31cb50ea5e40/docs/typed-tail.md), [call boundaries](https://github.com/ekmett/thc/blob/040b14338accd292e6c71b8f80bf31cb50ea5e40/docs/call-boundaries.md),
  [call packets](call-packets.md), [dense handoff](../docs/handoff-slabs.md) and
  [Map inlining](map-inlining.md).
* [Laziness and thunk updates](../docs/thunk-updates.md), [boxed values](../docs/boxed-values.md)
  and [demand probes](../docs/demand-probe.md).
* [Bytecode backend](https://github.com/ekmett/thc/blob/040b14338accd292e6c71b8f80bf31cb50ea5e40/docs/bytecode.md), [source locations](../docs/debug-locations.md),
  [graph inspection](../docs/graph-inspection.md) and [kernel measurements](prototype-results.md).
* [Current architecture and planned work](../docs/architecture.md), the preserved
  [2026-09-22 proposal](../research/architecture-2026-09-22.md), and
  [development checks](../docs/contributing.md).

Runtime experiments are opt-in except compact headers and class-owned layouts.
`-Dthc.classOwnedLayouts=false` selects field-bearing layouts;
`-Pthc.compactObjectHeaders=false` disables compact headers for Gradle launches.
The controlled benchmark also accepts `-XX:-UseCompactObjectHeaders` for a
matched header-off run. Keep graph capture separate from timed measurements.
