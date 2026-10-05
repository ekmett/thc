# Scheduling hints, delays and allocation counters

THC implements `par#`, `spark#`, `numSparks#`, `getSpark#`, `forkOn#`,
`delay#`, `setThreadAllocationCounter#` and `setOtherThreadAllocationCounter#`
on both backends.

## Thread hosting

Platform hosting remains the default. Experimental per-context
`thc.ThreadHosting=loom` uses one Truffle-managed virtual thread per guest thread,
including public entries; nested entries and ordinary thunk evaluation remain on
that same virtual thread. Embedders must allow experimental options and thread
creation. Loom requires the pinned JDK 25 and
`--add-opens=java.base/java.lang=ALL-UNNAMED` (included by the launcher). Missing
support fails explicitly; there is no platform fallback.

Each logical capability (HEC) has one exclusive guest-execution permit. MVar and
STM waits release it, including inside native-pinned callbacks. Runnable, unmounted ordinary
threads may move between HECs; `forkOn#` stays on its selected logical HEC.
Resizing normalizes parked and queued routes immediately and mounted routes at
their next unmounted boundary. Cooperative guest polls yield to waiting work;
this is not preemptive scheduling. Shutdown cancels and joins managed threads
and active callbacks before closing carrier queues.

Safe/interruptible foreign transitions release the HEC before native entry and
reacquire it before guest execution resumes. Replacement carriers run only while
released native mounts retain carriers; they share the same exclusive HEC permit.
Reverse entries remain on the native origin thread, with fresh bound identities
and destination-context admission. Native TLS and the foreign activation stack
are not moved to an offload thread. Process-signal readers use the same managed
hosting and release their HEC during native waits. Unsafe foreign calls retain
the HEC and can block its progress. This does not make arbitrary native calls
cancellable. Supported native callback signatures and safety rules are described
in [Foreign code](interface-foreign.md). Per-virtual-thread
allocation counters are unavailable and explicit reads/resets fail, not return
zero. Platform hosting retains the existing foreign and allocation facilities.
Existing async delivery, masking and saved-continuation rules apply in both modes.

## Hints, affinity and accounting

Spark hints are discarded by default without evaluating their lifted arguments.
`par#` returns one and `spark#` returns the identical argument. Set
`thc.SparkQueueCapacity` to a value from 1 to 65536 to enable one managed guest
worker and a bounded context-owned queue; zero disables it. The embedding must
allow thread creation. The worker uses the selected platform or Loom hosting.
For the installed launcher, pass `-Dpolyglot.thc.SparkQueueCapacity=1` in
`JAVA_OPTS`; the ordinary launcher contexts allow thread creation.
Loom worker sharing, deferred failures, cancellation/resumption and disposal have
been checked on macOS with both backends and handoff modes. Broader workloads
remain experimental.
It evaluates original shared thunks to WHNF through ordinary `Force`; demand
shares the same publication rather than starting a copied computation.

This first slice admits only unevaluated original thunks from this context's
AST or bytecode roots with asynchronous continuation capture enabled. Ordinary
AST and bytecode lowering retain this capture support even when
`thc.asyncExceptions` is false; that option controls polling eagerness. Other
values, unsupported roots, duplicate pending hints and overflow are discarded
without forcing. Thus enabling the queue does not promise evaluation of every
hint. `numSparks#` counts queued entries; `getSpark#` removes an unclaimed thunk with success flag one, or returns zero
with the pinned RTS's boxed `False` filler when empty. The state operand must
complete successfully before `spark#` can enqueue its payload; failure or
suspension cannot launch the hinted work.

Ordinary speculative guest failures stay on their originating thunk and are
observed by later demand; unrelated guest work continues. Cooperative async
cancellation acknowledges the worker request and retains the claimed thunk's
saved continuation for demand without replaying effects. Cancelling the worker
stops this context's pool and discards unstarted hints. Context disposal clears
queued references before managed worker shutdown; it never resets or replays
claimed thunks. Unexpected worker infrastructure failures use the existing
context failure path. No parallel speedup is claimed without workload evidence.

`forkOn#` uses the existing real managed-thread implementation, lazy child action
and inherited masking state. It records a locked context-local logical capability,
reducing the requested number modulo the context's logical capability count. It
maps that index modulo eligible CPU capacity and attempts native affinity on
the child platform thread, or the Loom HEC's platform carriers. Affinity is best
effort: an unavailable API, denied native access or rejected request never prevents
the fork. `threadStatus#` reports the logical capability and requested lock, not
proof that the OS accepted a pin. This is not `forkOS` or bound foreign TLS.

CPU capacity is captured when the context is created, before guest pinning: the
JVM's available-processor count, capped by discovered eligible CPUs. This respects
JVM/container capacity limits. The separate logical count starts at this capacity;
original `setNumCapabilities` changes it per context and `enabled_capabilities`
reports it. Ordinary platform threads receive logical indices round-robin. Shrinking
the count in platform mode normalizes retained thread indices without repinning
existing carriers or changing their initial affinity outcome. Loom changes actual routing at
unmounted boundaries as described above. Eligible OS CPU IDs need not be contiguous.
No JVM pool scaling or GHC `-N` scheduler configuration is implied. See
[RTS event prerequisites and capabilities](rts-event-capabilities.md).

Linux uses `sched_getaffinity`/`sched_setaffinity` on the current native thread,
not a Java thread ID. The kernel's online-CPU and cpuset restrictions still apply.
Windows uses advisory CPU Sets, preserving hard process/thread masks and restoring
the exact prior selection. It currently declines multi-group process topologies
whose full hard eligibility cannot be resolved, rather than truncating to 64 CPUs.
Unsupported platforms retain the JVM capacity count without claiming a pin.
Virtual threads are never pinned directly. A completed platform fork restores its
prior native mask, including exceptional exits; a Loom worker restores it on exit.

In platform mode, `fork#` clears inherited affinity back to the context's original CPU eligibility.
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
