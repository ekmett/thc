<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Core bytecode continuation proof

`CoreContinuationAudit.hs` exports ordinary GHC Core. Native GHC, THC AST,
and THC bytecode return 108. A private in-process test control arms a
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

This is one local-force boundary, enabled only by the private test control.
Other application, force, tuple, mask, and handler edges do not yet capture
caller segments. There is no general async delivery, `throwTo`, or replay of
an interrupted effectful right-hand side.
