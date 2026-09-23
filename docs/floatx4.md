# Local FloatX4 foundation

This slice adds `broadcastFloatX4#`, `packFloatX4#`, `unpackFloatX4#`,
`plusFloatX4#`, `minusFloatX4#`, and `timesFloatX4#` on both executable backends.
The exact installed GHC 9.14.1 signatures define one `VecRep 4 FloatElemRep`
value. Pack consumes one logical `(# Float#, Float#, Float#, Float# #)` argument;
unpack produces that tuple. A vector is not a four-register unboxed tuple.

Every scalar lane has a concrete Float carrier and exact `FloatRep` proof.
Unknown scalar kinds cannot masquerade as Float lanes merely by claiming the
register name. Pack reads primitive Float locals, unpack writes them, and the
bytecode operations specialize on primitive `float` arguments. There is no
numeric widening through Double or `Number`.

The local carrier owns an immutable `FloatVector` of fixed `SPECIES_128`.
Arithmetic operates directly on that vector; it does not extract and rebuild
lanes between operations. Unlike the integer vector carriers, interpreted or
deoptimized FloatX4 storage retains the JDK vector object and its backing array.
Allocation elimination is a property to verify in compiled graphs, not an
interpreter representation claim. Java support is isolated in `FloatX4.java`.

This does not extend the vector ABI. Vector function arguments/results, PAP
prefixes, captures, ordinary lets, join arguments/results, constructor fields,
and vector leaves within tuples remain unsupported. Vector division, negation,
FMA, comparisons, other shapes, arrays, memory operations and FFI are outside
this slice. Supporting four Float tuple leaves does not support a vector leaf.

## Checks

`scripts/prepare-floatx4-audit.py` exports real pre/post-Tidy Core and compares
native GHC output with an independent binary32 Python model. All six primops
must remain in the actual Core, with exact pack/unpack logical signatures.
Every positive entry must pass the strict reachable audit with no missing
definitions; an actual vector-formal entry remains a negative frontier.

The scalar host ABI is unchanged. Finite arithmetic entries expose bounded
lane-sensitive integer checksums; they include binary32 integer-conversion and
arithmetic rounding boundaries. Exceptional entries encode each lane's class
in a separate five-bit field: NaN, positive/negative zero, infinities, exact boundary
subnormals/normals and other signed finite values. Signed zero is distinguished
by reciprocal sign. NaN arithmetic compares classification, not payload or sign.
Non-finite/out-of-range values are never converted to Int. A separate multiply
then add witness checks two roundings instead of fused evaluation.

JVM tests additionally compare primitive lane raw bits for movement and ordinary
arithmetic, with classification for arithmetic NaNs. Real Core tests validate
source/artifact hashes, both backends, all retained export stages, and positive
compiled-entry counter changes for every postcompile input. Handoff mode is
tested in a separate JVM run; no settling calls or retries substitute for entry.

On AArch64, preparation follows the existing integer SIMD policy:
`--export-only` supplies pre-Tidy Core and explicitly model-only expectations.
It makes no native or post-Tidy claim. Linux x86 preparation requires the native
oracle. Production graph captures are a separate gate: actual packed floating
ADD/SUB/MUL, no surviving vector/wrapper/array allocations or intermediate lane
boxes, no residual field traffic/calls, and no unintended fused arithmetic.

The [retained x86-64 graph checks](../bench/experiments/floatx4-foundation/README.md)
pass all twelve pre/post-Tidy × backend × arithmetic captures with physical
`VADDPS`/`VSUBPS`/`VMULPS`. Full default and handoff suites each pass 355 tests;
the public scalar Long result box remains and is accounted for separately.
