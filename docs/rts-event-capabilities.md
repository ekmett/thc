# Original RTS event prerequisites and capabilities

Both backends recognize these exact pinned `ghc-internal` FCallIds. Their
unit, calling convention, safety, scalar ABI and State/result tuple are checked
at the foreign boundary; an unrelated symbol with the same spelling is not an
alternate declaration.

`getNumberOfProcessors` returns the context's immutable eligible CPU capacity:
the initial JVM available-processor count capped by discovered native eligibility,
at least one. This snapshot respects JVM/container limits and does not change
when a guest child is pinned.

`setNumCapabilities` accepts a positive `Word32` count and updates a separate,
context-local logical count. The original public Haskell wrapper retains its
nonpositive-`Int` rejection. Direct zero or noncanonical foreign carriers also
reject without changing the count. The `enabled_capabilities` read-only Word32
data label observes the logical count; its ownership and address restrictions
are unchanged. Updates and registry assignment are synchronized. A shrink
normalizes retained live and finished guest thread capability indices modulo
the new count. Queries are snapshots, not a transaction with a later query.

Ordinary new carriers share logical capabilities round-robin. `forkOn#` reduces
its request modulo the logical count, then maps that capability modulo the
immutable eligible CPU count for its best-effort native affinity request.
Changing the count does not repin existing carriers, change their historical
affinity-acceptance report, create CPUs, resize JVM/compiler/GC pools, or provide
GHC `-N` scheduling. Safe completion publishes the new count before an enabled
async exception poll; resumption does not replay the setter. AST async capture
remains explicitly opt-in and bytecode remains enabled by default.

The following first-writer shared CAF stores use the existing context-owned
stable-pointer protocol, each with an independent slot:

- `getOrSetSystemEventThreadIOManagerThreadStore`
- `getOrSetSystemTimerThreadEventManagerStore`
- `getOrSetSystemTimerThreadIOManagerThreadStore`

A null argument queries without installing. The winning valid stable pointer
is retained until context disposal; losers remain caller-owned. Foreign-context
pointers, invalid candidates, freeing a winner and use after disposal reject.
These slots do not themselves start a timer or event-manager thread.

`__hscore_f_setfd` (`Int32#`), `__hscore_fd_cloexec` (`Int64#`) and
`__hscore_sizeof_siginfo_t` (`Word64#`, safe) return values measured by the native
C ABI probe, without requiring guest native-access authority. The size grants
no access to a host signal record. Exposing the constants does not extend the
managed `fcntl` adapter beyond its existing `F_GETFL`/`F_SETFL` operations.

`eventfd`, `eventfd_write`, `pipe`, `epoll_create`, `epoll_ctl`, `epoll_wait`,
`poll`, `setIOManagerWakeupFd`, `setIOManagerControlFd` and
`setTimerManagerControlFd` remain unsupported original event-manager leaves.
They need an owned descriptor and wakeup/shutdown protocol; THC does not return
invented descriptors or silently accept control-fd registration. Existing
managed I/O readiness and process-signal delivery retain their own protocols.

`cabal run exe:thc-fixtures -- rts-event` recovers all eight declarations from
installed full Core, specializes typed consumers, records the original interface
and source hashes, executes independent native controls, and strictly audits
pre/post exports. `rtsEventFullCoreTest` and `rtsEventFullCoreDenseTest` check both
backends and first installed calls. Native controls query existing event slots
without installing test objects into the host RTS.
