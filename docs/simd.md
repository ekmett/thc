# Bounded Int64X2 execution

Both backends execute local `Int64X2#` pack, unpack, broadcast, addition,
subtraction and negation. Each operation requires exact GHC representation
metadata; `VecRep 2 Int64ElemRep` has a vector identity distinct from a two-field
unboxed tuple. Pack consumes the ordinary tuple writer into two typed local
slots, and unpack writes two typed local slots. Neither operation allocates a
tuple or a payload array.

An interpreter/deoptimized vector value has two immutable primitive `long`
fields. Arithmetic constructs transient JDK `LongVector` values with fixed
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
loads/stores, shuffle, insertion, multiplication, quotient and remainder primops
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
oracle rows on x86. JVM tests run the 98 positive rows (two entry points) and
reject the 49-row vector-join frontier. They also run independent signed-long
wraparound formulas, compile each exact entry, execute it again and require that
its installed code remains valid. Metadata forgeries reject at load.

Pinned GHC 9.14.1's AArch64 native code generator requires LLVM for SIMD. The
macOS CI job explicitly uses `--export-only`: `-fno-code
-fwrite-if-simplified-core` runs the genuine pre-Tidy export pass successfully,
and tests execute those exports against independent formulas. Its provenance
has `nativeRows: null` and `stages: ["pre"]`. The Linux x86 CI job requires fresh
native rows and both export stages. No model-generated rows are described as a
native oracle. JVM tests select stages from hashed provenance, so stale files
left by an earlier run cannot silently expand the claim.

The separate Vector API mechanism probe has installed-code and final-register
checks on AArch64 ASIMD and x86 SSE2/AVX. This mechanism evidence is distinct from
the production Core graph controls: four on AArch64 using the source-matched
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

The [JDK 25 Vector API documentation](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.incubator.vector/jdk/incubator/vector/package-summary.html)
explains its compiler-dependent SIMD lowering and scalar fallback. The pinned
Graal implementation and its generated code determine which path THC actually
uses; language type names do not establish that claim.
