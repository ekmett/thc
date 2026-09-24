<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Delivery and continuation capture

Masking determines where asynchronous delivery may begin. It does not prove
that a child evaluation will return normally: that child can unmask, wait
interruptibly, or throw to itself. A masked caller still owes its unfinished
work to a suspended callee.

This contract separates those obligations. The bytecode implementation already
checks ownership, masks and saved call boundaries. A restricted internal AST
subset now checks admission; nested foreign-entry enforcement is in progress.
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

Bytecode lowering currently uses conservative continuation cuts and dynamic
mask checks, not this abstract interpretation. The first AST admission gate is
a syntactic whitelist, not a general suspension-effect analysis.

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
handler. Bytecode places this forcing in its resumable prologue; the first AST
subset rejects strict entry marks until it has equivalent coverage.
Admission must also match the execution route chosen by the parent. A saved
`executeLong` suffix does not cover a generic or tuple-returning entry; the
declared result convention must select the covered route before execution.

## Saved computation

The resume point owns the already-computed operands, live locals, pending
result representation and logical mask. Values in Java locals are included;
materializing a `VirtualFrame` alone does not save them. Typed handoff loans
must be copied into owned storage before their cleanup releases or clears them.

Ordinary typed execution must allocate no continuation record. A capture must
save every affected activation and assemble its remaining steps. Bytecode uses
its DSL yield frame; AST unwind materializes only the explicitly covered roots
and records their Java locals in resume steps. A true
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

The required foreign execution permission is separate from the observable
Haskell mask.
The intended dynamic nesting is guest entry, opaque foreign call, fresh guest
callback entry, foreign return, and outer guest return. Each transition restores
the preceding permission in `finally`; a callback does not acquire a new Java
thread identity. It may accept delivery subject to its Haskell mask.

Each callback entry must be a capture delimiter. No continuation may contain an
opaque Java frame or a released foreign-call argument loan. A caught callback
exception may leave the foreign call running normally. The callback's uncaught
boundary must acknowledge delivery and raise a foreign-visible failure with
the original payload. Unlike an ordinary synchronous guest exception, this
failure must retain its async origin if foreign code propagates it back into
an outer guest thunk. The outer thunk must not memoize the async payload as its
answer. Java may handle or propagate that failure. It does not receive a resumable Java computation;
only guest work captured inside the callback remains resumable. Internal
capture signals, saved records and delivery tokens must never escape as foreign
return values.

Returning from a completed foreign call is not permission to poll before its
outcome has been saved. A later interruption must resume after that call. No
automatic retry of opaque foreign work is allowed.
The nested permission barrier and callback integration remain separate work;
the existing public guest-entry acknowledgement does not by itself implement
this contract for an arbitrary foreign callback.

## Evidence and remaining checks

`GuestThreadsTest` checks Java identity, masking eligibility, nested entry
lifetime, request acknowledgement and wake/claim races. `CallMaskSegmentsTest`
checks logical mask restoration across Java threads and rejects malformed
mask restoration. `ResumableThunkProofTest` rejects uncaptured caller updates
and unrelated continuation roots. `LiveAsyncNativeTest` checks compiled loops,
blocking waits, blackhole ownership and repeated interruption; its source
fixture also has a native GHC result oracle. `ThreadAsyncNativeTest` covers
public fork/throw/catch and lazy action heads.
Its nested uninterruptible-mask/unmask/self-throw case verifies that the original
handler receives the exception and that the outer mask is restored, interpreted
and compiled, before and after Tidy.

`AstContinuationTest` exercises the internal admitted direct-MVar root after
explicit compilation, checks that the blocked entry ran compiled, and resumes
it on another Java thread. Its tuple is copied out of the producer's handoff
pool. Separate owned-thunk tests interrupt twice without replaying the prefix,
and keep an uninterruptibly masked caller's unfinished work when its child
unmasks. Construction rejects unsupported parent expressions and strict-entry
forcing. This is a small synthetic Core proof: public AST async entry remains
disabled until calls, cases, masks and handlers have complete coverage. A tuple
case around a direct MVar read is admitted only with a non-suspending Int#
literal suffix and an exact Long entry convention. Its compiled test checks
cross-thread resumption after the read; conflicting entry conventions and
unsupported suffixes are rejected during construction.

Nested foreign callback checks must land before those paths are advertised as
admitted. They must distinguish successful interruption inside a callback from
unsupported capture across Java.
