# AST case-arm boundaries

An outlined arm is a distinct `FunctionRoot` with immutable `PASS_THROUGH` role.
It executes its body once. It does not consume self-tail transfers, install a
self loop, or run a generic trampoline. Scalar and typed self-call shortcuts
cannot mutate its frame and emit the targetless `AstSelfCall` singleton.
Transfers with explicit targets and owned arguments propagate to their real
caller. Ordinary function roots retain their existing behavior.

`Program` currently exposes an internal construction-time `outlineCaseArms`
selection for controlled integration. It defaults to false; public loading does
not automatically outline or retry compilation. When selected, non-atomic
scalar, tuple and sum case arms use this boundary. Selection stays outside the
arm: the scrutinee runs once, existing match profiles choose the alternative,
and original constructor/payload restoration precedes the call. No additional
branch profile or ever-seen flag is introduced.

The call inherits the caller's Bloom ancestry. It is not an ordinary non-tail
call with a reset Bloom and local trampoline. An outer backedge therefore
crosses the side root and reaches its original loop owner. The side root's own
Bloom contribution is only a conservative hint, not evidence of a live catcher.
Genuine non-tail calls inside the arm retain their ordinary returning
continuations. Lowering preserves the arm's original tail position; crossing
the new root boundary does not make a non-tail expression tail-positioned.

Live inputs use the existing immutable `CaptureLayout`, with exact primitive,
reference and aggregate fields, not an array of boxed scalar locals. Result
proofs and tuple destinations remain exact. Case/capture slots whose carriers
are already fixed are initialized during lowering; unknown captures retain
normal widening behavior. The selected side is ordinarily inlinable. No edge is
unconditionally marked no-inline.

An arm that references an outer lexical join remains in that join's activation.
There is no implicit cross-root transfer of join frame slots. Lambdas already
have their own roots; atomic arms remain inline.

Outlined programs enable internal continuation capture independently of their
external async-delivery policy. This includes operand sequencing, saved result
completion and the existing bounded stack driver; synchronous selection does
not turn on polling or external async delivery. The existing opaque BCO and
compact-traversal capture restrictions remain explicit. Mask/catch scopes,
tuple loans and child-update ownership use the existing continuation protocols.

This is the extraction mechanism, not automatic graph-budget adaptation or
tail-frame compaction. Selecting a budget boundary, generating a fresh target
after permanent compilation failure, and proving that saved identity-tail
activations can be elided are separate steps. An arm's `tailPosition` metadata
alone does not authorize eliding tuple completion, masks, catches or updates.

`PassThroughRootTest` covers root roles, explicit packet ownership, Bloom
backedges, typed/scalar self-shortcut suppression and cold compiled clones.
`CaseArmOutliningTest` covers actual Core lowering, cold compiled caller/callee
entry, selection effects, returning continuations, local joins, mixed typed
tuples, a real blocking async cut and original native-backed delimited resumes.
Prepare its existing fixture with:

```sh
cabal run exe:thc-fixtures -fdevelopment -- delimited-continuations
```
