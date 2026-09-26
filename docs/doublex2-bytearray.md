# Bounded DoubleX2 ByteArray access

This page retains the original memory-operation checkpoint and its measurements.
Current execution uses raw `DoubleVector.SPECIES_128` values, not a `DoubleX2`
wrapper. Historical transport restrictions below are superseded by the
[current SIMD contract](simd.md); the old graphs do not certify the new runtime.

This slice adds exactly six local GHC 9.14.1 operations without a vector ABI:

| Operations | Index stride | Access width |
| --- | ---: | ---: |
| indexDoubleX2Array#, readDoubleX2Array#, writeDoubleX2Array# | 16 bytes | 16 bytes |
| indexDoubleArrayAsDoubleX2#, readDoubleArrayAsDoubleX2#, writeDoubleArrayAsDoubleX2# | 8 bytes | 16 bytes |

Index consumes a ByteArray# and Int#, returning the exact
`VecRep 2 DoubleElemRep`. Read consumes a mutable array, Int# and State# and
returns `(# State#, DoubleX2# #)` only to an immediate, structurally validated
case. Write consumes the array, Int#, exact local vector and State#, returning
State#. There are two logical read fields, not State plus two scalar lanes.

The closed family registry distinguishes signed Int32, unsigned Word32, Float32
and Double64. Exact integer lane/constructor counts, State order, unlifted
non-coercion binders, lexical identities and the producer/whole-binder annotation
checks remain mandatory. General tuple/vector function, join, capture, heap,
foreign/Addr and pinned-memory transport rules do not change.

The immutable DoubleVector value uses one ByteVector128 transfer and
LongVector/DoubleVector reinterpretation. Big-endian conversion reverses bytes
within each 64-bit lane. The memory path does not extract/repack scalar lanes,
perform floating arithmetic, numerically convert values or canonicalize NaNs.
This is ordinary native-endian, non-atomic managed byte-array storage.

Bounds remain full-width Long checks before scaling or narrowing:
`size >= 16 && index >= 0 && index <= (size - 16) / stride`.
Invalid ranges deoptimize before fault allocation; invalid stores preserve every
byte. State evaluation and validation precede memory access and result publication.

## Validation contract

The genuine GHC fixture uses existing Int/Double scalar-array aliases to observe
all 64 lane bits without scalar bitcast primops. Its independent integer-byte
model has 4,384 portable rows over twelve scalar-host wrappers. It checks signed
zeros, subnormal/normal boundaries, finite values, infinities and selected signed
quiet-NaN payloads. Raw writes use the two input words XOR the selected output
byte, avoiding arithmetic overflow while observing all 64 backing bytes.
Offset and lane rotations cover every safe offset, not an exhaustive Cartesian
product. The separate 576 selected native signaling-NaN observations are not a
portable scalar copy/pack/unpack or arithmetic-payload guarantee.

Finite graph witnesses consume both lanes using weights 3/5 and exact integer
seeds around 2^32 and 2^40, distinguishing binary64 from binary32. The maximum
absolute weighted sum is below 2^44. Their numeric checksums are separate from
the raw-bit corpus. Graph acceptance requires one live packed 128-bit caller-array
load/store, both f64 lane paths, unchanged installed targets and no live private
carrier/vector/payload allocations or unexpected calls. See the
[evidence harness](../bench/experiments/doublex2-bytearray/README.md).

JVM tests independently reconstruct the complete portable inventory, compare
native/model rows and require exact per-row compiled guest-entry deltas, active
target identities, last-tier validity, empty handoff pools and zero traps or
blackholes on both backends and both inlining modes. Default and dense-handoff
runs are separate. Production automatic compilation settings are unchanged.
An isolated cold bounds-transition test follows the existing memory-family
pattern: explicitly compile one fresh guest, invalidate on its first invalid
access, verify no effects, then recover without recompilation.

## Verified x86-64 checkpoint

Frozen source `b6ba65f3ba54f937e2b88f61f04e7e4f425a1e5c` passes all 4,384
native/model rows, 545 JVM tests in each of default and dense-handoff modes,
and 294 Python checks both normally and under `-O`, with no final skips.
Each complete JVM mode requires 35,072 compiled calls and 104,704 exact guest
entries. The separate 96 cold-transition cases pass without partial effects.

Sixteen Double captures pass on first check: 512 installed-target comparisons,
sixteen live f64 lane products, sixteen exact store inputs, eight XMM
VMOVDQU32 loads and eight XMM VMOVUPD stores. Fresh Int32, Word32 and Float
regressions also pass sixteen captures each on the same runtime, totaling
64 accepted graph/LIR records and 3,840 installed-target comparisons.
No runtime or reader correction, compiler-budget increase or guest retry was
needed in this campaign. The [retained evidence](../bench/experiments/doublex2-bytearray/evidence-x86_64/README.md)
records source/binary hashes, commands, first development-test failures and scope.

No big-endian execution, non-x86 packed code, throughput, no-spill or globally
allocation-free behavior is claimed.
