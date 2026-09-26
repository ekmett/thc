<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Delivery and continuation capture

Masking determines where asynchronous delivery may begin. It does not prove
that a child evaluation will return normally: that child can unmask, wait
interruptibly, or throw to itself. A masked caller still owes its unfinished
work to a suspended callee.

This contract separates those obligations. Both backends check ownership, masks
and saved call boundaries when `asyncExceptions` is enabled. The public Boolean
option defaults to off for AST and on for bytecode. Foreign execution and nested
public guest entries have a separate delivery-permission gate.
The existence of a resumable root alone does not establish either property.

## Delivery

Only a THC continuation point may claim a request. The claim belongs to the
current Java thread in the request's owning context. A Truffle wakeup may make
that thread runnable, but must not throw a guest exception from arbitrary Java
frames. A thread has at most one claimed request at a time.

For an external request, guest delivery follows this table:

| Haskell mask | Ordinary continuation point | Interruptible wait |
| --- | --- | --- |
| Unmasked | Allowed | Allowed |
| Masked, interruptible | Deferred | Allowed before the operation commits |
| Masked, uninterruptible | Deferred | Deferred |

A self-directed throw is synchronous and bypasses the Haskell mask, matching
GHC. It still requires a guest continuation point. An operation that already
committed is not retried after resumption; its result or failure must be saved.

Acknowledgement means that a handler or uncaught guest-entry boundary accepted
the delivery, not merely that the target observed a wakeup. Claimed requests
cannot be cancelled by a late wake failure. Pending sender interruption removes
an unclaimed outbound request; resuming that sender requeues the same token.

## Lowering

Lowering must distinguish two facts:

* Whether a point may accept delivery under its current mask.
* Whether evaluating a child may suspend before returning its result.

For future mask analysis, the abstract domain is inherited/unknown, unmasked,
masked-interruptible, or masked-uninterruptible. A known lexical scope can
simplify state changes and delivery checks. Independently compiled roots start with inherited masking
unless their entry convention explicitly establishes another state.
Eliminating a delivery check also requires ruling out a self-directed request;
the Haskell mask alone cannot establish that delivery is impossible.
The actual mask belongs to the executing thread. Observing one mask while
compiling does not make it a constant for other invocations or resuming threads.

Both backends currently use conservative continuation cuts and dynamic mask
checks, not this abstract interpretation. AST lowering covers ordinary Core
calls, cases, lets and local joins, with separate capture for typed results and
arguments. Unknown forms and subsystems with unsupported continuation state
remain rejected during construction.

The conservative suspension effect is independent of that mask. Unknown calls,
forcing a lazy value, and callbacks that may change masking can suspend.
Primitive arithmetic with non-suspending operands need not participate in
capture. Being pure, returning an unboxed value, or being called while masked
is not sufficient evidence that evaluation cannot suspend.

Every admitted node must establish one of these properties:

1. Its entire evaluation is non-suspending.
2. Every suspending child edge has a capture handler and a defined resume point.

An unclassified node has neither property. Async AST lowering must reject it
before execution; silently dropping an unsupported caller suffix is forbidden.
This check belongs to construction of the executable tree, not a per-call walk
of the tree. A caller's known mask cannot satisfy the check on its own.
The obligation includes argument evaluation, strict entry forcing, result
conversion and cleanup introduced by lowering. Forcing a strict argument in
host code before entering the root's capture handler is not covered by that
handler. Both backends place this forcing in the callee's resumable prologue,
including strict arguments supplied by partial application.
Admission must also match the execution route chosen by the parent. A saved
`executeLong` suffix does not cover a generic or tuple-returning entry; the
declared result convention must select the covered route before execution.
AST lowering sequences primitive and constructor operands into frame locals
before executing the operation. Calls preserve already evaluated arguments,
pending strict forces and typed destinations. Typed argument loans are released
after their values have entered the callee frame; saved tuple results use owned
storage. Operand temporaries remain live across a cut and clear when the saved
expression completes. `catch#`, masks, annotations and `keepAlive#` retain their lexical
handler or cleanup scope around resumed child steps. Recursive local joins poll
before executing the next selected body.

## Saved computation

The resume point owns the already-computed operands, live locals, pending
result representation and logical mask. Values in Java locals are included;
materializing a `VirtualFrame` alone does not save them. Typed handoff loans
must be copied into owned storage before their cleanup releases or clears them.

