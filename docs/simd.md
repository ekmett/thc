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

## Code-generation evidence

Type acceptance and native-result agreement do not prove uninterrupted vector
SSA, register retention, allocation elimination or faster execution. Dynamic
arithmetic-chain and vector-loop graph checks must inspect both backends for
interior lane extraction/reinsertion, allocations and residual calls, separating
entry/exit pack/unpack from arithmetic transport. A target may use packed
instructions, wider-lane reconstruction or scalar fallback.

Record the exact runtime, source, oracle and host
for a capture; a result on AVX2 does not establish an SSE2-only target, and an
imported x86 native oracle does not establish native GHC execution on AArch64.
