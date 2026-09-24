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
worklist resume the callee before feeding its result to the caller. An unrelated
nested root fails closed; overapplication, aggregate calls,
and tail transfers remain outside this seam. No call packet is added to the
ordinary path.

The cold call segment is separate from a Haskell thunk update. Its resumed
application result can be a lazy `Thunk`: the captured caller receives that
same object without entering it, and a later demand may force it. Ordinary
thunk updates still require WHNF. Pending aggregate results and operand
handoff ownership are uncaptured; only the scalar result already on this
direct call edge is fed to its caller.

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

These are two boundaries enabled only by the private test control. Other
force, tuple, mask, and handler edges do not yet capture caller segments.
There is no general async delivery, `throwTo`, or replay of an interrupted
effectful right-hand side.
