<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Explicit weak finalization (partial)

`mkWeak#`, `mkWeakNoFinalizer#`, `deRefWeak#` and `finalizeWeak#` have a bounded,
context-owned implementation in both interpreters. This is **not automatic weak
reference or ephemeron support**. Registrations strongly retain their lazy keys,
values and Haskell actions until explicit finalization or context close. Dropping
the Weak# does not erase its registration. Key/value/action cycles therefore
cannot be collected prematurely, but unreachable keys and resources can remain
retained for the context's entire lifetime.

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
is still no automatic GC finalizer thread, Java Cleaner/WeakReference
approximation, heap walk or bounded-memory reclamation.
