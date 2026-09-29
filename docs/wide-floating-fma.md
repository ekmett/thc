# FloatX16 and DoubleX8 fused arithmetic

The four fused operations `fmadd`, `fmsub`, `fnmadd` and `fnmsub` accept
exact `FloatX16#` or `DoubleX8#` operands on the AST and bytecode backends.
Each takes three identical vector shapes and returns that shape. Wrong widths,
lane types, arities and lifted operands are rejected by the Core contract.

The formulas are `x*y+z`, `x*y-z`, `(-x)*y+z` and `(-x)*y-z`, respectively.
Operand signs are selected before a single fused rounding. This matters for
cancellation and signed zero. The runtime directly uses fixed-species
`FloatVector.SPECIES_512` and `DoubleVector.SPECIES_512` values; arithmetic does
not reconstruct THC lane wrappers. Primitive lane fields remain a separate
[durable heap-storage boundary](simd.md). Floating behavior follows Java
semantics; NaN payload selection is unspecified.