Ordinary typed execution must allocate no continuation record. A capture must
save every affected activation and assemble its remaining steps. Bytecode uses
its DSL yield frame; AST unwind materializes affected roots and records their
Java locals in resume steps. A true
tail call contributes no caller suffix. The approach follows
[A Technique for Implementing First-Class Continuations](https://web.archive.org/web/20070420042601/http://eval.apply.googlepages.com/stackhack4.html).

A thunk or call segment has at most one evaluator. Its saved identity
is claimed under the same ownership protocol as its original body. A second
interruption replaces consumed steps with the newly captured segment followed
by only the unconsumed old suffix. Completed effects and bindings are not
replayed. The resuming Java thread regains its previous ambient mask on every
exit, while the saved computation runs under its logical mask.

Continuation identity must match the suspended body, including legitimate
Truffle clones, or carry a validated tail-transfer witness. An uncaptured
callee cannot masquerade as its caller's continuation. The asynchronous payload
belongs to the delivery token; it is not the thunk's memoized answer. Ordinary
synchronous exceptions retain the normal thunk-update behavior.

## Foreign boundaries

Foreign execution permission is separate from the observable Haskell mask.
`GuestThreads` keeps a context-owned permission stack per Java thread. Public
guest entry pushes guest permission; outgoing JavaScript, Polyglot, managed
MD5/Sulong and managed-file operations, including teardown flushes, push foreign permission and restore
their previous permission in `finally`. A reentrant public guest entry pushes
guest permission above that foreign scope on the same Java thread. It may claim
at its own THC cut, subject to its unchanged Haskell mask. A self-directed
request bypasses that mask, but cannot claim while foreign permission is active.
The foreign scope also works before any guest thread is registered. A separate
Java-thread-local count records only whether an opaque foreign frame exists in
*any* THC context, so an uncaught callback into a second context retains its
async origin. It grants no delivery permission and shares no mask or mailbox.

Each public callback entry is a capture delimiter. No continuation may contain an
opaque Java frame or a released foreign-call argument loan. A caught callback
exception may leave the foreign call running normally. The callback's uncaught
boundary must acknowledge delivery and raise a foreign-visible failure with
the original payload. Unlike an ordinary synchronous guest exception, this
failure retains its async origin if foreign code propagates it back into an
outer guest thunk. The outer thunk parks without memoizing the async payload as
its answer or replaying the foreign call. Java may handle or propagate that
failure. It does not receive a resumable Java computation;
only guest work captured inside the callback remains resumable. Internal
capture signals, saved records and delivery tokens must never escape as foreign
return values.

Returning from a completed foreign call is not permission to poll before its
outcome has been saved. A later interruption must resume after that call. No
automatic retry of opaque foreign work is allowed.
The permission gate never polls on foreign return. Capture after a foreign
return still depends on the enclosing guest node's own resume proof; the gate
does not make an arbitrary AST caller or opaque Java frame resumable.

## Evidence and remaining checks

`GuestThreadsTest` checks Java identity, masking eligibility, nested entry
lifetime, request acknowledgement, foreign/guest/foreign nesting, cross-context
callback origin and wake/claim races. `ManagedFilesTest` checks embedding-stream
calls and teardown flushes. The optional `PolyglotFFITest` invokes an actual
public `EntryValue` from a JavaScript callback: an acknowledged request lets
JavaScript continue, while an uncaught request passes through
`EntryValue.publicSuspension` and leaves an outer thunk parked with one foreign
effect. The unit test checks the exact payload and cause; Polyglot wraps the
escaping failure before the outer caller sees it. These focused classes pass
in ordinary and dense handoff modes. The callback target is synthetic; native GHC callback Core
and general safe-FFI resumption are not established. `CallMaskSegmentsTest`
checks logical mask restoration across Java threads and rejects malformed
mask restoration. `ResumableThunkProofTest` rejects uncaptured caller updates
and unrelated continuation roots. `ThreadAsyncNativeTest` covers public
fork/throw/catch and lazy action heads with async explicitly enabled on both
backends. It and `AsyncStrictEntryNativeTest` pass in ordinary and dense handoff
modes; strict-entry checks preserve demanded PAP arguments and the caller.
The thread suite's nested uninterruptible-mask/unmask/self-throw case verifies that the original
handler receives the exception and that the outer mask is restored, interpreted
and compiled, before and after Tidy.

`AstContinuationTest` exercises a direct-MVar root after
explicit compilation, checks that the blocked entry ran compiled, and resumes
it on another Java thread. Its tuple is copied out of the producer's handoff
pool. Separate owned-thunk tests interrupt twice without replaying the prefix,
and keep an uninterruptibly masked caller's unfinished work when its child
unmasks. Construction admits the ordinary caller and strict-entry routes while
still rejecting conflicting representation proofs and unknown Core forms.
`LiveAsyncNativeTest` extends coverage to running loops, blocking waits,
blackhole ownership and repeated interruption using a native GHC result oracle.
Its expanded AST compiled-retention checks are still under diagnosis; the
public-thread and strict-entry passes do not establish the entire live suite.

STM transaction frames, compact traversal, opaque foreign execution and mixed
asynchronous/delimited capture retain separate lowering or continuation
barriers. Ordinary Core capture does not remove those subsystem requirements.

The callback permission gate is admitted only at a public guest entry whose
guest body already satisfies its backend's capture obligations. It does not
admit suspension across Java or callbacks from an arbitrary unclassified AST
node.
