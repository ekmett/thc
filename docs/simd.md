# Bounded Int64X2 and Int32X4 execution

The separate [FloatX4 foundation](floatx4.md) adds local floating pack/unpack,
broadcast and addition/subtraction/multiplication using a fixed-width
FloatVector carrier. The integer-carrier storage description below is unchanged.

Both backends execute local `Int64X2#` and `Int32X4#` pack, unpack, broadcast, addition,
subtraction and negation. Each operation requires exact GHC representation
metadata; `VecRep 2 Int64ElemRep` and `VecRep 4 Int32ElemRep` have vector identities
distinct from each other and from unboxed tuples. Pack consumes the ordinary
tuple writer into typed local slots, and unpack writes typed local slots. Neither operation allocates a
tuple or a payload array.

Int32X4 also supports [signed wrapping multiplication](int32x4-multiply.md):
the low 32 product bits are retained and unpack sign-extends them. The newer
slice has its own native/model corpus and strict compiled-code checks, separate
from the original add/subtract evidence below.

An interpreter/deoptimized Int64X2 value has two immutable primitive `long`
fields. Int32X4 has four immutable primitive `int` fields, also a 16-byte payload.
Int32 tuple lanes use the existing primitive `long` local slots: pack narrows to
32 bits, and unpack sign-extends each lane back to `long`. Arithmetic wraps at
the lane width and constructs transient JDK `LongVector` or `IntVector` values with fixed
128-bit species and immediately extracts the result into those primitive
fields. JDK vector objects and their payload arrays are never stored in guest
values. Partial evaluation can remove the temporary lane carriers and use
hardware SIMD. Type acceptance alone is not proof of this optimization; see the
fixed-input compiler inspection probes in `bench/experiments/simd-foundation`.
The application launcher enables `jdk.incubator.vector`; direct Java launches
that execute vectors must add `--add-modules=jdk.incubator.vector` too.

This first slice rejects vector function arguments/results, PAP prefixes,
closure captures, ordinary let bindings, join arguments/results, constructor
fields, vector leaves inside unboxed tuples, and other lane types/widths. Arrays,
loads/stores, shuffle, insertion, Int64X2 multiplication, quotient and remainder primops
remain unsupported. A vector-valued case/local expression is supported when GHC
retains it within one scalar root. GHC can turn a source-local expression into a
join with vector formals; the native `branchCase` fixture records that explicit
frontier.

The next ABI extension should flatten exact lanes into caller-owned typed input
slots and the existing callee-completion result storage, with vector identity
checked independently of physical layout. Constructor and capture layouts need
separate primitive lane fields as well. Public JVM `Object[] -> Object` calls do
not promise a hardware vector calling convention. This change does not pretend
to provide one.

## Validation and architectures

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
