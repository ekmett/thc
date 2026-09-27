# Thread inventory and boundness

`listThreads#` returns an independent `Array# ThreadId#` snapshot of this THC
context's guest identities. It does not enumerate host JVM threads. Active,
foreign and retained finished identities can be present; ordering is unspecified.
The registry uses weak keys, while a returned snapshot deliberately retains its
identities. Snapshot storage never aliases the registry. Context close invalidates
further observations without modifying arrays already returned.

Both backends support this operation and `isCurrentThreadBound#`. Ordinary guest
entries and `fork#` children remain unbound. A managed foreign reverse entry gets
a fresh bound identity on its existing Java carrier, with a separate mailbox and
an initially unmasked state. Nested ordinary guest calls keep that identity;
nested reverse entries get another identity. Return restores the suspended caller
and its mask. Finished callbacks remain observable through retained ThreadIds.

The native fixture uses GHC's actual C callback trampoline to check distinct
ThreadIds, unmasked entry, boundness, same native carrier and caller restoration
under all three caller masks. THC's matching tests exercise managed reverse
entries only. This does not add raw C callback transport, `forkOS`, a general bound
thread scheduler or admission of callbacks through unsafe foreign declarations.
The existing negative `rtsSupportsBoundThreads` capability remains unchanged.
Ordinary thread comparisons still use native `forkIO`, since GHC's main is bound.

The array result uses GHC's unlifted `ThreadId#` elements. `indexArray#` and
`readArray#` now also transport unlifted object references without forcing or
boxing them. Array allocation and writes also admit either known boxed levity;
unlifted thread identities do not require a lifted wrapper.

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

Forks inherit their program's `asyncExceptions` mode. With it enabled, both
backends capture external `killThread#` delivery, including lazy action-head
evaluation and shared thunk resumption. With it disabled, the registry rejects
external delivery before enqueueing or waking the child, including across nested
guest entries. AST still permits self-delivery to the original handler or child
termination as `DIED`; synchronous bytecode rejects `killThread#` at load time.
Both kinds are real Truffle-managed threads cancelled by `Context.close(true)`;
context-wide cancellation is distinct from resumable guest async delivery.

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

`par#` and `spark#` are implemented as discarded hints without evaluating the
lifted argument; `getSpark#` and `numSparks#` describe an empty spark pool.
`forkOn#` creates a real managed platform thread with best-effort CPU affinity.
See [scheduling](thread-scheduling.md). `numSparks#` is not a Java thread count.

A speculative evaluator can diverge or block without its result ever being
demanded. A managed-thread pool must therefore abandon speculative work safely
at shutdown and preserve shared-thunk resumability. Opt-in guest async capture
now exists on both backends, but neither synchronous-mode evaluation nor
context-wide cancellation becomes resumable through that option. The capture
machinery does not establish spark admission, abandoned-result management or a
both-backend spark scheduler.
Logical capabilities initially reflect available CPU capacity and can be shared
by many guest threads. Original `setNumCapabilities` changes their context-local
count without resizing JVM pools; see [RTS capabilities](rts-event-capabilities.md).
No spark admission or parallel-speedup claim is made here.
