<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Asynchronous exceptions

Both backends implement `fork#`, `myThreadId#` and `killThread#` using
Truffle-managed Java threads. A `ThreadId#` names a logical guest lifetime in its
owning context and separately records its Java carrier. The command-line
runtime permits thread creation; an embedding must enable
`Context.Builder.allowCreateThread(true)`.

The public request accepts an optional Boolean `asyncExceptions`. `true` enables
ordinary asynchronous polling immediately; when omitted it defaults to
`false` for both AST and bytecode. A string such as `"true"` is rejected.
`CoreModules.request(..., asyncExceptions = true)` exposes the same option.
`loadEntry` also accepts a nullable `asyncExceptions` argument, defaulting to
the optional strict `-Dthc.asyncExceptions=true|false` launcher property. With
neither set, both backends use speculative single-origin execution.

The standalone `--run-executable` launcher, raw `--run-io` and embedding requests
all default to `false` on both backends. An explicit
`-Dthc.asyncExceptions=true` enables ordinary polling eagerly. Original GHC
startup can install its process signal handlers without opting in: process
signal dispatch admits concurrency with either hosting option; see
[standalone process signals](process-signals.md).

Ordinary programs always retain continuation capture from their first lowering.
AST captures ordinary and typed calls, strict entry forcing,
cases, lets, local joins, masks and handlers. Shared thunks retain unfinished
work, and `killThread#` supports external delivery as well as self-delivery.
Forked children inherit the parent's mask and evaluate lazy action heads on
their own threads. With `false`, ordinary polls rely on an irreversible,
per-context single guest admission origin assumption. A guest fork or signal
worker invalidates it before child construction/publication; a different-origin
public entry invalidates it before hosting or guest effects. Compiled code can
deoptimize at that boundary and continue its original activation. No root is
replaced and no completed effect is replayed. Capture handlers around child calls,
strict operand saves, typed loans and saved PCs never depend on the assumption.

The origin is the first public guest admission's Java thread. Truffle does not
expose the public `Context.Builder` creator, so context construction/initialization
does not establish this identity. Internal Loom dispatch forwards the original
origin; same-origin safe reverse callbacks preserve it. This assumption is distinct
from the physical Java entrant assumption, which Loom itself can invalidate.
Generic thread construction and Truffle thread initialization do not admit guest
concurrency. Other contexts retain independent assumptions.

Prepared reusable AST roots also retain capture from first lowering, including
their declared materializable-frame and polymorphic-completion AOT contracts.
Their default-off ordinary polls read an irreversible volatile admission bit,
not the invalidatable origin assumption. Admission sets that bit before
invalidating ordinary code or publishing guest effects, so prepared installed
targets need not retire or fall back to interpretation. Sequential public caller
changes and instantiation after a transition remain supported. Nested entries
still cannot upgrade an enclosing activation that actually lacks capture
capability. Persisted Native Image acceptance requires the pinned provider and
is separate from JVM cold AOT verification.

Self-delivery remains mandatory even while the assumption is valid, including
under either masking mode, and also reaches live and saved delimited `catch#`
frames. Each resumption keeps its own one-shot request; the reusable frame image
does not clone or acknowledge it. The reached handler acknowledges the original
request without forcing its payload. With async enabled, external delivery from
an interrupted action likewise unwinds to its live or saved catch, preserving
the original request and the child's separate one-shot ownership. Each invocation
drains scheduling cuts and AST stack spills through its own one-shot owner. New
delimited capture across a parked caller chain still rejects; see
[delimited continuations](delimited-continuations.md).

