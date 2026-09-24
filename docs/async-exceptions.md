<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Asynchronous exceptions

The bytecode backend implements `fork#`, `myThreadId#` and `killThread#` using
Truffle-managed Java threads. A `ThreadId#` contains the Java thread ID and its
owning context. There is no separate scheduler identity. The command-line
runtime permits thread creation; an embedding must enable
`Context.Builder.allowCreateThread(true)`.

The [capture contract](async-continuation-contract.md) separates delivery
eligibility from the caller's obligation to preserve a suspended computation.

`killThread#` queues a lazy exception payload and waits for delivery or target
completion. A Truffle thread-local action wakes the target; it does not throw
from arbitrary Java frames. The target claims the request at a bytecode
continuation cut. Normal polls respect masking; interruptible blocking waits
also accept requests under `maskAsyncExceptions#`. A self-directed throw is
synchronous even under an uninterruptible mask, as it is in GHC.

The target acknowledges delivery when its original `catch#` accepts the
exception, before executing the handler. An uncaught child delivery terminates
the child and releases the sender. At a public host entry, an uncaught delivery
becomes a guest exception with the original payload. A completed target is a successful no-op.
Only one request is claimed at a time; queued requests retain their order.

## Saved evaluation

A bytecode Yield materializes the frame on the interruption path. Locals,
pending calls and the active masking state form a saved continuation. Ordinary
execution does not materialize a frame at each poll.

When an evaluator abandons a shared thunk, the continuation replaces its
original target and environment. Another Java thread can claim and resume it.
The asynchronous exception belongs to the interrupted evaluator, not to the
thunk's memoized result. A later force continues the work instead of replaying
the body or throwing that exception again. Synchronous exceptions still follow
the ordinary memoization rules.

Strict entry arguments are forced in the callee's bytecode frame, including
arguments supplied earlier by a partial application. A suspension therefore
preserves the call as well as the argument being forced. Forced arguments are
checked against their declared runtime representation after resumption.

Calls through `keepAlive#` retain the protected reference in the saved caller
frame until the continuation returns or throws. Truffle may clone a compiled
call target; clones of the same body retain continuation identity.

Blocking MVar operations are cancelled only before their transfer commits.
Their operands are saved before the cut, so resumption retries the uncommitted
operation. Interrupting a blackhole waiter does not alter the thunk owned by
another evaluator. If a blocked `killThread#` sender is interrupted, its pending
outbound request is removed from the target's queue. Resuming that saved sender
requeues the same request; an already claimed request cannot be revoked.

This is distinct from the diagnostic stack snapshots used for backtraces.
Those snapshots are display data, not executable continuations.

## Checks

`ThreadAsyncAudit.hs` runs the sender and target in exported Haskell. Native GHC
and THC agree on catching a thrown exception, resuming the shared thunk, uncaught
child termination and self-directed delivery. The JVM checks use the normal
public parser before and after Tidy, interpreted and explicitly compiled.
`LazyForkAudit.hs` checks that a forked action's lazy head is first evaluated by
the child, then resumed by its parent after interruption. `UncaughtSelfAudit.hs`
checks public execution and `runIO` failure boundaries without exposing internal
continuation markers.

`LiveAsyncAudit.hs` exposes a gated shared thunk. Tests deliver into its running
compiled loop, its blocked MVar, a second evaluator waiting for the owner, and
a previously saved continuation. The effectful prefix executes once, including
after two successive interruptions. The running-loop test records that the
request was claimed from compiled code.

The focused fixtures are prepared with:

```sh
cabal run thc-fixtures --offline -- live-async
cabal run thc-fixtures --offline -- thread-async
cabal run thc-fixtures --offline -- uncaught-self
```

The AST backend, arbitrary JVM/native foreign calls and blocking file operations
do not gain resumable asynchronous interruption from this implementation. The
three admitted thread primops are marked partial in the coverage inventory;
other GHC scheduling and thread-inspection primitives remain separate work.
