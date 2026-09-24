# Bounded Word32X4 ByteArray access

This slice adds the six unsigned counterparts to the local
[Int32X4 memory operations](int32x4-bytearray.md), using the existing four-int
Word32X4 carrier. It does not introduce a vector calling convention.

| Operations | Offset unit | Access width |
| --- | ---: | ---: |
| `indexWord32X4Array#`, `readWord32X4Array#`, `writeWord32X4Array#` | 16 bytes | 16 bytes |
| `indexWord32ArrayAsWord32X4#`, `readWord32ArrayAsWord32X4#`, `writeWord32ArrayAsWord32X4#` | 4 bytes | 16 bytes |

The pinned GHC 9.14.1 contracts are index `(ByteArray#, Int#) -> Word32X4#`,
read `(MutableByteArray# s, Int#, State# s) -> (# State# s, Word32X4# #)`,
and write `(MutableByteArray# s, Int#, Word32X4#, State# s) -> State# s`.
The exact proof is `VecRep 4 Word32ElemRep`; signed proofs are not interchangeable.
Four raw int fields preserve the bits and unpack zero-extends each lane to Long.
Scalar observations therefore distinguish `0x80000000` and `0xffffffff` from
their signed counterparts.

Memory is ordinary managed byte-array storage in native byte order, with no
atomic or foreign-memory semantics. The entire sixteen-byte access must fit.
Bounds are checked at full Long width before scaling or narrowing:
`size >= 16 && index >= 0 && index <= (size - 16) / stride`.
Invalid stores do not modify any bytes; an invalid range transfers to the
interpreter before constructing RuntimeFault. Native invalid-offset behavior is
not an oracle. Scalar-element offsets 1, 2 and 3 need not be vector-aligned.

Both backends erase State locally only after evaluating and checking the input
token. Mutable reads require one immediate, exactly checked tuple case, an
unused whole-tuple binder, and ordered State/vector pattern binders. The two
aggregate annotation exceptions are structural sites, not reusable tuple proofs.
Raw lane counts and tuple arity must be integer JSON values before normalization.
Vector function arguments/results, captures, joins and constructor fields stay
closed. There is no new general vector aggregate transport, Addr#, pinned-array,
fill or copy support in this slice.

The storage route is a 128-bit ByteVector array load/store reinterpreted as four
int lanes. Big-endian storage reverses bytes within each lane, since Vector API
reinterpretation groups them little-endian. A Vector API implementation alone
is not evidence that packed instructions survive compilation.

## Native fixtures and controls

The independent unsigned model explicitly assembles bytes and reduces integers;
it does not call the runtime SIMD helpers. The genuine GHC fixture covers all six
operations, all safe offsets in 64 bytes, aliases and loaded snapshots, high-bit
values and every byte after each store. No mutable access follows unsafeFreeze.
Native evidence is scoped to 64-bit little-endian x86. Big-endian conversion is
implemented but has not been executed here.

The scalar host roots use Int# seeds, then `int2Word#` and `wordToWord32#`.
Observation uses `word32ToWord#` followed by `word2Int#`; weighted checksums fit
in signed 64 bits. Opaque workers retain existing reference/scalar/State
boundaries. Read/write workers return only the existing State/scalar tuple,
which remains unsupported as a public host entry result.

The ten entry families contain 9,666 rows. Across pre/post Core, AST/bytecode
and inlining on/off, the strict native test requires 77,328 compiled calls and
229,536 guest entries per handoff configuration, with exact target identities
and counter deltas after every row. No retries or settling calls are allowed.
The main corpus uses ordinary automatic compilation triggers. Among the JVM
correctness tests, only the cold bounds-transition test suppresses automatic compilation to keep its host
bridge interpreted while checking deoptimization and recovery.

