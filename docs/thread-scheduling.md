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
reducing the requested number modulo the context's CPU capacity. It then attempts
native affinity for that CPU on the child **platform** thread. Affinity is best
effort: an unavailable API, denied native access or rejected request never prevents
the fork. `threadStatus#` reports the logical capability and requested lock, not
proof that the OS accepted a pin. This is not `forkOS` or bound foreign TLS.

CPU capacity is captured when the context is created, before guest pinning: the
JVM's available-processor count, capped by discovered eligible CPUs. This respects
JVM/container capacity limits. `enabled_capabilities` (used by GHC's capability
query) reports that count; creating more guest threads never creates more CPUs.
Capabilities are dense indices into ordered eligible OS CPUs, which need not have
contiguous IDs. Ordinary carriers share these indices round-robin. There is no
dynamic `setNumCapabilities` or GHC `-N` scheduler configuration yet.

Linux uses `sched_getaffinity`/`sched_setaffinity` on the current native thread,
not a Java thread ID. The kernel's online-CPU and cpuset restrictions still apply.
Windows uses advisory CPU Sets, preserving hard process/thread masks and restoring
the exact prior selection. It currently declines multi-group process topologies
whose full hard eligibility cannot be resolved, rather than truncating to 64 CPUs.
Unsupported platforms retain the JVM capacity count without claiming a pin.
Virtual carriers are never pinned. A completed fork restores its prior native
mask, including exceptional exits.

`fork#` clears inherited affinity back to the context's original CPU eligibility.
Thread creation temporarily restores that eligibility on the creator, then
restores the creator's pin; `forkOn#` applies its new selection in the child.
This avoids accidentally restricting an ordinary fork to its pinned parent's CPU.
Other native/JVM threads created while pinned can inherit the restriction.
THC installs a local listener for the pinned Graal runtime: recognized compiler
workers restore the first native provider's baseline eligibility before compiling
each target, without changing the submitting guest's pin. It identifies the exact
worker class and classloader, not its name, and reset failure is non-fatal.
This requires no Graal patch or reflective access. The listener runs after worker
and compiler initialization, so helpers created during that earlier initialization
may still inherit restrictions. Unknown runtime worker implementations are left
untouched. THC does not retarget arbitrary JVM threads.

The launcher also captures Graal's default compiler-pool policy before guest pins
(two workers with at least four available CPUs, otherwise one). An explicit
`polyglot.engine.CompilerThreads` setting takes precedence. Embedders retain their
own engine configuration; the listener cannot resize a pool already initialized
by another engine. Scoped pinning is compatible with JVM GC and safepoints; it is
not a latency or speedup guarantee.

The public [`THC` module](cpu-affinity-api.md) distinguishes unavailable, advisory,
and pinned support. Its `forkOnWithAffinity` callback observes whether that child's
initial native request was accepted, and always runs even if the request failed.

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
