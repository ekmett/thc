# Unsigned scalar primops

The scalar audit used GHC's pinned
[`ghc-9.14.1-release` primop definitions](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/compiler/GHC/Builtin/primops.txt.pp)
against `scripts/core-capabilities.json`. The existing machine Int operations
already covered ordinary scalar arithmetic, comparisons and bitwise operations.
The first missing unsigned families now execute in both AST and bytecode:

| Carrier | Added primitives |
| --- | --- |
| Word# | `quotWord#`, `remWord#`, `gtWord#`, `geWord#` |
| Word8#/Word16#/Word32# | `quotWordN#`, `remWordN#`, `eqWordN#`, `neWordN#`, `gtWordN#`, `geWordN#` |
| Word8#/Word16#/Word32# | `andWordN#`, `orWordN#`, `xorWordN#`, `notWordN#`, `uncheckedShiftLWordN#`, `uncheckedShiftRLWordN#` |

There are 40 additions. Machine words retain all 64 bits in a Long, using Java's
unsigned division/remainder and comparison facilities. Narrow unsigned words
remain zero-extended Longs; masks truncate left shifts and complements and bound
division and comparison operands. Each bytecode operation has a constant width
mask and primitive operands/results. No aggregate transport changed.

`IntegerPrimopsAudit.hs` wraps every primitive with dynamic operands and the
existing Int# host boundary. `prepare-integer-primops.py` rejects any wrapper
whose intended primop disappears during GHC optimization, performs strict Core
auditing, and generates 56,791 native oracle rows. Inputs include every bit
position and its neighbors, zero, alternating patterns, the sign bit, all-ones
and equal/neighbor operands. Every byte is checked for complement and every
valid byte shift count; wider shifts cover every count and bit transition.

`IntegerPrimopsTest` independently checks those native results with BigInteger,
then executes every row on both runtimes before and after compilation. It
requires exactly one installed guest entry for every oracle row and checks wrong
arities in strict and diagnostic modes. Preparation is part of the normal
`scripts/prepare-tests.sh` flow; Gradle tracks the generated inputs and CI retains
the oracle and audit reports. SHA-256 manifests reject stale source or artifacts.

Division by zero and unchecked shifts outside `[0, width)` are outside the
numeric oracle. No semantics for those inputs are promised. This slice leaves
existing signed division and overflow behavior unchanged.

The separate [signed narrow slice](signed-narrow-primops.md) adds 36 arithmetic,
division and comparison operations with native/model checks. Remaining scalar
candidates include signed narrow shifts, cross-signedness narrow conversions,
width-specific counts, byte swaps and bit reversal. Explicit Int64/Word64
arithmetic remains separate from the small
[Int64 conversion/literal foundation](int64-conversions.md). The separate
[tuple arithmetic slice](tuple-arithmetic.md) now lowers quotient/remainder,
carry, overflow and full-width multiplication directly into exact result slots.
Aggregate arguments, PAPs, captures and sums remain
outside this slice; these additions do not establish complete GHC.Prim coverage.

Validation on Linux x86-64 with the pinned GHC/GraalVM toolchain: a fresh
`scripts/try.sh --offline --max-workers=4` passed all 288 JVM tests and built the
distribution. The exact dependency auditor passed 24 tests. A subsequent focused
run passed the stronger per-row compiled-entry assertion on both backends.
