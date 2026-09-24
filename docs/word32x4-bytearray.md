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
Native evidence is scoped to 64-bit little-endian x86; big-endian portability is
implemented and modeled, not established by a native run here.

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

## Validation status

The isolated native preparation passed full, export-only and full modes on the
first attempt: 9,666 native/model rows agree, nineteen source and seven artifact
hashes verify. Fifteen unsigned proof tests and eleven byte-model tests pass
normally and under `-O`. The graph reader's thirty-five offline tests include
actual signed-load graphs rejected specifically for incorrect widening.
These reader controls are not unsigned compiler evidence.

Integrated JVM validation and new unsigned packed graph captures are pending.
The signed `540fdb7` evidence remains frozen and is not relabeled as unsigned
coverage. Its later integer-proof/test-trigger follow-up has a separately
retained first compilation failure caused by the Context.Builder apply overload;
the explicit-builder source correction does not change runtime policy.
