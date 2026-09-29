# GHC absent-value fillers

Both backends support GHC's typed `LitRubbish` for scalars, vectors, tuples and
sums. It is an already-evaluated, non-bottom absent filler: a surrounding
`DEFAULT` case can continue. Its payload is unspecified and must not be observed.
There is no Haskell source syntax for this literal.

CBD retains a dedicated rubbish tag. The expression's representation metadata
provides its full type shape, including tuple components and sum alternatives.
The inspection form is `["lit","rubbish",null,metadata]`; erased type applications
retain the applied type's representation. Missing or contradictory metadata,
opaque aggregate shapes and rubbish literal patterns are rejected.

The runtime keeps rubbish explicit until a consumer needs a value. Scalars use
valid primitive or inert boxed carriers, vectors use the declared species, and
aggregate results use the ordinary typed destination slots. Sums select a valid
alternative and initialize its payload and inactive slots. Unknown boxed levity
retains a reference carrier without inventing a levity proof.

Rubbish currently initializes the required slots. Removing unused writes also
requires eliminating their reads, captures and continuation storage; leaving
stale values in live slots would be incorrect.