The four graph roots are vector/scalar index workers and vector/scalar store
workers, each with 36 cases and one genuine Core lambda. Every invocation uses
fresh caller storage; stores must return that same array and match all 64 bytes.
The graph gate requires exactly one live packed 128-bit caller-array access,
four zero-extensions from loaded 32-bit lanes to 64 bits, or four exact low-32-bit
store inputs. It rejects private carrier/vector/payload allocation and unexpected
calls. Existing narrowly checked interpreter/frame/bloom deoptimization metadata
exceptions do not permit live scalar payload arrays.
The graph-only harness separately suppresses automatic compilation to capture
exactly one explicitly requested guest target, following the signed harness.
It checks installed-target identity and validity, not compiled-entry counters;
those counters are required separately by the instrumented native tests. Neither
test setting increases a compiler graph budget or changes production policy.

`scripts/prepare-word32x4-bytearray-audit.py` prepares fresh native/pre/post
inputs, fourteen strict positive roots, seven exact frontier negatives and six
signed-proof mutations per stage. Export-only mode honestly records pre-Tidy
Core/model inputs without claiming native execution. Per-run provenance and
command/status logs are retained; earlier snapshots do not independently retain
every shared artifact overwritten by later preparation modes.

## Verified checkpoint

The isolated native preparation passed full, export-only and full modes on the
first attempt: 9,666 native/model rows agree, nineteen source and seven artifact
hashes verify. Fifteen unsigned proof tests and eleven byte-model tests pass
normally and under `-O`. The graph reader's thirty-five offline tests include
actual signed-load graphs rejected specifically for incorrect widening.
These reader controls are not unsigned compiler evidence.

Final runtime `340560523065c2091cd029695180c3341a408cc2` passes 515 tests in each
default/dense configuration, with 106 suites, identical method identities and
zero failures, errors or skips. Fourteen focused unsigned tests and three
targeted parser regressions pass as well. The shared canonical parser rejects
floating vector lane counts before normalization across all nine supported
families, while preserving genuine Int/Long counts and JSON roundtrips. Raw
exceptional read annotations remain checked at their structural sites.

The first direct-memory regression run exposed signed and unsigned indexing
accepting malformed `lanes=4.0` metadata. Both exact failures are preserved.
An initial scoped guard passed 515 tests in each mode; the final checkpoint
imports the parent's shared parser correction and removes that temporary guard.
Both full checkpoints, their commands, XML reports and native inputs retain
separate provenance. No valid GHC-generated Core semantics were changed by this
metadata correction.

All sixteen new pre/post × AST/bytecode × four-root graph captures pass on their
first check, with no reader correction or guest replay. They retain eight packed
loads with 32 exact unsigned extensions, eight packed stores with 32 exact lane
inputs, and sixteen physical XMM `VMOVDQU32` caller-array accesses. The 1,152
installed-target comparisons check fresh storage, every byte and identical store
results. The separate instrumented suites check 77,328 compiled calls and
229,536 guest entries per mode. No private vector/carrier/payload allocation
survives these selected compiled paths; public result boxes and caller arrays
remain intentional. This is not a throughput or globally allocation-free claim.

The graph campaign freezes and rehashes 75 runtime/harness sources, eleven
installed JARs and four JDK files. The nineteen-source native-input inventory
is narrower and does not include CoreVectors.kt; it is not represented as full
runtime provenance. All prepared Python checks pass normally and under `-O`
without skips: 65 auditor, 23 generic vector, 14 signed and 15 unsigned memory
proof, ten signed and eleven unsigned byte-model, nine byte-array, four coverage
and 35 reader tests.

The [compact x86 evidence](../bench/experiments/word32x4-bytearray/evidence-x86_64/README.md)
retains the original failures and both passing JVM checkpoints, current native
inputs and all selected graph/LIR records. Compression preserves original
hash-covered bytes. Signed `540fdb7` evidence remains frozen and is not relabeled
as unsigned coverage. Its separately recorded parser/test follow-ups retain their
own first compilation failure and corrected 14/15-test validations.
