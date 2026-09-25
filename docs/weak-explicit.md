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

For the separate `rts_setMainThread` consumer, `ManagedWeaks.mainThreadKey(weak,
threads)` validates a live, same-context `ThreadId#` **key**, not the boxed value.
Its `MainThreadWeakKey.liveJavaId()` capability rereads the registration and
delegates to `GuestThreads.liveJavaId(identity)`. It returns null after explicit
finalization or either registry's close, for a missing/dead original Java carrier,
terminal guest status, or a noncanonical identity. The thread service compares
the actual identity and carrier by reference, not numeric ThreadId equality.
A live host carrier in FOREIGN status remains observable between guest entries.
This query neither creates a guest lifetime nor finalizes its weak registration.
The capability keeps
only the registry, opaque weak handle and expected thread registry; it does not
keep another key/value/action reference. Consumers must retain the capability,
not a permanent key or ID snapshot. This is a liveness snapshot, not a dispatch
permission: an eventual signal action must atomically recheck the exact identity
when enqueuing its request. The existing numeric send path is not that guard.
This accessor does not implement or admit the foreign RTS call or signals.

`addCFinalizerToWeak#` remains unsupported. There is no ignored C callback,
automatic GC finalizer thread, Java Cleaner/WeakReference approximation, heap
walk or bounded-memory reclamation. Native callback support needs real typed
pointer ownership and a once-only exit drain before it can be admitted.

This slice permits stdout's genuine key-capturing registration; it does not
complete the Handle call graph or ordinary hello. Native normal process exit
flushes stdout/stderr through `GHC.Internal.TopHandler.flushStdHandles`, not by
eagerly running their weak finalizers. The actual standard-handle finalizer also
closes codecs and replaces its MVar value with a finalized-handle error; it is
not interchangeable with hFlush. Process-wrapper/shutdown support is separate.

## Native contract fixture

The single `WeakAudit.weakComposite` fixture uses GHC's actual primitives. It
checks registration/dereference, returned rather than automatically executed
Haskell actions, repeated/dead/no-action flags, independent weaks sharing a key,
lifted and unlifted value carriers, and lazy bottom key/value/action carriers.
It does not use GC timing to claim reclamation. The native expected result is
the input plus 58, wrapping as machine Int on the supported 64-bit target.

```sh
cabal run exe:thc-fixtures --offline -- weak-explicit
./gradlew --offline --no-daemon --max-workers=2 test --tests 'thc.runtime.ManagedWeakTest' --rerun
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew --offline --no-daemon --max-workers=2 test --tests 'thc.runtime.ManagedWeakTest' --rerun
```

The fixture must be present and fresh; selected tests do not silently skip it.
Pre-/post-Tidy strict audits and native rows are checked before interpreted and
first-installed compiled AST/BC comparisons. Direct registry tests separately
cover racing claims, ownership, reentrancy and context disposal; they are not
native GC evidence. No installed GHC binaries, interfaces or libraries are hashed.

The compiled check disables Truffle inlining, resolves the genuine `weakComposite`
target and its active host-linked split/worker targets (including BytecodeDSL's
cached calls), and explicitly installs each. It checks target identity and
last-tier validity immediately after the first installed call, with no settling
calls. Per-call guest-entry deltas and target names are retained in test output;
the host wrapper itself does not increment the guest-entry counter. Each native
row requires exactly three compiled guest entries: the input lambda, runRW State
lambda, and returned Haskell action. The already-forced initial-value CAF remains
an installed target but does not execute again. Every
compiled row also requires zero unsupported traps and released argument/result
handoff references and depths, in both default and dense-slab runs.

AST lowering uses exact Object writes for the boxed weak/payload results and
direct enum identity predicates for operation selection. The initial enum `when`
retained Kotlin's mutable switch-mapping array in actual Graal graphs. The first
installed state-lambda call deoptimized under `IntrinsifyFrameAccessor` after
frame-array materialization; typed writes alone did not fix it. Direct identity
dispatch together with those typed writes passes the unchanged immediate
retention checks. The registry and shared frame implementation were not changed,
and no settling calls or disabled compiler speculation are part of this proof.
