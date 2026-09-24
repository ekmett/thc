<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Managed MVars

Both interpreters lower the eight GHC 9.14.1 MVar primitives: `newMVar#`,
`takeMVar#`, `putMVar#`, `readMVar#`, `tryTakeMVar#`, `tryPutMVar#`,
`tryReadMVar#` and `isEmptyMVar#`. This is a managed-cell foundation for Handle
locking, not support for ordinary `putStrLn` or the complete Handle call graph.

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

This does not introduce a guest scheduler, `fork#`, `throwTo#`, masking, Haskell
exception recovery or resumable interrupted thunks. Language context policy and
guest-thread admission are unchanged. Blocking integration tests use one active
guest thread and external host cell operations, not concurrent guest admission.
Weak finalizers, other Handle dependencies and native IO remain separate work.

## Evidence and reproduction

`compiler/test-fixtures/ManagedMVarAudit.hs` exports genuine pre- and post-Tidy
Core for five integer-entry examples: state transitions, lifted bottoms,
opaque aliases and snapshots, boxed-unlifted products, and closure payloads.
All eight contracts appear in both stages; all six payload-bearing primitives
appear at both boxed levities. An independent signed-64-bit model checks 845
native rows over 169 edge and seeded inputs. The JVM tests compare every row in
both interpreters with inlining enabled and disabled, then explicitly compile
the observed call targets and repeat the comparisons with per-call compiled
guest-entry and handoff-cleanup assertions. Normal compilation policy remains
enabled during warmup; post-install host-driven calls are not a separate claim
that the host bridge's entry counter was measured.

Four separately listed context roots (`makeBox`, `waitTake`, `waitRead`,
`waitPut`) are internal host-driven tests, not an extension of the public
integer-only entry ABI. Native ready-state adapters provide 507 oracle rows.
Native-only thread/status handshakes also verify reader broadcast and queued
take/put order under one and two GHC capabilities, 45 rows each. Their fork and
exception dependencies are outside guest exports and do not establish guest
thread support. Direct cell tests cover stable retries, commit/cancel ordering,
lazy identity and queue cleanup.

The context tests check every ready-state oracle row across pre/post Core,
AST/bytecode and both handoff modes (4,056 comparisons). They also exercise 24
real guest waits awakened by host cell operations, 24 terminal `Context.close`
cancellations and 24 `Context.interrupt` cancellations followed by a fresh call
in the same context. Each checks waiter removal and argument/result reference
cleanup. Context reuse is not resumption of a cancelled guest thunk.

```sh
python3 scripts/prepare-managed-mvars.py
python3 scripts/prepare-managed-mvars.py --check-only
python3 scripts/test-managed-mvars.py
python3 scripts/test-managed-mvar-fixtures.py
./gradlew --no-daemon test --tests 'thc.runtime.ManagedMVar*' --rerun
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew --no-daemon test --tests 'thc.runtime.ManagedMVar*' --rerun
```

Use `--refresh` to rebuild stale generated output under `build/`. CI uses this
option when source changes invalidate an existing fixture. Valid output is
still reused; `--check-only` never changes it.

The manifest records source/generated-artifact hashes and native/audit results;
installed GHC files are not hashed. No performance claim is made for locking or
allocation in this slice.
