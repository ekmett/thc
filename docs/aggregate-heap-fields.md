# Aggregate fields in boxed constructors

Both backends construct and match boxed values with exact unboxed tuple or
sum fields. This includes GHC 9.14.1's real `GHC.Core.TyCon.BoxedRep`: its source
field `{-# UNPACK #-} !(Maybe Levity)` becomes one logical worker field
`(# (# #) | Levity #)`, stored as a tag and one lifted reference.

Constructor metadata retains logical field order and shape. Lowering maps each
logical field to a contiguous range of existing final typed heap properties;
tuple components flatten recursively and sum payloads retain GHC's existing
slot proofs, with the [managed storage adaptation](sum-results.md) applied separately.
Empty tuples and scalar void components occupy no payload
property, but their expressions still execute. Scalar fields after a zero-width
or multi-register field retain their own positions. No boxed tuple/sum payload
array or additional guest carrier is introduced.

Lifted payloads remain lazy. A `BoxedRep Nothing` field uses a traced Object
property without claiming liftedness or evaluatedness. An explicit strict-field
annotation still demands the field. Demanding an unboxed field does not demand its
lifted children. Inactive sum reference slots are cleared null padding, not guest roots:
closure inspection omits them from its pointer list, compact copying ignores
them, and compact images preserve their inactive state. Active references retain
ordinary reachability, sharing and existing compact-region evaluation semantics.

The supported tuple leaves and sum payloads are the existing
[tuple](tuple-results.md) and [sum](sum-results.md) layouts. Logical shape, arity,
sum tags/projections, scalar carriers, vector species and heap ownership remain
checked. Families with two or more alternatives and sums inside recursive tuples
are supported, including sums nested inside sum payloads, managed addresses and
supported exact vector species. Nonrecursive unlifted tuple/sum lets use typed frame locals.
[Tuple captures](tuple-captures.md), [sum inputs and captures](sum-inputs.md) and [tuple join inputs](tuple-joins.md)
are supported separately. AST constructor workers support partial application
with these fields. Bytecode constructor lowering currently requires a direct
saturated application for aggregate fields.
