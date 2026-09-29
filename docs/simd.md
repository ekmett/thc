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

## Runtime-shaped vectors from Haskell

`THC.Prim` also exposes the JDK Vector API through `Vec# e`, `VecMask# e`,
`VecShuffle# e` and `VecSpecies# e`. These unlifted values hold the JDK objects
directly. Choose the species at runtime, query its lane count, and construct
masks and shuffles from input data. `Int8#`, `Int16#`, `Int32#`, `Int64#`,
`Float#` and `Double#` select the corresponding Java lane type; `Int#` is
machine-sized and is not an alias for `Int32#`.

For example, `floatSpecies# 0#` selects the host's preferred Float species;
`floatSpecies# 128#` selects 128 bits. `speciesLength#` gives the loop stride,
`speciesLoopBound#` the end of the complete blocks, and
`speciesIndexInRange# species offset count` the mask for a partial block.
Masked loads zero inactive lanes; masked stores leave them untouched. Memory
offsets count elements in native-order byte arrays, and mutable access threads
`State#`. Arithmetic, comparisons, blending, shuffling, conversion and
reduction follow the JDK API's lane semantics and species compatibility rules.

[VectorLoops.hs](../src/examples/VectorLoops.hs) contains readable Haskell
implementations of array multiplication and negative sum of squares from the
JDK examples, plus runtime threshold masks with holes, block reversal and
species queries. Select width `0` to follow the CPU rather than hard-code a
lane count. Explicit widths remain usable when the JDK must scalarize them.
The examples run in compiled callers on both backends. Runtime-shaped operations
can still fall back to JDK support calls and allocate; in particular, masked
memory access with inactive lanes currently uses a runtime call. Prefer complete
blocks with one masked tail when that matches the algorithm.

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
