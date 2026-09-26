# Scheduling hints, delays and allocation counters

THC implements `par#`, `spark#`, `numSparks#`, `getSpark#`, `forkOn#`,
`delay#`, `setThreadAllocationCounter#` and `setOtherThreadAllocationCounter#`
on both backends.

Spark hints are discarded without evaluating their lifted arguments. `par#`
returns one, `spark#` returns the identical argument, and the empty pool queries
return zero; `getSpark#` carries the pinned RTS's boxed `False` filler. There is
no speculative worker pool or parallel-speedup claim.

`forkOn#` uses the existing real managed-thread implementation, lazy child action
and inherited masking state. It records a locked context-local logical capability,
reducing the requested number modulo the current logical capability count.
`threadStatus#` observes that capability and lock. This is not OS CPU affinity,
`forkOS`, or bound foreign TLS. Ordinary `fork#` keeps its existing unbound path.

`delay#` interprets its argument in microseconds; nonpositive values do not wait.
A saturated monotonic deadline prevents multiplication overflow. Safepoint wakes
and captured continuations retain that deadline. Waiting reports the RTS delay
status and is interruptible under the usual masking rules. The implementation
does not restart a full delay after interruption.

Allocation counters measure actual JVM heap bytes allocated by the thread during
its outer guest-entry extents, including runtime bookkeeping. They start at zero,
count down, and accept signed 64-bit resets for the current or another context-owned
thread. Nested entries do not reset a counter; host work between outer entries
is excluded. The narrow original `stg_getThreadAllocationCounterzh` adapter makes
the installed GHC getter observable. JVM allocation accounting must be available
for an explicit counter operation; loss of accounting never interrupts thread
cleanup and requires a successful reset before another read. Native/Sulong bytes
are not charged. These are target-relative measurements, not GHC heap-layout byte
equivalence, and they do not implement allocation-limit enforcement.

`cabal run exe:thc-fixtures -- thread-scheduling` exports nine genuine pre/post
Core entries and compares native hint, fork, counter and timed-delay observations.
The native oracle uses GHC's non-threaded RTS because the pinned threaded POSIX
I/O manager rejects direct `delay#`; the guest checks still exercise real managed
threads. Counter expectations use allocation bounds, including GHC's documented
approximately 4 KiB other-thread accounting granularity. Kotlin checks add first
installed straight-line calls, actual JVM byte accounting, context ownership,
cleanup with accounting disabled, delay capture/deadline retention and masking.
