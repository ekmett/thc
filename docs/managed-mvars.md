<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Managed MVars

Both interpreters lower the eight GHC 9.14.1 MVar primitives: `newMVar#`,
`takeMVar#`, `putMVar#`, `readMVar#`, `tryTakeMVar#`, `tryPutMVar#`,
`tryReadMVar#` and `isEmptyMVar#`. This is a managed-cell foundation for Handle
locking; MVar coverage alone is not coverage of the complete Handle call graph.
The [driver's current executable IO path](driver.md) includes ordinary output.

## Representation and effects

The contracts retain logical zero-width `State#` arguments and result fields.
MVar references require exactly an object with `BoxedRep (Just Unlifted)`;
payloads require a boxed data, closure or object proof at a known lifted or
unlifted levity. Flags require `IntRep`, not another long-carried representation.
Unknown levity, scalar/address/vector payloads, malformed tuple layouts and
contradictory local/global occurrence proofs are rejected before execution.

Lifted payloads remain lazy through put, read and take, including a rejected
`tryPutMVar#`. Unlifted payloads are evaluated, without forcing their lifted
fields. Failed try-read/take operations return flag zero and an unspecified
payload; the implementation clears the destination rather than retaining an old
reference. A separate fullness bit distinguishes an empty cell from a stored
null in host-level protocol tests. State carriers are checked before mutation or
tuple publication.

## Waiting and cancellation

A short internal lock serializes each cell's state and waiter queues. No guest
code or payload comparison runs under that lock. Queued takes and puts transfer
in FIFO order. A put completes all waiting readers before the oldest taker;
readers observe that value even if a taker subsequently consumes it.

Each blocking operation owns one request token. Registration is inside the
Truffle interruptible callback, and safepoint retries reuse that token. A wakeup
delivers an already committed operation: it does not compete for the cell again.
Terminal cancellation removes only a still-pending request and releases its
offered payload; it cannot revoke or replay a committed transfer.

There is no GC-driven `BlockedIndefinitelyOnMVar` detection. A pending
`takeMVar#`, `putMVar#` or `readMVar#` with no future partner needs supported
asynchronous interruption or embedding cancellation to terminate. See the
[current primop behavior reference](primop-behavior.md#exceptions-blocking-and-transactions).

Both backends use these requests for
[asynchronous exception delivery](async-exceptions.md). Interruption cancels
an uncommitted request and saves a retry at the guest continuation cut.
[Managed weak registrations](weak-explicit.md) allow raw `MVar#` keys to own
lazy values and finalizers. An otherwise unrooted value-to-key cycle can collect;
a retained request keeps its cell and conditional values alive even after
cancellation. Explicit finalization and context close detach these values.

With Jam, `addMVarFinalizer` can execute its Haskell cleanup automatically when
the cell becomes unreachable. Attached C callbacks follow the same general weak
lifetime rules. Collection gives no cleanup deadline; use explicit close or
`bracket` for scarce resources. Handle dependencies and native IO remain separate
contracts.
