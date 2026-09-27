# DoubleX2 operations and checks

`DoubleX2#` has exact GHC representation `VecRep 2 DoubleElemRep` and uses a raw
`DoubleVector.SPECIES_128` value. Pack takes one logical
`(# Double#, Double# #)` argument and unpack returns it. The vector, scalar
tuple, boxed pair and byte array are distinct representations; equal width does
not make `Int64X2#` or `FloatX4#` compatible.

Both backends use primitive Double lanes and exact `DoubleRep` proofs without
narrowing through Float. Vector arithmetic retains the raw Vector API result.
The [guest transport contract](simd-families.md) includes calls, PAPs, joins,
tuples and owned captures/heap fields. Public host vector arguments/results
remain unsupported.

## Operation scope

The foundation fixture focuses on broadcast, pack, unpack, add, subtract and
multiply. Current execution also has generated negate, divide, insertion,
min/max and shuffle operations, plus four fused multiply/add variants.
See [generated arithmetic](simd-wide-arithmetic.md),
[floating extrema](floating-vector-minmax.md),
[shuffle](simd-quot-rem-shuffle.md) and the [capability checklist](primops.md).
[DoubleX2 ByteArray operations](simd128-array-memory.md) distinguish packed-vector
indices from scalar-Double offsets. [Address operations](simd-address-families.md)
have their own memory rules; ordinary scalar Double arrays are independent.

## Original-Core and independent-model checks

```sh
python3 scripts/prepare-doublex2-audit.py
./gradlew testDefault --tests thc.runtime.SimdDoubleVectorTest
```

The producer retains all six foundation operations and exact logical signatures
in genuine pre/post-Tidy Core, with strict audits for its scalar-host entries.
The `vectorArgument` negative control describes a public host boundary;
backend loaders accept exact guest vector formals. Forged shapes and scalar
proofs remain errors even in diagnostic mode.

The independent binary64 model uses integer significands/exponents and
ties-to-even rounding, not host floating arithmetic. Its edge corpus covers
signed zeros, subnormal/normal boundaries, maximum finite values, infinities,
NaNs, underflow ties, overflow, integers around 2^53 and binary64-only precision.
Arithmetic NaNs compare by class without specifying payload or sign; non-finite
values never enter Double-to-Int conversion. Movement controls retain quiet
NaN payloads, and a multiply/subtract witness checks separate rounding.

`SimdDoubleVectorTest` checks source/artifact hashes, each declared row and every
available Core stage on both backends. Its compiled-entry expectations derive
from the retained guest-root structure. It installs active callees before the
caller and verifies the selected target, active target identities and last-tier
validity, plus released handoff storage, after each row. There are no
post-compilation settling calls or recompilation retries. Default and dense
handoff are separate JVM runs.

`--export-only` produces real pre-Tidy Core with model-only expectations, not
native or post-Tidy evidence. Full preparation requires a working pinned GHC
native SIMD configuration and fresh native rows matching the model. An imported,
source-matched oracle can support Graal checks on another host without claiming
that host ran native GHC SIMD. Local LLVM must satisfy GHC's supported version
range; a renamed executable does not establish toolchain compatibility.

Type acceptance and result agreement alone do not prove packed instructions,
eliminated allocations or a throughput improvement.
