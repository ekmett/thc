<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Managed weak registrations (partial)

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

Attaching a supported C callback to either kind of still-live collectible
registration atomically restores strong retention of its exact key and value
before the callback can run. Attaching to an already
collected registration returns 0. Callbacks remain explicit-only: collection
never executes C callbacks or Haskell actions.

Distinct-value registrations with other key carriers, and all registrations
with a Haskell action or attached C callbacks, strongly retain their keys and
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
Host cancellation is not claimed to guarantee execution of a returned action.

`addCFinalizerToWeak#` admits only [source-certified one-argument C labels
and owned `free` bases](c-finalizers.md). Explicit `finalizeWeak#` runs their
callbacks outside the registry lock before returning the Haskell action. There
is no automatic GC finalizer thread, Java Cleaner, general ephemeron collection
or heap walk. Identity and actionless `MutVar#`/`MVar#` collection do not establish
bounded-memory reclamation for other keys or registrations with finalizers.
