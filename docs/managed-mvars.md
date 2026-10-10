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

Jam's Candidate lifecycle rescues an unreachable parked logical thread with
the original GHC `BlockedIndefinitelyOnMVar` exception. The parked Java carrier
does not root the captured guest continuation. A live cell, strong `ThreadId`
or independent Java reference remains a rescue path; a cycle consisting only
of blocked threads and their cells does not keep itself alive. Commitment,
cancellation and GC rescue compete for the same pending operation.

The genuine blocked-owner example matches native GHC on both backends and
handoff modes with platform and virtual threads on macOS. Full compiled
application and Native Image qualification remain tracked in
[#1213](https://github.com/ekmett/thc/issues/1213). This is reachability-based
rescue, not a promise to detect every wait with no future producer or the
main thread's global `Deadlock`.

Both backends use these requests for
[asynchronous exception delivery](async-exceptions.md). Interruption cancels
an uncommitted request and saves a retry at the guest continuation cut.
[Managed weak registrations](weak-explicit.md) allow raw `MVar#` keys to own
lazy values and finalizers. An otherwise unrooted value-to-key cycle can collect;
a reachable logical owner keeps its pending cell and conditional values alive. Cancellation or
commitment releases the request's cell reference. A committed request retains
its result for safepoint retries, including any cell referenced by that result.
Explicit finalization and context close detach conditional values.

With Jam, `addMVarFinalizer` can execute its Haskell cleanup automatically when
the cell becomes unreachable. Attached C callbacks follow the same general weak
lifetime rules. Collection gives no cleanup deadline; use explicit close or
`bracket` for scarce resources. Handle dependencies and native IO remain separate
contracts.
