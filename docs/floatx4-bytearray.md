# Bounded FloatX4 ByteArray access

This page retains the original memory-operation checkpoint and its measurements.
Current execution uses raw `FloatVector.SPECIES_128` values, not a `FloatX4`
wrapper. Historical transport restrictions below are superseded by the
[current SIMD contract](simd.md); the old graphs do not certify the new runtime.

This slice adds exactly six local GHC9.14.1 operations, without a vector ABI:

| Operations | Index stride | Access width |
| --- | ---: | ---: |
| indexFloatX4Array#, readFloatX4Array#, writeFloatX4Array# | 16 bytes | 16 bytes |
| indexFloatArrayAsFloatX4#, readFloatArrayAsFloatX4#, writeFloatArrayAsFloatX4# | 4 bytes | 16 bytes |

Index accepts an immutable ByteArray# and Int#, returning the exact
`VecRep 4 FloatElemRep`. Read accepts a mutable byte array, Int# and State#,
returning `(# State#, FloatX4# #)` only to an immediate validated case. Write
accepts the mutable array, Int#, exact local vector and State#, returning State#.
There are two logical read fields, not State plus four unpacked scalar lanes.
Whole-tuple escape, vector function/join/capture/constructor transport and
foreign/Addr memory remain unsupported.

The closed family mapping keeps signed Int32, unsigned Word32 and Float32
identities distinct. Raw aggregate annotations are accepted only at the read
producer and whole-binder structural sites; exact integer lane/constructor
counts, unlifted/non-coercion binders, State order and lexical identities remain
required. Generic aggregate rules are unchanged.

Memory is native-endian ordinary non-atomic byte-array storage. Full Long
bounds are checked before scaling or narrowing:
`size >= 16 && index >= 0 && index <= (size - 16) / stride`.
Invalid stores leave all bytes unchanged; invalid ranges deoptimize before
exception allocation. State is checked before access or result publication.

The storage path preserves raw immutable FloatVector values,
using ByteVector128 transfers and reinterpretation, with per-int byte reversal
on big-endian hosts. It performs no numeric conversion, lane extraction/repack,
NaN canonicalization or floating arithmetic. Packed-code survival is verified
on the pinned little-endian x86-64 host; big-endian behavior is not tested here.

Raw-bit witnesses use existing Word32/Float scalar-array aliases, not additional
scalar bitcast primops. Exact portable assertions cover finite values, signed
zeros, subnormals, infinities and selected quiet-NaN payloads. Selected signaling
NaN movement is recorded separately on tested hosts: Java permits quieting when
copying floating scalars, so no cross-platform scalar sNaN bit guarantee or
arithmetic NaN-payload guarantee is made. Native invalid offsets are not an oracle.

Finite graph witnesses consume all four lanes separately from raw-bit semantic
tests. Graph acceptance must prove live packed caller-array memory operations
and reject private vector/carrier/payload allocations and unexpected calls;
public result boxes and exact deoptimization metadata remain distinguished.

## Fixture production

The four 128-bit byte-array families share the Haskell
`SimdByteArrayFixtures` producer and integer/byte model. Run
`cabal run exe:thc-fixtures -- floatx4-bytearray`; the independent Kotlin
`SimdByteArrayCorpus` controls run in the existing native test class.
The shared Python `audit-core.py` remains the exact Core proof mechanism;
the family-specific Python producer, model and test entry points are removed.
Fresh pre/post audits and retained historical Core mutation controls both run.
Receipts include closed source/artifact inventories and command exit records;
failed attempts and prior receipts are preserved, never resealed.

`--export-only` records only pre-Tidy/model evidence, with native fields
explicitly null. This remains the default ARM CI policy. Repeatable
`--ghc-option=OPTION` records and forwards explicit code-generation options
to exports and native builds (for example `--ghc-option=-fllvm`).
Availability of LLVM and native arithmetic evidence on a host does not by
itself establish this byte-array corpus or JVM/graph support.
The historical results below are not new migration performance measurements.

## Verified checkpoint

Runtime `4281921` passes 15 focused tests and the full 530 default + 530
dense-handoff tests (109 suites each, zero failures/errors/skips). Fresh native
and independent model output match all 6,720 portable rows. Each full mode
checks 53,760 compiled calls and 160,256 exact guest entries across pre/post
Core, AST/bytecode and inlining on/off, with no settling calls or retries.
All safe offsets and all output bytes are covered by coupled rotations, not
an exhaustive bit-pattern-by-offset product. The 640 native-only signaling-NaN
observations remain separate from the portable corpus.

Sixteen Float graph/LIR records prove one packed 128-bit caller-array access,
all four live Float32 load lanes or store inputs, and no live private vector
payload. The 1,024 installed-target comparisons check fresh arrays, every byte
and returned store-array identity. Allocated LIR contains eight XMM VMOVDQU32
loads and eight XMM VMOVUPS stores. Graph counters are disabled; instrumented
correctness tests provide the separate exact compiled-entry evidence.

The first campaign exposed real AST specialization failure from Kotlin's enum
switch-map array. Direct exact-family identity predicates fixed that shared
node without restoring an unsigned-versus-everything-else fallback. Fresh
Int32 and Word32 regression campaigns each pass 16 graph/LIR checks on the
same runtime. The original AST failure is preserved.

A separate reader-only correction recognizes exact Float-tagged frame
reconstruction from pinned compiler source, with strict ownership, kind,
snapshot and non-escape checks. Its original failed check is retained and
the offline recheck does not repeat guest execution or relax live-code gates.
Final Python checks pass 220 normally and 220 under `-O`, without skips.

The [compact evidence package](../bench/experiments/floatx4-bytearray/evidence-x86_64/README.md)
contains provenance, native observations, graph/LIR records, all failure
history and exact JUnit archives. No throughput, no-spill, globally
allocation-free execution, general vector ABI or non-x86 claim is made.
