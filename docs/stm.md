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

Retry validates and registers its read dependencies under the commit lock,
then releases it and blocks through Truffle's interruptible safepoint API.
Changed dependencies wake the waiter; unrelated or identical-pointer writes
do not. Both branches' dependencies survive an `orElse` retry. An empty read
set really waits. Context disposal releases waiters and payload references;
foreign-context, disposed and wrong-carrier TVars fail before mutation.

## Asynchronous interruption

The public load request's `asyncExceptions` boolean selects synchronous or
asynchronous execution on either backend. Omitting it preserves the existing
defaults: synchronous AST and asynchronous bytecode.

In asynchronous mode, an exception crossing `atomically#` aborts the entire
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

THC does not implement GC-driven `BlockedIndefinitelyOnSTM` detection. An
unreachable empty-read-set retry waits until embedding cancellation/disposal;
it cannot return success. Unsafe effects inside STM are not rolled back, just
as in GHC; callers must not rely on how many times an invalid action reruns.

## Verification and example

```sh
cabal run exe:thc-fixtures --offline -- stm
./gradlew --no-daemon testDefault --tests thc.runtime.ManagedSTMTest \
  testDense --tests thc.runtime.ManagedSTMTest stmFullCoreTest stmDenseFullCoreTest
```

The Haskell producer exports pre/post-Tidy original Core, closes the implicit
exception using complete installed GHC Core, performs strict audits and runs a
57-row native oracle. Kotlin checks an independent arithmetic model, deterministic
conflicts and stale exceptions, real retry registration/wakeup, nested rollback,
lazy and unlifted payloads, context boundaries and cleanup. Installed guest-entry
checks inspect the first compiled call without settling or retries, with and
without inlining and under both handoff modes.
The public parser tests additionally interrupt retry and an inner transaction
thunk through original `killThread#` and `catch#`, check sender acknowledgement,
observe rollback before resumption, and force the abandoned enclosing thunk from
another carrier. Repeated interruption must not replay the enclosing prefix or
retain retry registrations. All native helpers execute afresh inside an opaque
IO wrapper so the oracle does not accidentally memoize its own observations.
The 336 compiled rows per handoff require exactly three guest roots for basic,
lazy and unlifted payloads, or five for exception/alternative/nested-atomic rows:
the public entry, `runRW#` lambda, atomic action, and (where present) protected
action plus handler/right branch. The rejected nested atomic action never runs.
All active targets must remain installed after each row. Callback orchestration
is Kotlin-inlined to keep virtual frames out of heap closures; expected retry and
conflict signals do not trigger an interpreter transfer at storage boundaries.

The eleven transaction-protocol tests and strict audit mutation controls run in
ordinary CI. The original-Core/native tests have a required explicit
`stmFullCoreTest` gate, using the existing full-Core source set, a hashed native
receipt and no cached test success. Missing fixtures fail, never skip. Even a
non-nested `atomically#` frame must retain the original nested-transaction exception:
an action can invoke another transaction dynamically. Stock thin interfaces omit
that exception dictionary's private `$ctoException` body, so all faithful atomic
closures share this complete-installed-Core requirement. This is a fixture/linker
prerequisite, not an excuse to replace the original exception or weaken STM.

The installed bundle also contains an unrelated `GHC.Internal.Conc.Bound`
foreign-export registration that the current linker cannot admit. The producer
retains the complete-bundle discovery failure, requires that exact module to be
unreachable, then selects whole original modules named by the reachable closure.
Fresh strict audits run against that selection; tests compare every selected
module byte-for-byte with its hashed installed archive member. No definitions or
module metadata are rewritten, and a reachable unsupported module still fails.

`compiler/test-fixtures/STMAudit.hs` is a runnable raw-primop example: `basic`
distinguishes committed reads from private writes; `alternative` demonstrates
rollback; `awaitEither` waits on either TVar. `STMNative.hs` runs those examples
and the state-threaded concurrent increment action with the pinned native GHC.
