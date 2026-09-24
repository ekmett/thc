<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Resumable bytecode thunk proof

`ResumableThunkProofTest` uses a test-only Truffle Bytecode DSL root with two real
`Yield` instructions. It keeps a primitive `Long` and an object local live across
both yields. An observable effect runs before the first yield. The first entry
is explicitly compiled; another guest thread resumes the captured frame, and
two concurrent readers observe one published answer without replaying the
effect. The test checks the saved frame's primitive `Long` and object slot tags,
as well as the DSL local's `Long` type profile. The generated interpreter
materializes the frame in `handleYield`, not at ordinary bytecode entry.
Production `BytecodeRoot` does not enable yield yet.

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

This is a cooperative two-root proof, not `throwTo` support. It does not
deliver asynchronous exceptions, capture arbitrary safepoint PCs, or compose
arbitrary nested calls, Haskell handlers and mask state. An uncaptured caller
still fails closed rather than replaying effects. The next step is a general
caller-segment chain through those update and handler boundaries.
