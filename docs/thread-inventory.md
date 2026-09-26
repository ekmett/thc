# Thread inventory and boundness

`listThreads#` returns an independent `Array# ThreadId#` snapshot of this THC
context's guest identities. It does not enumerate host JVM threads. Active,
foreign and retained finished identities can be present; ordering is unspecified.
The registry uses weak keys, while a returned snapshot deliberately retains its
identities. Snapshot storage never aliases the registry. Context close invalidates
further observations without modifying arrays already returned.

Both backends support this operation and `isCurrentThreadBound#`. THC currently
admits only unbound guest threads: the latter returns zero for an actual registered
guest entry, including foreign re-entry. This agrees with the existing negative
`rtsSupportsBoundThreads` capability; it does not treat an OS/Java carrier or a
logical capability number as a bound Haskell thread. Native threaded GHC's main
thread is bound, so the native comparison explicitly executes in `forkIO`.
`forkOS` and bound foreign TLS remain unsupported.

The array result uses GHC's unlifted `ThreadId#` elements. `indexArray#` and
`readArray#` now also transport unlifted object references without forcing or
boxing them. Array allocation/writes retain their existing lifted-element gate.

## Example and evidence

[ThreadInventory.hs](../examples/ThreadInventory.hs) finds the current thread in
a snapshot using identity, and observes its boundness. Its
`forkSnapshot` example uses real `fork#`/MVar coordination to acquire a snapshot
containing a live child and retain that snapshot across its completion signal.
The fork, observations, and concurrent host-entry tests run on both backends.
The same example checks lazy action-head evaluation in the child, discarding an
unforced result, inherited masking, and uncaught self-directed child death.
`parkedFork` deliberately leaves a child blocked for embedding shutdown tests;
it is not a standalone program that completes all its child work.

Ordinary AST forks do not receive external `killThread#`: their general bodies
cannot capture resumable continuations. The registry rejects such a send before
enqueueing or waking the child, including across nested guest entries. Self
delivery still reaches the original handler or terminates the child as `DIED`.
Bytecode forks retain their existing captured async delivery. Both kinds are
real Truffle-managed threads cancelled by `Context.close(true)`; context-wide
cancellation does not imply that a shared AST thunk can later be resumed.

```sh
cabal run exe:thc-fixtures --offline -- thread-inventory
./gradlew installDist
build/install/thc/bin/thc build/thread-inventory/pre/core/ThreadInventory.json selfInventory 0 --compile
./gradlew test --tests thc.runtime.ThreadInventoryNativeTest --tests thc.runtime.GuestThreadInventoryTest
```

The Haskell producer compiles native GHC 9.14.1 with `-threaded`, runs with `-N2`,
exports and strictly audits pre/post Core, and hashes the exact source/artifact
inventories. The JVM checks native invariants, first installed compiled calls,
typed tuples, handoff cleanup, independent snapshot storage, actual concurrent
identities, finished outcomes, nested entries, and context isolation. Snapshot
counts and native background thread ordering are intentionally not equated.

## Spark and scheduler boundary

`par#`, `spark#`, `getSpark#`, `numSparks#` and `forkOn#` remain unsupported.
Pinned GHC's `par#` returns one regardless of acceptance, but that is not a license
to replace scheduling with a constant. `numSparks#` counts a capability's local
queue, not running Java threads.

A speculative evaluator can diverge or block without its result ever being
demanded. A managed-thread pool must therefore abandon speculative work safely
at shutdown and preserve shared-thunk resumability. Ordinary AST evaluation has
no general captured cancellation continuation; an interrupted owned thunk can
enter the unresumable state. Merely adding worker threads would either hang
context shutdown or damage a subsequently demanded thunk. The current bytecode
async machinery does not establish the missing both-backend spark scheduler.
Logical capabilities are monotonic Java-carrier identities, not a `forkOn#`
affinity scheduler. No spark admission or parallel-speedup claim is made here.
