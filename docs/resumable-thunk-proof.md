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

This is a cooperative, single-root proof. It does not deliver asynchronous
exceptions, capture arbitrary safepoint PCs, or resume nested calls and handler
frames. The next step is to capture a caller segment when a child yields, then
compose that segment with the child's continuation up to the thunk update or
exception handler. Only after those boundaries preserve their live operands,
locals and mask state can an asynchronous delivery use this mechanism.
