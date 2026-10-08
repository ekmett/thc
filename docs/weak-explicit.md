<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Managed weak registrations

General automatic weak collection and Haskell finalizer scheduling are broken;
[issue #1065](https://github.com/ekmett/thc/issues/1065) tracks their replacement.
The carrier-specific retention below describes current behavior. It cannot
implement the general `System.Mem.Weak` contract by adding more key classes.
Explicit finalization remains available.

`mkWeak#`, `mkWeakNoFinalizer#`, `deRefWeak#` and `finalizeWeak#` share a
context-owned implementation in both interpreters. An actionless registration
without C callbacks, whose key and value are the identical boxed carrier, can be
collected by the JVM while its `Weak#` handle remains live. Neither registration
nor dereference forces that carrier. Once the key is collected, dereference returns flag 0;
the registry releases the dead entry during a subsequent weak operation.
Collector timing remains a JVM decision, and a GC request does not guarantee
collection before returning.

An actionless registration without C callbacks also supports a distinct value
when its key is a raw managed `MutVar#` or `MVar#` carrier. The key owns that
registration's original lazy value, while the registry holds the key weakly. A live key retains
its value even if the `Weak#` handle is dropped. An otherwise unrooted value that
refers back to its key does not keep that cycle alive. Registration and
dereference do not force either carrier. Explicit finalization and context close
detach each registration's value from its key. A retained MVar request keeps its
cell and that cell's weak values alive, even after the request is cancelled;
cancellation alone does not make the cell unreachable.

One canonical THC-owned `free` callback can leave an actionless registration
collectible when its key and value are identical, or its key is a raw managed
`MutVar#` or `MVar#`. It holds only a weak reference to a direct malloc owner
(or null), permitting
the same key-owned distinct lazy values described above. The original
function label must belong to the current context; a constructed same-symbol
label, returned-address wrapper, second callback or generic callback remains
explicit-only. Attaching any such callback to a live collectible registration
atomically restores strong retention of its exact key and value. Callback order
remains newest first. Attaching to an already collected registration returns 0.

JVM collection can retire this malloc storage without a guest GC request or
another weak operation. A standard JDK Cleaner action holds only a weak Owner
reference and an armed flag; it retains no context, key, value, address or
function provider. The context's allocation registry owns the live allocation.
The action invokes only the already-paired raw native free, outside weak and
allocation registry locks; it executes no guest or package callback. A collected
key makes its weak DEAD before the native effect. Collection and cleanup timing
remain nondeterministic, including in idle contexts.

Cleanup never waits for native borrows or an explicit free/realloc reservation,
including with one Loom HEC. It latches pending retirement and retries when the
last borrow or reservation completes, without requiring another collection.
Managed `performGC`, `performMajorGC` and `performBlockingMajorGC` calls retain
their opportunistic drain but guarantee neither collection nor completion.
An owner already retired by explicit free or realloc consumes a stale token
without another native call. Ordinary free and explicit finalization still
reject freed aliases. Promotion and explicit finalization disarm cleanup before
releasing the key; an already collected registration preserves pending work.

Malloc retirement invalidates its shared arena before invoking raw free. If
arena close fails, native free is not invoked and storage may leak. An uncertain
native effect is terminal and never replayed. Background failures are retained
and reported by subsequent address access, explicit free/address recovery or
context disposal. Successful retirement removes the Owner from the allocation
registry. Windows LocalFree retains its existing separate failure contract and
is ineligible for this automatic route.

Distinct-value registrations with other key carriers, and all registrations
with a Haskell action or other attached C callbacks, strongly retain their keys and
payloads until explicit finalization or context close. Dropping the `Weak#` does
not erase a live registration. General ephemeron support remains absent:
otherwise unreachable cycles through these retained values or actions can remain
for the context's entire lifetime. These primitive-key paths recognize only raw
carriers; they do not force a lifted key or unwrap an `IORef` or `MVar` box.

Finalization atomically marks the registration dead and drops its payload from
the registry. It returns the actual Haskell action with flag 1; it does not call
that action. The original guest caller invokes it using the returned State#.
Repeating finalization returns flag 0, as does finalizing a weak without an
action. Dead dereference returns flag 0 and an unspecified, cleared payload.
The returned Haskell action remains an ordinary reusable closure, not a newly
one-shot wrapper. No guest code runs under the registry lock.

Keys and values are boxed at their exact independently selected levities; Weak#
is an unlifted boxed object, never a native pointer. Logical State operands and
tuple fields are retained. Malformed arity, representations, lifted flags and
contradictory lexical proofs are rejected. The runtime checks opaque handle
identity and context ownership. Closing a context invalidates its handles and
releases registry references without executing outstanding Haskell finalizers.
Owned-free cleanup registrations are disarmed before keys are released; native
allocation disposal releases remaining owned storage after admitted guest
operations have stopped. Cleanup weakly references its Owner and does not
promise reclamation of an abandoned, unclosed context; deterministic context
close remains the resource-lifetime contract.
Host cancellation is not claimed to guarantee execution of a returned action.

`addCFinalizerToWeak#` admits [source-certified C labels and owned `free`
bases](c-finalizers.md). A zero flag calls `f(object)`; every nonzero flag calls
`f(environment, object)`. The retained function declaration and linked definition
must agree with that ABI. Explicit `finalizeWeak#` runs callbacks outside the
registry lock before returning the Haskell action.
The eligible owned-free path above provides automatic malloc retirement through
JDK Cleaner; Haskell actions and package callbacks still require explicit
finalization. There is no general callback executor, ephemeron collection or
heap walk. Identity and actionless `MutVar#`/`MVar#` collection do not establish
bounded-memory reclamation for other keys or general registrations with finalizers.
