<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# STM and TVars

The pinned GHC 9.14.1 family has eight primops: `atomically#`, `retry#`,
`catchRetry#`, `catchSTM#`, `newTVar#`, `readTVar#`, `readTVarIO#` and
`writeTVar#`. `sameTVar#` is a removed primop, not another supported entry.

Both backends implement transactions with lazy boxed payloads at
either known levity. A context owns its TVars and commit domain. A transaction
buffers writes, validates read revisions on reads/writes and at commit, then
publishes all changes under one short lock. Guest evaluation, forcing and
handlers never execute under that lock. A stale transaction reruns; a stale
synchronous exception is discarded and rerun too. `readTVarIO#` observes the
committed value, not the caller's private writes.

`catchRetry#` rolls back a failed branch's writes and retains its read set.
The right branch runs only after the left retries. `catchSTM#` rolls back
the failed action's writes, retains its reads and invokes the lazy handler
outside its own catch frame, without changing masking. Retry is not a Haskell
exception. Nested `atomically#` raises GHC's original `nestedAtomically`
SomeException closure, retained through implicit dependency linking; the runtime
does not fabricate its dictionary or exception value.

An `STM` newtype stored in a constructor field has an opaque lifted-object
binding representation. Its erased newtype cast supplies the closure occurrence
used by `atomically#`, `catchRetry#` or `catchSTM#`. The strict auditor permits this
refinement only within the same lifted boxed carrier; scalar, aggregate and
unlifted contradictions still fail. Runtime action forcing still requires a real
closure. This does not change State# or TVar# argument and result shapes.

Retry validates and registers its read dependencies under the commit lock,
then releases it and blocks through Truffle's interruptible safepoint API.
Each TVar retains the waiters registered on it; the context keeps only a weak
shutdown inventory. A commit visits waiters of changed TVars, without scanning
unrelated transactions. Identical-pointer writes do not wake them. Cancellation
removes the waiter from every dependency under the same lock. Both branches'
dependencies survive an `orElse` retry. An empty read set really waits. Context disposal releases waiters and payload references;
foreign-context, disposed and wrong-carrier TVars fail before mutation.

## Asynchronous interruption

The public load request's `asyncExceptions` boolean controls eager asynchronous
polling. Both backends default to `false`, speculating on a single guest admission
origin until concurrency is admitted, while always retaining continuation capture.
Explicit `true` enables ordinary polling immediately.

An asynchronous exception crossing `atomically#` aborts the entire
attempt and cancels any retry registration before the enclosing IO handler runs.
The request is acknowledged by that handler (or the uncaught IO boundary), not
by transaction cleanup. Neither `catchSTM#` nor `catchRetry#` catches asynchronous
delivery; their nested tentative writes are discarded during unwinding.

A shared thunk enclosing `atomically#` saves a restart of the original action,
not the interrupted action's continuation or transaction log. Another carrier
can force that thunk: it preserves the outer already-executed prefix and starts
a fresh transaction. An inner shared thunk may itself have an update continuation;
the fresh action must demand it under the new attempt, rather than traversing it
as the yielded child of the abandoned transaction. Logs remain carrier-local.
No locks or unconditional masking are held across guest evaluation.

Commit has no guest async delivery point between validation/publication and
recording successful completion. A completed commit is not replayed by the
restart boundary. Conflicts still restart synchronously, with no acknowledgement
or conversion into a Haskell exception.

## Explicit limits

Explicit test-checkpoint and delimited-continuation capture across a transaction
remain unsupported. Async abort/restart does not make an arbitrary STM log a
multi-shot continuation. Admission is currently conservative: a linked program
containing delimited control rejects transactional operations even when the
intended paths are disjoint. Synchronous calls from multiple Java carriers in the
same context continue to share transactions and retry wakeups correctly.
`newTVar#` and `readTVarIO#` need no transaction frame.

Jam's Candidate lifecycle rescues an unreachable parked retry with the original
GHC `BlockedIndefinitelyOnSTM` exception, including an empty read set. Live
read-set TVars and independently retained thread state remain rescue paths.
Rescue aborts the attempt and preserves its exception handlers and masking;
it does not commit the transaction. See the
[blocked-owner qualification limits](managed-mvars.md#waiting-and-cancellation).
Unsafe effects inside STM are not rolled back, just as in GHC; callers must not
rely on how many times an invalid action reruns.

Linking `atomically#` requires complete installed GHC Core for the original
nested-transaction exception and its dictionaries, even if the intended action
never nests. Use the [installed-library setup](driver.md#installed-library-core)
rather than replacing a missing exception definition.
