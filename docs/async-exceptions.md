<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Asynchronous exceptions

Both backends implement `fork#`, `myThreadId#` and `killThread#` using
Truffle-managed Java threads. A `ThreadId#` contains the Java thread ID and its
owning context. There is no separate scheduler identity. The command-line
runtime permits thread creation; an embedding must enable
`Context.Builder.allowCreateThread(true)`.

The public request accepts an optional Boolean `asyncExceptions`. `true` enables
saved asynchronous continuations on either backend; when omitted it defaults to
`false` for AST and `true` for bytecode. A string such as `"true"` is rejected.
`CoreModules.request(..., asyncExceptions = true)` exposes the same option.

With async enabled, AST captures ordinary and typed calls, strict entry forcing,
cases, lets, local joins, masks and handlers. Shared thunks retain unfinished
work, and `killThread#` supports external delivery as well as self-delivery.
Forked children inherit the parent's mask and evaluate lazy action heads on
their own threads. With async disabled, AST supports self-delivery but rejects
external sends and delivery to its children before enqueueing; bytecode rejects
`killThread#` during lowering. Nested entry does not upgrade a thread lifetime
that was registered without external delivery support.

The [capture contract](async-continuation-contract.md) separates delivery
eligibility from the caller's obligation to preserve a suspended computation.

`killThread#` queues a lazy exception payload and waits for delivery or target
completion. A Truffle thread-local action wakes the target; it does not throw
from arbitrary Java frames. The target claims the request at a guest
continuation cut. Normal polls respect masking; interruptible blocking waits
also accept requests under `maskAsyncExceptions#`. A self-directed throw is
synchronous even under an uninterruptible mask, as it is in GHC.

The target acknowledges delivery when its original `catch#` accepts the
exception, before executing the handler. An uncaught child delivery terminates
the child and releases the sender. At a public host entry, an uncaught delivery
becomes a guest exception with the original payload. A completed target is a successful no-op.
Only one request is claimed at a time; queued requests retain their order.

The ordinary bytecode poll reads a Truffle context-thread-local cell and the
target's volatile pending flag. The cell follows nested guest entry and is
cleared when the carrier leaves its final guest entry. Claiming remains a cold
locked operation that rechecks ownership, foreign-call permission, masking and
FIFO state. This keeps Java `ThreadLocal` initialization and map maintenance out
of compiled guest loops without disabling asynchronous delivery. See the
[September 26 investigation](../bench/results/performance-regressions-20260926/README.md)
for the graph regression and native-result checks.

Masking and stack-annotation reads likewise use context-thread-local mutable
cells. Boundary setters and the thread registry's legacy `ThreadLocal` interface
update those same cells; they are not cached copies of guest state. Leaving a
carrier resets its masking cell, and different contexts/carriers do not share
cells. This keeps root-entry snapshots and continuation checks from crossing a
boundary into Java thread-local map lookups on ordinary calls.

## Saved evaluation

A bytecode Yield or AST capture materializes the frame on the interruption
path. AST capture also saves pending operands and caller suffixes from Java
locals. Calls, active masks and annotation scopes form a saved continuation;
ordinary execution does not allocate continuation records at each poll.

When an evaluator abandons a shared thunk, the continuation replaces its
original target and environment. Another Java thread can claim and resume it.
The asynchronous exception belongs to the interrupted evaluator, not to the
thunk's memoized result. A later force continues the work instead of replaying
the body or throwing that exception again. Synchronous exceptions still follow
the ordinary memoization rules.

Strict entry arguments are forced in the callee's resumable frame, including
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
Both resumable backends retain that exact outbound request across repeated
sender interruptions. Self delivery keeps its asynchronous origin through
`catch#`, including under an uninterruptible mask. It cannot be replaced by an
ordinary `throwIO` failure without changing lazy thunk update semantics.

This is distinct from the diagnostic stack snapshots used for backtraces.
Those snapshots are display data, not executable continuations.

## Checks

`ThreadAsyncAudit.hs` runs the sender and target in exported Haskell. Native GHC
and THC agree on catching a thrown exception, resuming the shared thunk, uncaught
child termination and self-directed delivery. The JVM checks use the normal
public parser before and after Tidy, interpreted and explicitly compiled, with
async explicitly enabled on both backends.
`LazyForkAudit.hs` checks that a forked action's lazy head is first evaluated by
the child, then resumed by its parent after interruption. `UncaughtSelfAudit.hs`
checks public execution and `runIO` failure boundaries without exposing internal
continuation markers. `ThreadAsyncNativeTest` and `AsyncStrictEntryNativeTest`
pass in both default and dense handoff modes; the latter preserves a demanded
PAP prefix and its caller across interruption.

`LiveAsyncAudit.hs` exposes a gated shared thunk. Tests deliver into its running
compiled loop, its blocked MVar, a second evaluator waiting for the owner, and
a previously saved continuation. These checks assert that the effectful prefix
executes once after repeated interruption and that the running-loop request was
claimed from compiled code. The same checks run against AST and bytecode in
both default and dense handoff modes without relaxing first-installed-code
retention assertions.

The focused fixtures are prepared with:

```sh
cabal run thc-fixtures --offline -- live-async
cabal run thc-fixtures --offline -- thread-async
cabal run thc-fixtures --offline -- uncaught-self
```

[STM](stm.md) aborts its current attempt and preserves an `atomically#` restart
at the enclosing update boundary; it does not save a live log or resume an
abandoned transactional child. Compact traversal, GHC BCO interpreter frames,
opaque foreign execution and mixed asynchronous/delimited capture retain their
separate continuation barriers.
Arbitrary JVM/native frames and blocking file operations do not gain resumable
interruption from the public option. See the
[primop-by-primop behavior reference](primop-behavior.md#exceptions-blocking-and-transactions)
for current restrictions, and [scheduling](thread-scheduling.md) and
[thread inspection](thread-inventory.md) for the implemented operations.
