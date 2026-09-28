# SIMD representation and transport

THC represents supported vectors directly with fixed-species JDK
`ByteVector`, `ShortVector`, `IntVector`, `LongVector`, `FloatVector` or
`DoubleVector` values. Exact GHC `VecRep` metadata retains lane count, width
and signedness. Generic boundaries check the raw vector's species; signed and
unsigned families can share a physical lane class without sharing type proofs.

## Execution and storage

Both backends perform arithmetic, broadcast, insertion and pack construction
at the selected primop site. Arithmetic retains the Vector API result.
Explicit GHC pack/unpack converts between scalar tuple lanes and a vector;
a vector is not an equal-width scalar tuple. Integer unpack sign- or zero-extends
according to the lane proof, and arithmetic wraps at lane width. Floating
operations follow Java semantics, including NaN/signed-zero min/max and fused
operations that negate operands before the single rounding.

Activation locals, tuple leaves, arguments/results, PAP prefixes and joins
transport one vector reference per exact `VecRep`. Owned heap fields and
closure/thunk captures retain dense primitive lane properties, converting at
that durable storage boundary. ByteArray operations use native byte order.
See [generated families](simd-families.md) for admitted shapes and transport
limits; operation and memory support remain separate contracts.

The launcher enables `jdk.incubator.vector`. Direct Java launches need
`--add-modules=jdk.incubator.vector` too. The [Core host ABI](site/embedding.md#load-a-core-entry)
accepts and returns exact raw JDK vector values through polyglot interop;
host-object access is required for vector inputs. This transport does not
provide a native hardware vector calling convention.

## Correctness checks

The Int64X2 and Int32X4 foundations retain separate original-Core producers:

```sh
python3 scripts/prepare-simd-audit.py
python3 scripts/prepare-simd-audit.py --vector int32x4
```

Full preparation requires a working native GHC SIMD toolchain and records both
pre/post-Tidy exports and fresh native rows. `--export-only` instead produces
real pre-Tidy Core with explicitly model-only expectations and null
`nativeRows`; it does not establish native or post-Tidy coverage. Tests use the
stages and artifact hashes in provenance, not leftover files from another run.
The [floating](floatx4.md), [binary64](doublex2.md) and
[integer fixture](integer-simd-fixtures.md) guides describe their own models.

`CoreVectorCarrierTest`, `VectorLayoutTest`, `SimdAstTransportTest`,
`BytecodeVectorTransportTest` and `VectorHeapStorageTest` cover raw carriers,
exact layouts and owned transport. The genuine `SimdCallNativeTest` corpus
checks guest calling paths separately from local arithmetic fixtures.

## Code-generation evidence

Type acceptance and native-result agreement do not prove uninterrupted vector
SSA, register retention, allocation elimination or faster execution. Dynamic
arithmetic-chain and vector-loop graph checks must inspect both backends for
interior lane extraction/reinsertion, allocations and residual calls, separating
entry/exit pack/unpack from arithmetic transport. A target may use packed
instructions, wider-lane reconstruction or scalar fallback.

The existing graph driver accepts the shape explicitly, for example:

```sh
bench/experiments/simd-foundation/run-runtime.sh build/int32-graphs int32x4
```

Its selected-phase JSON and complete raw BGV/CFG captures are graph evidence,
not throughput measurements. Record the exact runtime, source, oracle and host
for a capture; a result on AVX2 does not establish an SSE2-only target, and an
imported x86 native oracle does not establish native GHC execution on AArch64.
