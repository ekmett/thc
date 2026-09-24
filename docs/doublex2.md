# Local DoubleX2 foundation

This slice adds `broadcastDoubleX2#`, `packDoubleX2#`, `unpackDoubleX2#`,
`plusDoubleX2#`, `minusDoubleX2#`, and `timesDoubleX2#` to both backends.
The exact GHC 9.14.1 vector proof is `VecRep 2 DoubleElemRep`. Pack takes one
logical `(# Double#, Double# #)` argument; unpack produces that tuple.
The vector, two-leaf tuple, boxed pair, and one-reference byte array remain
separate representations. `Int64X2#` and `FloatX4#` are also distinct.

Scalar lane proofs require the concrete Double kind and `DoubleRep`. AST pack
reads primitive Double frame slots; unpack writes primitive Double slots.
Bytecode operations specialize on primitive `double` arguments and typed locals.
The immutable local carrier retains `DoubleVector.SPECIES_128`, preserving
vector arithmetic between operations. Its interpreter/deoptimization storage
includes the JDK vector object and backing array, as for FloatX4. Elimination of
these objects and packed instructions must be demonstrated separately in compiled
production graphs; type acceptance alone does not establish either claim.

No vector ABI boundary is added. Vector function arguments/results, PAP prefixes,
captures, ordinary lets, join arguments/results, constructor fields and tuple
leaves remain unsupported. Division, negation, FMA, comparisons, other shapes,
addresses and FFI are outside this slice. The separate
[DoubleX2 ByteArray slice](doublex2-bytearray.md) adds six local memory operations:
`indexDoubleX2Array#` and `indexDoubleArrayAsDoubleX2#` have different index units.
Scalar Double-array coverage remains independent.

## Correctness gates

`compiler/test-fixtures/SimdDoubleX2.hs` is a genuine GHC input, with a separate
native driver. The preparer requires all six operations in retained Core, exact
logical signatures, thirteen accepted scalar-host entries, and a specifically
rejected vector-formal entry. The 3,390-row corpus contains lane-sensitive finite
checksums, exact raw binary64 results for non-NaN edges, movement/broadcast cases,
and a separate multiply/subtract rounding witness. Arithmetic NaNs compare by
class, without specifying payload/sign. Non-finite values never enter double-to-Int
conversion. Direct helper tests separately preserve quiet NaN movement payloads.

The independent model uses integer significands and exponents for binary64
round-to-nearest/ties-to-even addition, subtraction, multiplication, and conversion.
It does not calculate expected values with host floating arithmetic. Boundary
checks include both signed zeros, minimum/maximum subnormal, minimum normal,
maximum finite, infinities, NaNs, underflow ties, overflow, integers around 2^53,
and values distinguishable in binary64 but not binary32. A secondary test checks
this model against host arithmetic for edge pairs and deterministic random inputs.

JVM tests verify source/artifact fingerprints and every declared row in every
available export stage on both backends. After compilation, every row must produce
the exact guest-entry count derived independently from that stage's retained Core.
The fixture-specific count rejects unknown, lazy, recursive or path-dependent calls.
The host's active direct call must still name the selected compiled entry, which
must remain installed after each row. This includes helper calls where GHC retains
them; helper counts alone cannot pass a missing selected-target identity/validity
check. There are no settling calls or recompilation retries after compilation.
Default and opt-in handoff are separate test runs.

On AArch64, ordinary preparation uses `--export-only`: real pre-Tidy Core and
explicitly model-only expectations. It claims neither a native oracle nor a
post-Tidy export. Full preparation on a supported GHC native backend requires fresh
pre/post exports and native output matching every model row. Local LLVM binaries
must satisfy GHC's actual supported version range; a misleading versioned symlink
is not native-oracle provenance. An independently verified source-matched oracle
and exports may be transferred for Graal validation without claiming local GHC
native execution.

The [retained native-backed production captures](../bench/experiments/doublex2-foundation/README.md)
pass all twelve pre/post × backend × arithmetic controls on AArch64. Final
allocated-register LIR contains packed 128-bit Double `FADD`, `FSUB` and `FMUL`,
with temporary vector allocations and floating lane boxes eliminated. The
public Long result box remains. The source-matched oracle was produced by
supported x86 GHC, not the local unsupported LLVM configuration. Both focused
JVM modes pass all native rows and strict compiled-entry/target checks.
