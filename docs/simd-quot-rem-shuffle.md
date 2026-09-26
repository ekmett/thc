# Integer vector division and vector shuffle

THC implements quotient and remainder for all twenty-four integer vector
shapes, and shuffle for those shapes plus the six Float/Double shapes. Both AST
and bytecode use the same raw public Vector API carriers and exact species.
The six wide byte/short shapes use the same lowering, completing their 147
local and memory operations without another vector representation.

Integer quotient rounds toward zero; remainder satisfies `q*y+r=x` lane-wise.
Word lanes divide unsigned, including the high bit of Word64. These operations
use ordinary compiled scalar-lane loops behind typed residual calls, avoiding
forced expansion of 64 divisions into one oversized guest target; no
SIMD-division speedup is claimed. Native defined
inputs exclude zero divisors and signed minimum divided by minus one. Host-side
zero-divisor controls remain explicit, including a zero in the last lane.

Shuffle selects from the concatenation of two vectors. GHC requires one literal
`Int#` index per result lane, in `0 .. 2*lanes-1`; lowering checks this and stores
one public `VectorShuffle` constant rather than materializing a runtime index
tuple. Lane bits are preserved, including floating signed zero and NaN payloads.

For example, `shuffleInt32X4# left right (# 3#, 4#, 1#, 6# #)` selects
`left[3], right[0], left[1], right[2]`.

`cabal run exe:thc-fixtures -- simd-arithmetic` generates real vector Haskell Core,
an independent scalar-lane native GHC oracle, and strict provenance/audits.
`SimdArithmeticTest` checks its results against a Kotlin BigInteger/bit-lane model,
then executes the unchanged Core on both backends, including each first installed
call and target validity. Three shuffle patterns cover both inputs, reversal,
rotation, and repeated first-left/last-right lanes, including the highest valid
concatenated index. Literal rejection covers 16-, 32-, and 64-lane tuples.
The corpus has 138 entries for 78 operations. The existing generated SIMD smoke corpus also
includes the new operations. Native scalar evidence does not require wide host
vector hardware and does not claim native wide-vector code execution.
