# SIMD representation and transport

The runtime represents each supported vector directly with a fixed-species JDK
`ByteVector`, `ShortVector`, `IntVector`, `LongVector`, `FloatVector`, or
`DoubleVector`. The exact GHC `VecRep` retains lane count, width and signedness
in node and layout metadata. Generic value boundaries check the raw vector's
species; signed and unsigned families share the same physical lane class.

Both backends perform arithmetic, broadcast, insertion and pack construction
at the selected primop site. Arithmetic retains the Vector API result rather
than extracting every lane into another carrier. Explicit GHC pack/unpack
converts between scalar tuple lanes and the vector. Integer unpack preserves
its signed or unsigned extension; arithmetic wraps at the lane width. Floating
operations follow Java semantics, including min/max NaN and signed-zero behavior
and fused operations that negate operands before the single rounding.

Activation locals, tuple leaves, arguments/results, PAP prefixes and joins
transport one vector reference per exact `VecRep`. Owned heap fields and captures
retain dense primitive lane fields, converting only at that durable storage
boundary. ByteArray operations preserve native byte order at their memory
boundary. The current supported families and frontiers are recorded in the
[capability contract](../scripts/core-capabilities.json) and
[generated-family guide](simd-families.md). The launcher enables
`jdk.incubator.vector`; direct Java launches need
`--add-modules=jdk.incubator.vector` too.

Raw vectors remove THC's nominal carrier wrapper and its repeated lane
reconstruction. This source change alone does not prove uninterrupted vector
SSA, register retention, or faster execution. The historical graph captures
below measured older carrier implementations. In particular, allocation
elimination did not prevent the AArch64 Int32X4 extraction/reinsertion described
below. A fresh dynamic arithmetic-chain and vector-loop graph check must inspect
both backends for interior cuts/reinserts, allocations and residual calls, with
entry/exit pack and unpack distinguished from arithmetic transport. Public JVM
`Object[] -> Object` calls still make no hardware vector calling-convention
promise.

## Historical validation and architectures

`prepare-simd-audit.py` produces genuine pre/post-Tidy Core and 147 GHC-native
Int64X2 oracle rows on x86. `--vector int32x4` prepares a separate
`build/simd-int32x4` corpus with 243 native rows: four independent scalar inputs,
including signed 32-bit boundaries and out-of-range 64-bit values. JVM tests run
the two positive entries for each shape and reject the vector-let/join frontier;
an isolated genuine join regression checks the formal boundary directly.
Independent lane-width wraparound formulas validate results, and every measured
postcompile input must increase the compiled-entry count by exactly one. Each
entry must remain installed after execution. Metadata forgeries reject at load.

Pinned GHC 9.14.1's AArch64 native code generator requires LLVM for SIMD. The
macOS CI job explicitly uses `--export-only`: `-fno-code
-fwrite-if-simplified-core` runs the genuine pre-Tidy export pass successfully,
and tests execute those exports against independent formulas. Its provenance
has `nativeRows: null` and `stages: ["pre"]`. The Linux x86 CI job requires fresh
native rows and both export stages. No model-generated rows are described as a
native oracle. JVM tests select stages from hashed provenance, so stale files
left by an earlier run cannot silently expand the claim.

The existing Int64 Vector API mechanism probe has installed-code and final-register
checks on AArch64 ASIMD and x86 SSE2/AVX. This mechanism evidence is distinct from
the Int64 production Core graph controls: four on AArch64 using the source-matched
x86 native oracle, and [eight on x86](../bench/experiments/simd-foundation/evidence-x86_64/runtime/README.md)
using fresh pre/post-Tidy exports and native rows. Both backends emit packed
ADD/SUB with no vector carrier allocation, field traffic or residual calls in
the inspected graphs. The x86 output uses `VPADDQ`/`VPSUBQ` on an AVX2 host;
it does not establish behavior on an SSE2-only machine.

Graph captures retain installed-target validity after execution; instrumentation
is disabled in those captures, so this is separate from per-call compiled-entry
measurement. Each record identifies its tested source revision and runtime.
Compact headers are enabled; the outer scalar `Long` box needed by the public
`Object` result is accounted for separately.

The production harness accepts a second `int32x4` argument, for example
`bench/experiments/simd-foundation/run-runtime.sh build/int32-graphs int32x4`.
It checks native rows, installed-target validity, packed 32-bit ADD/SUB
instructions, and absence of carrier/array allocations and field traffic.
Per-row compiled-entry assertions belong to the separate instrumented JVM tests.
An accepted type or the earlier Int64 mechanism probe alone does not establish
these Int32 production properties.

The [four AArch64 Int32X4 production controls](../bench/experiments/simd-foundation/runtime-int32x4-aarch64/README.md)
match the source-verified x86 native rows and show packed `V128_DWORD` ADD/SUB
without surviving carrier allocation, field traffic, or residual calls. Lane
extraction/reinsertion still appears between some operations. Their runtime
revision and the separate local pre-Core/native-x86 origins are recorded.
The [eight x86 Int32X4 controls](../bench/experiments/simd-foundation/evidence-int32x4-x86_64/runtime/README.md)
cover both export stages and both backends, with final `VPADDD`/`VPSUBD` on an
AVX2 host. Their separate instrumented tests require compiled entry for every
native input. These are code-generation checks, not a throughput comparison.

The [JDK 25 Vector API documentation](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.incubator.vector/jdk/incubator/vector/package-summary.html)
explains its compiler-dependent SIMD lowering and scalar fallback. The pinned
Graal implementation and its generated code determine which path THC actually
uses; language type names do not establish that claim.

The graph harness expands only the checked `Before phase HighTierLowering`
snapshot to JSON. Its index still summarizes every compiler phase, and the raw
BGV/CFG files retain the full graphs for further inspection.
