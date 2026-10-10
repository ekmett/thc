<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Delivery and continuation capture

Masking determines where asynchronous delivery may begin. It does not prove
that a child evaluation will return normally: that child can unmask, wait
interruptibly, or throw to itself. A masked caller still owes its unfinished
work to a suspended callee.

This contract separates those obligations. Ordinary programs on both backends
always retain saved call boundaries and check ownership and masks for delivery.
The public Boolean `asyncExceptions` option defaults to off on both backends,
speculating only on ordinary polling until concurrency is admitted; explicit
`true` enables polling eagerly. Foreign execution and nested public guest entries
have a separate delivery-permission gate.
The existence of a resumable root alone does not establish either property.

## Delivery

Only a THC continuation point may claim a request. The claim belongs to the
current logical guest thread in the request's owning context. A Truffle wakeup may make
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

Both backends use conservative continuation cuts and dynamic mask checks.
The mask belongs to the executing guest thread; a mask observed during
compilation is not a constant for another invocation or resumption. Unknown
forms with unsupported continuation state reject during construction.

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
handler. A saved preparation must retain the argument packet, current force and pending
invocation, including strict arguments supplied by partial application. Typed
preparation acquires transport loans only after forcing completes.
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

Bytecode `SavedGuestContinuation` views share a terminal claim for the exact raw
saved token. Distinct tokens remain independent even when they share a frame or
come from a copied delimited image. A real bytecode cut records weak original
execution-context provenance before publishing its token. Resume and discard
validate that context and any owned native requests before claiming; Force also
preflights before changing its outer thunk, call segment or delivery request.
Guest or cleanup failure cannot reopen a claim, and repeated discard does not
repeat cleanup. These laws use stock Truffle APIs in both runtime modes. They
apply to THC's owning adapters, not arbitrary direct upstream continuation calls.

Claiming a one-shot AST activation detaches its old yield marker. Resumption
removes completed steps as it advances, including steps inside lexical scopes.
After validating a completed child's identity and result, the caller transfers
that result out of its completion carrier. A discarded result therefore need not
remain reachable until an unrelated suffix returns. Live frame values, Java
references and active `keepAlive#` extents retain their normal reachability.
Reusable delimited suffixes retain immutable scope recipes; each invocation owns
fresh progress through those recipes, including nested scopes.

Continuation identity must match the suspended body, including legitimate
Truffle clones, or carry a validated tail-transfer witness. An uncaptured
callee cannot masquerade as its caller's continuation. The asynchronous payload
belongs to the delivery token; it is not the thunk's memoized answer. Ordinary
synchronous exceptions retain the normal thunk-update behavior.

## Foreign boundaries

Foreign execution permission is separate from the observable Haskell mask.
`GuestThreads` keeps a context-owned permission stack per Java thread. Public
guest entry pushes guest permission; outgoing JavaScript, Polyglot, linked
native and managed-file operations, including teardown flushes, push foreign permission and restore
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

## Boundaries

BCO instruction positions, operand stacks and pending applications survive
one-shot asynchronous and stack cuts; see [GHC bytecode objects](ghc-bco.md).
Explicit delimited capture through BCO frames and opaque foreign execution
remain barriers. A fresh AST capture traverses the exact saved caller scopes,
allowing a matching inner prompt to handle it before a thunk update. Reaching a
thunk update rejects capture and publishes the failure through its parked
parents without replaying their suffixes. The driver validates each parked
caller's identity before forwarding capture. A yielded capture marker is not
an AST resume point; parked bytecode invocation recapture remains unsupported.

Internal stack cuts preserve the active [STM](stm.md) attempt. External
interruption retires its old transaction log before propagating the original
request. Explicit checkpoint/delimited capture across transactions is unsupported.

A callback permission gate is valid only at a public entry whose guest body
meets these capture obligations. It cannot make an opaque Java caller resumable.
