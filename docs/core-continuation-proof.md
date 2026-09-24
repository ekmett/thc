<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Core bytecode continuation proof

`CoreContinuationAudit.hs` exports ordinary GHC Core. Native GHC, THC AST,
and THC bytecode return 108 for the local-force example and 208 for a
non-tail application with work after its call. A private in-process test control arms a
checkpoint at its genuine `noDuplicate#` application; this checkpoint is not
part of `noDuplicate#` semantics and cannot be requested by exported Core.
The shared child thunk yields a Bytecode DSL `ContinuationResult`, and the
parent `ForceLocal` captures its own caller segment. Another force finishes
the child, supplies its result to the caller segment, updates the local, and
publishes the parent once. The test verifies that the checkpoint runs once,
the caller retains a primitive Long frame slot, and the initial caller enters
an explicitly compiled target.

Yield generation is enabled on the Bytecode DSL root, but ordinary Core emits
no Yield instruction. The generated cached interpreter calls `frame.materialize()`
inside `handleYield` only; the normal path does not capture a continuation.
The resumed segment may run cold. A separate direct call through an uncaptured
root and malformed continuation inputs fail closed.

The scalar application proof captures only an exactly saturated call whose
continuation belongs to the directly invoked callee root, including a cloned
target with the same body identity. A cold call segment lets the existing
worklist resume the callee before feeding its result to the caller. An unrelated
nested root fails closed; overapplication, aggregate calls,
and tail transfers remain outside this seam. No call packet is added to the
ordinary path.

The cold call segment is separate from a Haskell thunk update. Its resumed
application result can be a lazy `Thunk`: the captured caller receives that
same object without entering it, and a later demand may force it. Ordinary
thunk updates still require WHNF. Pending aggregate results and operand
handoff ownership are uncaptured on that application edge; only the scalar
result already on the direct call edge is fed to its caller.

Only a yielded call allocates a segment. Its continuation is claimed once,
published with a wakeup for competing readers, and retained across repeated
yields. Guest failure data is memoized without sharing a mutable Truffle
exception trace; an unsupported host unwind closes the segment without
replaying its prefix. A cold segment now records its logical mask and its
caller's mask. Each yielding caller parks to its root-entry mask before
unwinding, while the yielded signal carries the active mask that cannot be
read back from the carrier thread. Resumption reinstalls that logical mask;
completion and guest failure restore the caller's mask, and the host carrier's
ambient mask is restored even on another thread. A three-root
masked→unmasked→masked test exercises repeated yields and failure. The ordinary
non-suspending nested call still explicitly compiles; only the cold path
allocates mask metadata.

These boundaries are enabled only by the private test control. Other force,
tuple, mask, and handler edges may still lack captured caller segments.
There is no general async delivery, `throwTo`, or replay of an interrupted
effectful right-hand side.

A private handler-cut test now claims a captured bytecode caller while its
exact shared child thunk remains parked. A distinct async-origin marker enters
that caller's saved Yield continuation, and the nearest test-only DSL catch
handler consumes it under the restored logical mask. The child later resumes
once from its saved frame; its effectful prefix is not replayed, and neither
the child nor an unhandled marker becomes a memoized guest exception. This is
not a `throwTo` entry point or a production `catch#` implementation.

One genuine GHC `catch#` action now uses a private checkpoint variant of
`InvokeIOAction`. Its exact action target and recursive unboxed-tuple shape are
checked before an action continuation becomes a cold call segment. The caller's
Bytecode DSL frame retains the original typed `BytecodeTupleSlots`, and only a
completed child result is copied into them on resume. Before a completed call
segment wakes another thread, it copies a pooled `TupleComplete` into an owned
`HandoffStorage` and releases the thread-local result-slab loan. A result-shape
failure releases that loan too. A synchronous guest failure enters the original
GHC handler; the handler's lifted payload stays lazy. Original native GHC, AST,
ordinary bytecode, and explicitly compiled checkpointed bytecode produce 42
for the successful action and 77 for a caught `raiseIO#`. Repeated suspension
does not replay the action prefix, and another host thread can complete the
shared action before the catch caller resumes.

The private handler cut now also reaches that original GHC `catch#` frame. It
claims a saved caller only when its Yield signal names the exact parked
`CallSegment` created inside a caught IO action. A cold, action-bound token
enters the saved continuation; `ResumeIOAction` verifies that token and raises
a distinct private async-origin signal. Only the checkpointed catch extractor
admits it. Ordinary `checkpoint == null` still emits the original
`InvokeIOAction` and `RequireGuestFailure`, without Yield or private tuple
dispatch, and rejects this signal.

An original nested `catch#` fixture returns 43 normally under native GHC,
AST, and bytecode. Cutting at its inner handler with a boxed 7 yields 78
(inner handler adds 70, outer action adds 1), rather than the outer handler's
1007. The inner action remains parked and later completes from its saved frame
without replaying its checkpoint. The tests also reject a wrong parent/child
pair and preserve each host carrier's ambient mask across the handler cut.
This is deterministic, in-process proof control; it does not admit production
`throwTo`, arbitrary suspension inside a handler body, or uncaptured nested
call edges. Such edges still fail closed. The diagnostic stack snapshot is
not a resumable continuation.
