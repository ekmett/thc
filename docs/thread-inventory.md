# Thread inventory and boundness

`listThreads#` returns an independent `Array# ThreadId#` snapshot of this THC
context's guest identities. It does not enumerate host JVM threads. Active,
foreign and retained finished identities can be present; ordering is unspecified.
The registry uses weak keys, while a returned snapshot deliberately retains its
identities. Snapshot storage never aliases the registry. Context close invalidates
further observations without modifying arrays already returned.

Both backends support this operation and `isCurrentThreadBound#`. Ordinary guest
entries and `fork#` children remain unbound. In either hosting mode, a managed foreign
reverse entry gets
a fresh bound identity on its existing Java carrier, with a separate mailbox and
an initially unmasked state. Nested ordinary guest calls keep that identity;
nested reverse entries get another identity. Return restores the suspended caller
and its mask. Finished callbacks remain observable through retained ThreadIds.
Loom callbacks acquire the destination HEC's exclusive guest permit on that same
thread; blocking guest waits release it even when the native caller is pinned.

The array result uses GHC's unlifted `ThreadId#` elements. `indexArray#` and
`readArray#` also transport unlifted object references without forcing or
boxing them. Array allocation and writes also admit either known boxed levity;
unlifted thread identities do not require a lifted wrapper.

Fork admission invalidates speculative single-origin execution before child
publication. Ordinary programs on both backends capture external `killThread#`
delivery, including lazy action-head evaluation and shared thunk resumption,
even with the default `asyncExceptions=false`. Prepared AST code retains the
same continuation capture and uses its runtime context's admission bit. Both backends permit self-delivery to the original handler
or child termination as `DIED`.
Both kinds are real Truffle-managed threads cancelled by `Context.close(true)`;
context-wide cancellation is distinct from resumable guest async delivery.

## Spark and scheduler boundary

`par#` and `spark#` are implemented as discarded hints without evaluating the
lifted argument; `getSpark#` and `numSparks#` describe an empty spark pool.
`forkOn#` uses a managed platform thread by default, or a virtual thread locked
to a logical HEC in Loom mode. Native affinity is best effort in either mode.
See [scheduling](thread-scheduling.md). `numSparks#` is not a Java thread count.

Logical capabilities initially reflect available CPU capacity and can be shared
by many guest threads. Original `setNumCapabilities` changes their context-local
count and Loom HEC routing without resizing JVM/compiler/GC pools; see
[RTS capabilities](rts-event-capabilities.md).
No spark admission or parallel-speedup claim is made here.
