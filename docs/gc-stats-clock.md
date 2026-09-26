<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# GHC garbage collection, statistics and monotonic time

Both backends translate six original `ghc-internal` foreign declarations from
GHC 9.14.1. The original Haskell wrappers remain guest code; THC does not replace
them with a host implementation of `GHC.Stats` or a benchmark harness.

| Original C symbol | JVM behavior |
| --- | --- |
| `getRTSStatsEnabled` | Returns false. GHC RTS statistics are unavailable. |
| `getRTSStats` | Rejects direct use without reading or writing its buffer. |
| `performGC` | Requests collection with `System.gc()`. GHC's public `performMinorGC` uses this symbol. |
| `performMajorGC` | The same advisory JVM request. GHC's public `performGC` and `performMajorGC` use this symbol. |
| `performBlockingMajorGC` | The same request; no stronger completion guarantee. |
| `getMonotonicNSec` | Returns `System.nanoTime()` bits as `Word64#`. |

JVM collection policy, including disabled explicit GC, controls what happens.
The requests do not promise a GHC generation, a complete collection, prompt
reclamation, finalizer execution, or waiting for a concurrent collector. GHC's
`+RTS -T` does not enable these statistics on THC. JVM observations remain
available separately through [THC runtime services](runtime-services.md); JVM
heap/collector counters are not relabeled as GHC allocated/copied/live bytes.

The original `GHC.Internal.Stats.getRTSStats` first calls
`getRTSStatsEnabled`. When false it raises its own `UnsupportedOperation`
`IOError`, before allocating an `RTSStats` buffer. The direct foreign leaf is
also explicitly unavailable, not a buffer full of plausible zero counters.
In upstream tasty-bench 0.4.1, `hasGCStats` and `getAllocsAndCopied` already
choose `(0,0,0)` themselves when statistics are disabled. That is the library's
no-statistics behavior, not evidence of zero guest allocation.

Monotonic time has an arbitrary JVM origin and nanosecond units, not guaranteed
nanosecond resolution. Compare elapsed differences within a process; it is not
wall-clock UTC and is not synchronized with another JVM or native GHC. The
existing original `clock_gettime` CPU-time route is unchanged.

Admission preserves the original `ccall`, `ghc-internal` unit, saturated arity,
State/result tuple and primitive ABI. The statistics and GC calls are `safe`;
the clock is `unsafe`. With asynchronous exceptions enabled, successful safe
calls commit their result before polling. Saved continuation resumption does
not replay the completed call. AST keeps its explicit opt-in policy; bytecode
keeps its existing default.

## Reproduction and evidence

The Haskell producer recovers all six genuine FCallIds from the complete
installed `Stats`, `System.Mem` and `Clock` interfaces, specializes independently
typechecked consumers, and exports pre/post-Tidy Core. It does not redeclare the
foreign imports. Native GHC executes 15 rows: disabled statistics, three GC
requests, and ordered monotonic observations. No test assumes a measurable GC
effect or equates absolute clock values between runtimes.

```sh
compiler/build.sh
cabal run exe:thc-primops -- scalars
cabal run exe:thc-fixtures -- gc-stats
./gradlew --continue gcStatsFullCoreTest gcStatsFullCoreDenseTest \
  testDefault --tests thc.runtime.PackageSafeForeignTest \
  testDense --tests thc.runtime.PackageSafeForeignTest
```

Use the pinned native 64-bit GHC 9.14.1 complete-Core environment. These explicitly
selected full-Core tests fail if preparation is absent; they are not a hidden
dependency of stock/thin-interface CI. The receipt hashes producer inputs,
original interfaces, exported Core and native observations. Each handoff mode
checks 60 interpreted and 60 first-compiled native-comparison rows across both
backends (AST explicitly async-enabled) and both Core stages, plus ABI negatives and direct-statistics failure
with an unchanged destination buffer. Existing safe-FFI tests separately cover
asynchronous delivery and completed-result resumption without replay.