In either hosting mode, managed foreign reverse entries create fresh bound guest
identities on the same carrier. They start unmasked and cannot claim the suspended
caller's mailbox.
Self-directed delivery compares logical identities, so sending to that caller
from its callback is not a self-throw. Return retires the callback and restores
the caller's mask, polling cell and AST stack scope. Allocation counters pause
while a nested callback owns the carrier, including cross-context callbacks;
resetting a suspended counter does not restart its accounting interval.
Ordinary nested guest entries continue to share their current identity.
Loom releases guest admission around safe/interruptible foreign execution and
blocking callback waits, then reacquires it before guest execution. This does not
broaden the admitted native cancellation protocols; see
[thread hosting](thread-scheduling.md#thread-hosting).

Synchronous managed JavaScript, polyglot, private file-ABI and linked package calls retain their
exact safe/unsafe declarations. An unsafe activation rejects reverse entry before
creating a guest identity, even across contexts. A safe activation admits fresh
callbacks and queues caller delivery until its typed result is saved. Nested
calls use the innermost declaration. Shared file services receive explicit safety
from the private safe ABI; original unsafe stdio does not inherit ambient safe
authority. Final resource disposal admits no new callbacks after registry close.
[Native callbacks](interface-foreign.md#call-haskell-from-native-code-or-java) have a checked scalar/address
ABI; arbitrary opaque native interruption remains unsupported.

AST function roots and bytecode roots with async or delimited execution enabled
declare their polymorphic completion contract before target publication: ordinary scalar/tuple results and saved continuations
are both valid carriers. The pinned [protocol artifacts](../tools/truffle-protocol/README.md)
retain normal argument and exception profiling while avoiding return-class
speculation for these roots. Generated bytecode resume targets inherit the same
contract. Polls, owner waits and application dispatch retain ordinary learned
branch profiling. Unobserved compiled bytecode conditions instead consume their
real Boolean without quickening or fabricated history. Once real interpreter
execution learns a profile, an unseen request or application shape may deoptimize and continue in
the interpreter. Correct delivery, saved state and resumption do not depend on
retaining the first installed target. Closures can cross between the AST and bytecode backends in the same context.
Saved scalar and tuple calls share the continuation protocol, including typed
PAP prefixes, tail targets, IO actions and handlers. A resumed computation may
move to another Java carrier while retaining its guest identity and scopes.

The current managed errno slot is still carrier-local. Native GHC starts callback
errno at zero and publishes callback changes on return; matching that transition
across native transports remains a separate requirement. No previous caller errno
is blindly restored by this activation policy.

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

After its admission guard and existing scheduling checkpoint, a speculative
ordinary poll can skip mailbox lookup while its context has never published an async request.
This separate monotone assumption invalidates under the thread registry lock
before an accepted send or resumed request is queued; it never resets after a
queue drains. Explicit eager polling and prepared reusable AST roots keep their
runtime polling policy and do not use this context assumption. Mandatory delivery
cuts remain unconditional.

Once a request has been published, ordinary polls read the Truffle
context-thread-local cell and the target's volatile pending flag. The cell
follows nested guest entry and is cleared when the carrier leaves its final
guest entry. Claiming remains a cold locked operation that rechecks ownership,
foreign-call permission, masking and FIFO state. Scheduling checkpoints and
Truffle loop safepoints remain active before the first request.

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

Async-enabled AST and bytecode roots also use these continuations to bound nested calls and
thunk forcing. At the depth limit, they save the pending computation before
entering another body and unwind to the current guest entry's driver. That
driver resumes saved updates iteratively. This cut is not an asynchronous
request: it does not enqueue, deliver or acknowledge an exception. An actual
async request follows its saved handler scopes or the guest entry's normal
uncaught-delivery protocol.
Public calls, forked actions and reentrant callbacks have separate drivers, so
an autonomous cut is consumed inside its guest extent. Completed effects,
shared updates, masks and pending caller operands remain in their saved scopes.
Bytecode initial and continuation entries balance the same conservative depth
budget in `finally`; the entry cut resumes after ingress restoration, before any
body effects. AST roots with the separate synchronous stack-capture capability
also retain that capability without enabling external delivery. The logical
depth budget is not a portable measurement of remaining machine stack space.

An internal cut inside STM retains the original attempt and saved nested
`catchSTM#`/`catchRetry#` scopes. It does not commit, abort, restart a prefix, or
keep those scopes on the host stack. Resumption temporarily reinstalls the live
transaction association, and completion performs the original validation and
commit/rollback. A real conflict/retry still restarts the appropriate action.
External interruption retires the old log and keeps the original request through
the saved handler chain; a shared child later demanded by a new attempt inherits
that new attempt. Explicit checkpoint/delimited capture across transactions is
still unsupported.
Inline tuple carriers in async AST calls remain virtual at creation, but a cold
capture can retain them as owned storage. Capture therefore need not repeatedly
deoptimize compiled callers, and it does not retain a tuple-pool loan.

When an evaluator abandons a shared thunk, the continuation replaces its
original target and environment. Another Java thread can claim and resume it.
The asynchronous exception belongs to the interrupted evaluator, not to the
thunk's memoized result. A later force continues the work instead of replaying
the body or throwing that exception again. Synchronous exceptions still follow
the ordinary memoization rules.

Strict arguments, including PAP prefixes, are forced before the callee body
begins. A suspending preparation saves its argument packet, forcing position and
remaining invocation; resumption does not replay completed forces or enter the
callee twice. Typed preparation acquires its input loan only after forcing has
completed, and checks resumed values against their declared representation.

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

## Concrete exception result layouts

`catch#`, `raiseIO#` and the three masking primops use the same recursive typed
tuple destination as ordinary calls. Their outer result remains exactly
`(# State# RealWorld, result #)` even when `result` is an empty or nested tuple,
a sum, or a vector. Known zero-width layouts are not unknown representations.
Result carriers inherit the current generic [tuple](tuple-results.md),
[sum](sum-results.md) and [vector](simd.md) layouts, including a known-pointer
`BoxedRep Nothing` result without a WHNF guarantee. Unresolved RuntimeRep
shapes still reject.

The exception payload is a separate parameter. THC currently requires known
lifted or unlifted boxed payloads; unknown-levity and unboxed scalar payloads
reject. The payload's actual levity determines its argument flag. Lifted
payloads are not forced merely by raising them, and boxed-unlifted references
pass unchanged to the handler. Action and handler functions remain lifted closures.

These managed result layouts are broader than GHC 9.14.1's documented
one-machine-word native exception-continuation restriction. They are THC value
contracts, not a promise of binary compatibility with native GHC stack frames.
See [thread inspection](thread-inventory.md) for diagnostic thread observations.
