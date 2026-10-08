<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Managed weak registrations

The current draft uses real `jam.vm.Weak` associations in both interpreters.
Selected Linux JVM laws pass on the pinned Jam package: logical keys survive
thunk evaluation, pending handoffs permit guest progress, and automatic actions
follow the original GHC finalizer wrapper. Windows also passes the selected
fixture-free bootstrap, handoff and context-lifecycle models in both handoff
modes. The existing full weak IO program also matches native GHC on Linux
JVM and in a relocated Native Image built with the diagnostic supplier candidate
described in [acceptance evidence](jam-runtime.md#acceptance-evidence).
General qualification remains open in
[issue #1065](https://github.com/ekmett/thc/issues/1065): actual allocation-failure
recovery, complete native lifetime and supplier-package qualification, and other
platforms remain unfinished.

For an association `K => (V, F)`, independent reachability of `K`, including an
ordinary Java root, retains the original lazy value and finalizer state.
References from `V` or `F` back to `K` do not establish that reachability. Jam
owns this conditional association and its atomic retirement. THC keeps a
context-owned token handle; neither an active handle nor normal registry membership
strongly roots the key or conditional payload. Dropping the handle does not
cancel the association. This design applies to arbitrary boxed keys, without
restricting keys to mutable carriers, a Cleaner route or a separate guest heap walk.

The conditional value and real finalizer state are separate. Claiming the real
finalizer retains its actual action, program-owned runner and C callback captures without
an invented edge to the value. Neither registration nor dereference forces the
key, value or action. The runtime retains their independently selected levities
and the logical State# operands and tuple fields. Malformed representations and
contradictory proofs reject; opaque handles must belong to the current context.

`finalizeWeak#` atomically retires the association before any callback. It runs
C callbacks outside the registry lock, newest first, then returns flag 1 and
the exact original Haskell action. It does not execute that action. The action
remains reusable. A registration without a Haskell action returns flag 0 after
its callbacks; repeated finalization also returns flag 0. Dead dereference
returns flag 0 and an unspecified cleared payload. Resurrection does not revive
a retired registration, and explicit and automatic claims cannot both win.

The JVM-wide drainer takes Jam claims and dispatches each through its owning
context. During normal dispatch, a suspended guest finalizer does not block
other claims.
Execution uses an actual `GuestThreads` carrier with the normal masking, call
and continuation machinery. It runs C callbacks before the Haskell action.
The exact owning program supplies `THC.Internal.Weak.runWeakFinalizer` lazily;
that helper invokes GHC's original finalizer batch code, including its current
exception handler and handling of exceptions from that handler. THC does not
replace that policy with a Java catch-and-discard adapter. A claim completes
only after the real carrier terminates, unfinished work transfers to its context,
or shutdown proves abandonment. A start operation can fail after the carrier
has started; THC still joins that carrier before completing the claim.
After terminal completion or explicit action transfer, the old claim releases its
action, runner and dispatch tree. Retaining the terminated Java carrier does not
keep those completed cleanup captures alive. Untouched setup failures retain them
until explicit settlement or context shutdown.
Automatic Haskell actions and attached C callbacks require guest thread
permission.

If Java setup fails before cleanup begins, the draft retains the untouched
callbacks and action as context-owned failed work. Dereference and callback
attachment surface the failure; explicit finalization can settle the original
cleanup. Begun user cleanup is never replayed. Failed guest-carrier setup remains
explicitly settleable; it is not automatically repeated. Native allocation
failures that terminate the process cannot be recovered in Java.

The drainer also accepts other host clients' ordinary Jam callbacks. If their
carrier cannot be launched, it invokes the claimed callback synchronously and
completes only after return or failure. This exceptional fallback can block the
shared pump; it does not discard another client's cleanup.

[Typed C callbacks](c-finalizers.md) retain their actual declaration, native
provider and arguments. Each callback capture and native borrow lasts through
its real use; remaining C captures are cleared before the Haskell action can
suspend. The reserved owned `free` uses the existing allocation retirement latch: busy
borrows or free/realloc reservations defer retirement until their completion
without blocking the finalizer carrier or requiring another collection.
Explicit free rejects freed aliases. Arena invalidation precedes
raw free. An uncertain native effect is terminal and is never replayed; failures
remain visible through the allocation's existing error paths.

Context shutdown fences admission, stops and joins guest carriers, waits for
in-flight finalizer construction, then abandons remaining claims before native
providers and allocation owners are disposed. Outstanding Haskell finalizers
are not promised execution during context close. Deterministic close remains
the resource-lifetime contract; this does not establish reclamation of an
abandoned, unclosed context. GC requests remain advisory and guarantee neither
collection nor finalizer completion before returning.

THC implements
[language-level lifted weak handoff](https://github.com/ekmett/jam/issues/7).
An unresolved thunk uses an ordinary weak registration with a bootstrap finalizer
capturing the thunk, conditional value and real finalizer. The bootstrap executes
outside GC, inspects resolution without forcing and either publishes a successor
registration or proceeds to real finalization. Already resolved keys register
against their result directly. THC value carriers implement Jam's ordinary
userland `Lifted` protocol; resolution and projection inspect published answers
without forcing a computation. Bootstrap resolution runs outside collection.

The logical handle remains stable through handoff. A retired bootstrap token
cannot alone justify a dead dereference; explicit finalization, queued/running
handoff and shutdown must arbitrate without losing the backing value or running
the real finalizer twice. Temporary retention and additional collections are
expected costs.

If successor installation throws `OutOfMemoryError` before publication, the
context retains the resolved successor, original value and untouched finalizer.
The existing drainer retries that installation with capped backoff, one due
attempt per pump turn, even after the public weak handle is dropped. It neither
resolves the key again nor runs cleanup as part of recovery. Successful
publication restores the same logical handle before releasing the failed work.
Until then, weak operations surface the first failure; explicit finalization
can still claim the original cleanup. Explicit finalization and context stop
cancel scheduled retries, and an already dequeued retry rechecks ownership
before installing. Protocol, linkage and other VM failures do not enter this
retry policy. Continued allocation failure can keep the captures retained;
this is not a guarantee of recovery from an exhausted or terminated VM.

The pinned Linux release scans live registrations and reuses retired metadata
slots while generation-tagged tokens keep old handles invalid. Registry capacity
can remain at its high-water mark. Other platforms still use the preview packages
listed in [the toolchain guide](jam-runtime.md).

The existing `WeakAudit`/`WeakFixtures` producer owns the public
`System.Mem.Weak` law with a dropped handle, value/finalizer backedges and an
externally retained MVar signal. Native GHC supplies its independent result;
original runtime Core, CBD dependencies and audits are explicit inputs/outputs.
See [runtime qualification](jam-runtime.md#acceptance-evidence) for the distinction
between tested JVM behavior and the remaining qualification boundaries.

## Thread observers and resurrected state

[`WeakThreads`](../src/examples/WeakThreads.hs) uses ordinary `Control.Concurrent`,
`GHC.Conc` and `System.Mem.Weak` APIs. A running guest roots its `ThreadId#`.
The fork startup handoff releases its published identity when the parent receives
it or abandons the wait. After the finite guest lifetime ends, the runtime drops
its identity cache and scheduler references; keeping only its Java carrier does
not retain the weak key.
A guest or Java reference to the actual `ThreadId#` remains an independent root.
Platform host reentry continues to reuse the host's identity and capability.

The same program lets a finalizer return an ordinary cell through an MVar,
updates the recovered state and installs another weak registration for it.
The original registration remains dead; the fresh registration has its own
lifetime and finalizer. Collection requests are advisory. The example waits for
weak observations and finalizer signals, without assuming a number of collections.
See the [example commands](../src/examples/README.md).

On the pinned Linux JVM, this example matches native GHC 9.14.1 on AST and
bytecode in both handoff modes. Focused lifetime checks also cover platform
workers and Loom workers/outer entries, including Java-held identity roots.
The same program also matches the full native oracle as a redirected, relocated
Native Image with the THC lifetime fixes and the source-verified supplier queue
repair described in [acceptance evidence](jam-runtime.md#acceptance-evidence).
Adopting that repair in the normal supplier package and qualifying the remaining
platforms are still open.
