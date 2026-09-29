# Core entry contracts and storage evidence

THC keeps GHC's requested call-by-value convention separate from facts about a
value's type, evaluatedness and physical storage. Each fact authorizes different
lowering choices; none permits eagerly evaluating an arbitrary lazy argument.

## Entry obligations

The exporter retains existing GHC call-by-value marks after Tidy and can derive
a THC-internal proposal for eligible pre-Tidy workers and joins using the pinned
compiler's own selection logic. It does not reinterpret printed demand strings
or modify GHC's native calling convention.

`entryStrict` marks an obligation to supply a direct weak-head-normal-form value
when the body starts. It is not evidence that the original argument expression
was already evaluated. THC enforces marked arguments at saturation, including
supplied PAP prefixes, the saturated portion of overapplication, host entry and
tail reentry. Creating a partial application remains lazy.

A known saturated call can avoid constructing a thunk whose only purpose would
be immediate evaluation. Dynamic calls enforce the same obligation at entry.
Joins and direct-self loops enforce it when moving arguments, before the body
uses the resulting evaluatedness facts.

The separate caller-demand permission is opt-in with
`-Dthc.callDemands=true`. It governs evaluation when a particular application
executes; it does not strengthen an unrelated callee's entry contract.
See [Core evidence and local joins](core-evidence.md) for the metadata format,
export boundaries and independent proof rules.

## Typed storage and recursive cells

A type proof and a storage fact are different. Captures made while a recursive
group initializes retain its cells by identity. After the complete group is
initialized, newly published values can use direct reads without breaking
recursive knots or sharing. A value cannot bypass a runtime thunk merely
because its source expression was known to be evaluated.

Constructor and capture layouts retain exact supported primitive fields and
reference carriers. Lazy lifted fields stay generic; strict polymorphic fields
do not acquire a concrete constructor type. Root, self-call and join entry
restore the types justified by their proofs. Supported aggregate fields have
separate logical-shape and ownership rules.

The `thc.staticShapeUnchecked` and `thc.constructorClassIdentity` options are
independent, default-off storage experiments. They rely on allocation ownership
and guarded class-identity proofs, not on dropping checks for arbitrary host
objects. [Owned static storage](core-evidence.md#owned-static-storage) describes
their exact boundaries. Current class-owned layouts also retain a fallback for
shared carrier classes; visibility of a JVM class is not a Haskell type proof.

## Calls and validation

Direct self calls use parallel local moves and internal control transfers;
longer tail cycles use the [matching-root protocol](typed-tail.md).
These paths preserve PAP arguments, changed captures and entry obligations.

The [residual call ABI](call-boundaries.md) remains `Object[] → Object`.
Typed local storage does not by itself eliminate argument arrays or primitive
boxing at a call that does not inline. Compiler graphs and measurements must
establish those outcomes separately.
