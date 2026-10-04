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

The original `GHC.Internal.Stats.getRTSStats` first calls
`getRTSStatsEnabled`. When false it raises its own `UnsupportedOperation`
`IOError`, before allocating an `RTSStats` buffer. The direct foreign leaf is
also explicitly unavailable, not a buffer full of plausible zero counters.
The original monotonic clock requires its ordinary package-declared native linkage;
its acquisition and execution are not qualified by the fixture-free GC tests. The
original `clock_gettime` CPU-time route uses the separately linked base CAPI ABI.

## Original time package clock module

On native 64-bit Linux, installed acquisition links the three original CAPI
wrappers in `time-1.15`'s `Data.Time.Clock.Internal.CTimespec`: the configured
`HS_CLOCK_REALTIME` constant, `clock_getres`, and `clock_gettime`. Their clock
argument and status use `CInt`; the existing base CPU-clock argument uses
`Word64`. Both original libraries can coexist in one context. Linking retains
the original stubs, exact unit-qualified wrapper indices and selected compiler
`HsFFI.h`, `HsTime.h`, and `HsTimeConfig.h` hashes. Cache hits revalidate these
native inputs. Native execution still requires the context's native permission.

Time output uses a checked writable 16-byte timespec with 64-bit seconds and
nanoseconds. THC holds allocation ownership through the native call and copies
the staged image only after success. Failure captures errno from that library
on the same thread; success preserves the guest's previous errno. A null
`clock_getres` destination is valid. A null `clock_gettime` destination is rejected
before invoking libc. Invalid destination capacity, lifetime, context, or opaque
pointer cells are rejected before native observation.

This route supports fixed nonnegative clock identifiers and the reserved
invalid identifier `-1` for libc error behavior. Other negative identifiers
encode native descriptors or process/thread CPU clocks on Linux. They are
explicitly unsupported until guest-owned descriptors and guest CPU identities
can be translated; THC does not pass those encodings to unrelated host objects.
This is not general POSIX clock or timer support.

Admission preserves the original `ccall`, `ghc-internal` unit, saturated arity,
State/result tuple and primitive ABI. The statistics and GC calls are `safe`.
With asynchronous exceptions enabled, successful safe
calls commit their result before polling. Saved continuation resumption does
not replay the completed call. AST keeps its explicit opt-in policy; bytecode
keeps its existing default.

`CompilerHeapHintTest` checks the admitted shared GC foreign ABI, JVM return
behavior on both backends from the first compiled call, and unavailable statistics
without buffer reads or writes. These fixture-free callers use independent GHC
9.14.1 signature models; they do not qualify acquisition or execution of the
installed original Haskell wrappers.
