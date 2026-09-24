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

The application proof captures only an exactly saturated scalar call whose
continuation belongs to the directly invoked callee root, including a cloned
target with the same body identity. A cold call segment lets the existing
worklist resume the callee before feeding its WHNF to the caller. An unrelated
nested root and an active mask fail closed; overapplication, aggregate calls,
and tail transfers remain outside this seam. No call packet is added to the
ordinary path.

These are two boundaries enabled only by the private test control. Other
force, tuple, mask, and handler edges do not yet capture caller segments.
There is no general async delivery, `throwTo`, or replay of an interrupted
effectful right-hand side.
