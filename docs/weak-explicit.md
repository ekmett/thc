<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Managed weak registrations

The current draft uses real `jam.vm.Weak` associations in both interpreters.
General `System.Mem.Weak` support is **not qualified**:
[issue #1065](https://github.com/ekmett/thc/issues/1065) remains open. The real
completed-thunk regression returns flag 0 while its WHNF alias remains live in
both handoff modes with the retained release. Public Haskell automatic-finalizer
laws have executed on a diagnostic provider; those results do not qualify the
release or general lifted-key normalization. Native Image remains unqualified.

For an association `K => (V, F)`, independent reachability of `K`, including an
ordinary Java root, retains the original lazy value and finalizer state.
References from `V` or `F` back to `K` do not establish that reachability. Jam
owns this conditional association and its atomic retirement. THC keeps a
context-owned token handle; neither a live handle nor the context registry
strongly roots the key or conditional payload. Dropping the handle does not
cancel the association. This design applies to arbitrary boxed keys, without
key-class recognition, a Cleaner route or a separate guest heap walk.

The conditional value and finalizer state are separate. Claiming the finalizer
retains its actual action, program-owned runner and C callback captures without
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
context. A suspended guest finalizer does not block dispatch of other claims.
Execution uses an actual `GuestThreads` carrier with the normal masking, call
and continuation machinery. It runs C callbacks before the Haskell action.
The exact owning program supplies `THC.Internal.Weak.runWeakFinalizer` lazily;
that helper invokes GHC's original finalizer batch code, including its current
exception handler and handling of exceptions from that handler. THC does not
replace that policy with a Java catch-and-discard adapter. A claim completes
only after the real carrier terminates, or after shutdown proves abandonment.
Automatic Haskell actions and attached C callbacks require guest thread
permission.

[Typed C callbacks](c-finalizers.md) retain their actual declaration, native
provider and arguments. Each callback capture and native borrow lasts through
its real use; remaining C captures are cleared before the Haskell action can
suspend. The reserved owned `free` uses the existing allocation retirement latch: busy
borrows or free/realloc reservations defer retirement until their completion
without blocking the finalizer carrier or requiring another collection. An
already retired owner consumes a stale automatic token without replaying free;
ordinary explicit free still rejects freed aliases. Arena invalidation precedes
raw free. An uncertain native effect is terminal and is never replayed; failures
remain visible through the allocation's existing error paths.

Context shutdown fences admission, stops and joins guest carriers, waits for
in-flight finalizer construction, then abandons remaining claims before native
providers and allocation owners are disposed. Outstanding Haskell finalizers
are not promised execution during context close. Deterministic close remains
the resource-lifetime contract; this does not establish reclamation of an
abandoned, unclosed context. GC requests remain advisory and guarantee neither
collection nor finalizer completion before returning.

The remaining representation boundary is the general
[Lifted contract](https://github.com/ekmett/jam/blob/main/docs/vm/lifted-tracing.md).
THC supplies resolution and selector projection; Jam supplies verified collector
entries and rewriting of eligible source slots. Normalization must follow a
key's logical referent without marking it, forcing it or preserving its obsolete
wrapper. It must also support a selector whose chosen field remains unevaluated.
Non-success thunk states retain their ownership and suspension semantics.

The retained Jam package still scans dead registration records. Upstream has
removed that repeated scan, but package adoption and metadata lifetime remain
separate qualification boundaries. Neither registry checks nor collector startup
prove those contracts.

The existing `WeakAudit`/`WeakFixtures` producer owns the public
`System.Mem.Weak` law with a dropped handle, value/finalizer backedges and an
externally retained MVar signal. Native GHC supplies its independent result;
original runtime Core, CBD dependencies and audits are explicit inputs/outputs.
See [runtime qualification](jam-runtime.md#acceptance-evidence) for the distinction
between diagnostic execution and supported behavior.
