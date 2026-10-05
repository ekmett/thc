<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# GHC garbage collection, statistics and monotonic time

Both backends translate five original GC/statistics `ghc-internal` foreign declarations from
GHC 9.14.1. The original Haskell wrappers remain guest code; THC does not replace
them with a host implementation of `GHC.Stats` or a benchmark harness.

| Original C symbol | JVM behavior |
| --- | --- |
| `getRTSStatsEnabled` | Returns false. GHC RTS statistics are unavailable. |
| `getRTSStats` | Rejects direct use without reading or writing its buffer. |
| `performGC` | Requests collection with `System.gc()`. GHC's public `performMinorGC` uses this symbol. |
| `performMajorGC` | The same advisory JVM request. GHC's public `performGC` and `performMajorGC` use this symbol. |
| `performBlockingMajorGC` | The same request; no stronger completion guarantee. |
| `getMonotonicNSec` | Has no THC-owned override. Standalone Core calls without package native linkage reject as unsupported. |

JVM collection policy, including disabled explicit GC, controls what happens.
The requests do not promise a GHC generation, a complete collection, prompt
reclamation, finalizer execution, or waiting for a concurrent collector. GHC's
`+RTS -T` does not enable these statistics on THC. JVM observations remain
available separately through [THC runtime services](runtime-services.md); JVM
heap/collector counters are not relabeled as GHC allocated/copied/live bytes.

After each managed GC request, its admitted caller also attempts the narrowly
eligible [canonical owned-free registrations](weak-explicit.md). The weak is
DEAD before the native effect. Busy native borrows or free/realloc reservations
defer retirement until completion without blocking the guest or a Loom HEC.
JDK Cleaner also attempts this restricted retirement after collection without a
managed GC request; no request guarantees collection or retirement before return. Haskell
actions and package callbacks remain explicit-only. Statistics and clock queries
do not drain weak registrations.

The original `GHC.Internal.Stats.getRTSStats` first calls
`getRTSStatsEnabled`. When false it raises its own `UnsupportedOperation`
`IOError`, before allocating an `RTSStats` buffer. The direct foreign leaf is
also explicitly unavailable, not a buffer full of plausible zero counters.

Ordinary package clocks use package-declared native linkage through Sulong,
as described in [foreign code](interface-foreign.md). The general package tests
cover foreign ABI admission, pointer ownership and errno transport. They do not
qualify acquisition or execution of the installed `time` package's Haskell clock
wrappers.

Admission preserves the original `ccall`, `ghc-internal` unit, saturated arity,
State/result tuple and primitive ABI. The statistics and GC calls are `safe`.
With asynchronous exceptions enabled, successful safe
calls commit their result before polling. Saved continuation resumption does
not replay the completed call. AST keeps its explicit opt-in policy; bytecode
keeps its existing default.

`CompilerHeapHintTest` checks the admitted shared GC foreign ABI, JVM return
behavior on both backends from the first compiled call, and unavailable statistics
without buffer reads or writes. `ManagedWeakTest` checks real owned-free retirement, live-key controls, callback
promotion, borrow deferral, explicit-finalize races and context cancellation.
These fixture-free callers use independent GHC
9.14.1 signature models; they do not qualify acquisition or execution of the
installed original Haskell wrappers.
