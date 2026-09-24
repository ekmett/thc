# Bounded Int32X4 ByteArray access

This work-in-progress slice implements six pinned GHC 9.14.1 operations. Existing
vector arithmetic and managed byte-array identity stay unchanged.

| Operations | Offset unit | Access width |
| --- | ---: | ---: |
| `indexInt32X4Array#`, `readInt32X4Array#`, `writeInt32X4Array#` | 16 bytes | 16 bytes |
| `indexInt32ArrayAsInt32X4#`, `readInt32ArrayAsInt32X4#`, `writeInt32ArrayAsInt32X4#` | 4 bytes | 16 bytes |

Index takes immutable ByteArray# and machine Int#, returning Int32X4#.
Read takes mutable ByteArray#, Int# and State#, returning (# State#, Int32X4# #).
Write takes mutable ByteArray#, Int#, Int32X4# and State#, returning State#.
Vector identity is exactly `VecRep 4 Int32ElemRep`, distinct from Word32. Memory is
native-order, ordinary non-atomic byte-array storage; scalar offsets 1/2/3 need
not be vector-aligned. All sixteen bytes must fit before any access or store.
The checked condition is `size >= 16 && index >= 0 && index <= (size - 16) / stride`,
before multiplying or narrowing the machine-width index. Invalid writes cannot
partially modify storage. Invalid-input native GHC behavior is not an oracle.

Mutable reads require a dedicated immediate primitive-case path: one exact
registered two-field tuple alternative, erased State binder and concrete local
vector binder. The original whole-tuple binder cannot be used or transported.
The producer and whole-binder aggregate annotations are validated at those two
structural sites only; shared or equal metadata elsewhere gains no exception.
Both pattern binders and the whole binder must be unlifted non-coercion values.
Generic tuple validation, result handoff storage and vector function/join/
capture/constructor boundaries are not broadened. Addr and foreign memory remain
unsupported. State is evaluated and checked before memory or local publication.

The pinned JDK 25 route uses `ByteVector.SPECIES_128` array access and IntVector bit
reinterpretation. Reinterpretation groups bytes little-endian, so native-order
conversion must reverse bytes within each int on a big-endian host. No temporary
segment wrapper or durable vector/payload array is needed. Packed load/store
survival, however, requires actual selected graph/LIR evidence; it is not implied
by using the Vector API or passing a semantic model.

The native fixture exercises all six operations, before/after alias snapshots,
all safe offsets in 64-byte storage, signed lane boundaries, and observation of
all 64 bytes after each write pattern. Opaque helpers exercise residual calls
using existing scalar/reference/State boundaries. A mutable array is never used
again after unsafeFreeze; selected store graph roots freeze as their last action
and return the same byte-array reference. Each graph invocation needs fresh
caller-owned storage.

## Corpus and execution gates

The independent model uses explicit little-endian bytes and integer reduction,
not runtime SIMD helpers. Its ten tests separately spell bit formulas, check
every declared lane boundary and offset, and observe every byte after stores.
Native evidence is limited to a 64-bit little-endian host. Native undefined
behavior on invalid offsets is never used to justify managed bounds behavior.

| Scalar entry families | Logical arity | Rows | Guest entries per call |
| --- | ---: | ---: | ---: |
| `vectorUnitCase` / `scalarUnitCase` | 2 | 72 / 234 | 2 |
| `vectorIndexCase` / `scalarIndexCase` | 5 | 36 each | 3 |
| `vectorReadCase` / `scalarReadCase` | 5 | 36 each | 3 |
| `vectorWriteCase` / `scalarWriteCase` | 6 | 2,304 each | 3 |
| `vectorStoreCase` / `scalarStoreCase` | 6 | 2,304 each | 3 |

Actual Core contains an outer scalar lambda and an immediately called `runRW#`
state lambda. The eight helper wrappers additionally call one retained OPAQUE
worker. Across pre/post Core, AST/bytecode and inlining on/off, strict validation
requires 77,328 compiled calls and 229,536 guest entries per handoff mode. It
checks active target identities, last-tier validity and exact entry counters
after every row, with no settling or retry calls. Ordinary read/write workers
return only the existing `(# State#, Int# #)` residual-call representation;
these tuple results remain rejected at the host entry boundary.

The four selected graph roots have one actual lambda each. Index roots take
an immutable array and offset, returning a weighted signed checksum. Store
roots take a mutable array, offset, four machine-Int lanes and State token
(logical arity 7), returning the frozen input array itself. Provenance supplies
36 cases per root with exact initial and expected bytes and native-row keys.
Packed-code evidence must distinguish the intentional caller-array access
from unwanted private vector payload arrays or materialized carriers.

`scripts/prepare-int32x4-bytearray-audit.py` generates fresh pre/post Core,
strict audits, native/model TSVs and source/artifact hashes. Each stage checks
14 positive roots, seven exact frontier negatives and six deliberately unsigned
metadata mutations. `--export-only` retains pre-Tidy/model evidence without a
native-execution claim, matching the existing AArch64 CI path.

## Development checkpoint

Current development evidence: the initial 306-row native probe and the expanded
9,666-row native/model preflight pass on pinned x86. The integrated runtime
compiles and its three direct storage tests pass. Adversarial loader tests,
strict per-row compiled-entry/identity checks, full regressions and selected
packed load/store graph evidence are still in progress. This is not yet a
compiled-memory or cross-platform performance claim.
