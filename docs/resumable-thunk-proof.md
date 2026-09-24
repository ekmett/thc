<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Resumable bytecode thunk proof

These private experiments preceded the
[production asynchronous exception path](async-exceptions.md).

`ResumableThunkProofTest` uses a test-only Truffle Bytecode DSL root with two real
`Yield` instructions. It keeps a primitive `Long` and an object local live across
both yields. An observable effect runs before the first yield. The first entry
is explicitly compiled; another guest thread resumes the captured frame, and
two concurrent readers observe one published answer without replaying the
effect. The test checks the saved frame's primitive `Long` and object slot tags,
as well as the DSL local's `Long` type profile. The generated interpreter
materializes the frame in `handleYield`, not at ordinary bytecode entry.
The production bytecode backend now uses the same Yield machinery at async cuts.

`Force` places the `ContinuationResult` in the thunk's existing value slot and
publishes a paused state under the ownership monitor. Claiming that state
consumes the continuation once. The captured frame replaces the original
target and environment, which are cleared at publication. Side-effecting
thread-local actions are deferred during that short publication. A continuation
from a different root, or a yield through an uncaptured caller update frame,
fails closed instead of replaying an earlier effect or leaving a blackhole.

`CallerContinuationProofTest` adds one caller segment. A test-only Bytecode DSL
`TryCatch` catches the dedicated internal `ThunkSuspended` signal around a
shared child thunk and yields the caller frame. The signal is an
`AbstractTruffleException`, never a Haskell `GuestException`; other guest
failures are rethrown. On the cold resumption path `Force` first completes the
child, then supplies its answer to the caller continuation. A second child
yield leaves the caller segment parked; two callers and concurrent readers
still observe one child effect. If the child instead finishes with a guest
failure, the resumed caller rethrows and memoizes that failure normally.
The caller's first invocation is explicitly compiled, and the captured frame
retains a primitive `Long` operand that was live below the nested call and
`TryCatch` boundary. The test-only child operation calls a distinct Truffle
root through `IndirectCallNode`, so its force has a separate `VirtualFrame`
and ordinary caller entries need no frame materialization. Only the yield
captures the caller frame. The dependency-resolution path for an already
suspended thunk is a cold Truffle boundary. Production Core lowering is still
yield-disabled.

The cold resolver now walks parked caller dependencies iteratively, then
claims and resumes each continuation from the child outward. A changed link
causes a rescan before any claim. When a child yields again, it reports the
requested paused thunk as the update boundary; a new caller must not skip an
intermediate continuation. Tests cover 64 parked callers, two compiled caller
frames with primitive operands, two callers sharing a child, repeated yields,
concurrent readers, and guest failure through three callers.

This remains a cooperative test-only proof, not `throwTo` support. It does not
deliver asynchronous exceptions or capture arbitrary safepoint PCs. An
uncaptured caller still fails closed rather than replaying effects. Production
Core lowering is yield-disabled.

The production bytecode lowering of synchronous `catch#` and masking actions
now exposes the protected action, handler, and mask restoration as DSL
`TryCatch`/`TryFinally` control flow. Yield is still disabled there. A future
yield would suspend rather than finish a lexical mask scope: it must restore
the carrier host thread's ambient mask while saving the logical active and
prior masks in the continuation, then re-enter that active mask on resumption.
Ordinary final exit restores the lexical prior mask. The current host-thread
`ThreadLocal` mask alone cannot express that cross-thread handoff.

`MaskContinuationProofTest` now exercises that boundary with a test-only DSL
root using the production mask and guest-failure operations. Two nested masks
and a guest catch surround a shared suspending child. The captured frame keeps
the primitive operand and the logical active and prior masks. A cold park
operation restores the original carrier's ambient mask before `Yield` returns;
the resumed bytecode re-enters the saved active mask before consuming the
child's answer. `Force` restores the resumer carrier's own ambient mask in a
`finally` around `ContinuationResult.continueWith`, even when it differs from
the original carrier's. The test checks normal completion, a caught guest
failure under the handler mask, cross-thread resume, and no original-body
replay, with an explicitly compiled initial caller entry. A separate uncaptured
caller confirms that a Haskell catch rethrows the internal suspension signal,
restores its mask, and fails closed without invoking the handler.
The cold carrier-mask guard also encloses any resumed `TailCall` trampoline:
a tail target still observes the logical active mask, and only completion of
that chain restores the resumer carrier's prior ambient mask.

This proof names the outermost prior mask explicitly. Production suspension
would need to enumerate every active mask and handler segment to find that
carrier boundary, save each logical scope, and reinstate them in order. The
test does not enable production `Yield`, async delivery, or general `throwTo`.
