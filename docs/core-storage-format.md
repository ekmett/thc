# THC Core store v1 — byte contract

This document specifies the experimental v1 encoder/reader contract. No existing JSON
format, manifest hash, or production default changes meaning. All integers below
are little endian unless marked ULEB/SLEB. IDs are zero based. Reserved fields
and flags must be zero; unknown tags/versions fail explicitly.

## Container

The 64-byte header is `THCCORE\0`, u16 major=1, u16 flags=0,
u32 headerBytes=64, u64 totalFileBytes, u64 indexOffset=64, u64 indexBytes,
u32 recordCount, u32 scopeCount, u32 binderCount, u32 nodePageCount,
u32 payloadPageCount, u32 reserved=0.

The index starts with u32 rootRecordId, u32 symbolCount, then:

* scopeCount rows: u32 parentScopeId; UINT32_MAX means the unique module root.
  Each other parent is strictly earlier. Scope IDs are logical identities.
* binderCount rows: u32 ownerScopeId, u32 nameStringRecordId,
  u32 declarationRecordId. Binder IDs are logical identities. Each declaration
  is exactly one tag33 record; duplicate original binder names in one scope fail.
* symbolCount rows: u32 nameStringRecordId, u32 bindingRecordId, in original
  module binding order. Symbols are top-level declarations only; unique names.
* nodePageCount + payloadPageCount block rows: u64 absolute offset,
  u32 byteLength, u32 reserved=0, SHA256[32]. Node blocks precede payload blocks.

Data blocks immediately follow the index, in directory order, with no gaps or
overlap. SHA256(header || index), with its exact byte length and totalFileBytes,
is authenticated by a NEW manifest module record. The legacy sha256 continues
to mean the legacy whole-file digest. Initial encoding has no compression.

Node page i stores IDs [256*i,256*i+count). Only the final page may be partial.
A page is u16 count, count+1 u16 offsets relative to the record payload, then
payload. First offset0; offsets strictly increase; terminal equals payload
length; whole page <=65536 bytes. Record encodings are bounded (<128 bytes
except inline vectors, which are <=16 IDs), so256 records always fit.

Payload is a logical concatenation of <=65536-byte blocks. Only the final block
may be partial. Strings, large integers and long vectors use (u64 byteOffset,
u64 byteLength) spans. Spans may cross blocks: demand bounded verified blocks,
never allocate a giant verification copy. UTF8 is strict and incremental across
blocks. Nonempty spans partition the payload in physical record order: each
starts at the previous span's end, and the final end equals payload length.
Zero-length spans name the current end. Sharing uses RecordId, not overlapping
byte aliases. This lets full validation charge UTF8/integer/vector bytes once,
even for hostile inputs. All additions/multiplications are checked.

Partition validation is an EXPLICIT full-audit pass. Lazy demand checks only its
local span bounds, needed authenticated block hashes and record contents; it
must not decode preceding records to discover their payload ends. Such a load
does not claim whole-file audit completion. The format never hides a sequential
scan behind one on-demand record lookup.

## Record encodings

Every record begins with one byte tag. R means canonical ULEB32 physical
RecordId, strictly less than the containing record ID. S and B mean canonical
ULEB32 logical ScopeId/BinderId, checked against the indexed namespaces; they
are NOT structural edges. N means canonical ULEB32 ordinary count/position.
U means one byte boolean0/1. No trailing bytes are allowed.

| Tag | Meaning | Fields after tag |
|---:|---|---|
|0|null|none|
|1|false|none|
|2|true|none|
|3|signed integer in Int64 range|canonical SLEB64|
|4|string|u64 UTF8 byteOffset, u64 byteLength|
|5|ordered record vector|N count, mode byte; mode0 (count<=16): count R; mode1 (count>16): u64 payloadOffset, then count little-endian u32 RecordIds in payload|
|6|ordered object|R keysVector, R valuesVector (same length; keys strings)|
|7|integer outside Int64 range|sign byte0 positive/1 negative, u64 payloadOffset, u64 magnitudeByteLength; unsigned big-endian magnitude, no leading zero|
|8|canonical representation layout|R orderedFieldsObject, excluding outer evaluated field only|
|9|representation use|R layout, proof byte0 absent/1 false/2 true, N insertionPosition (0 when absent)|
|16|variable|S useScope, R metadata, discriminant byte0 global/1 local, R globalName OR B localBinder|
|17|primop|S useScope, R metadata, R exactName|
|18|constructor expression|S useScope, R metadata, R exactName, N arity|
|19|literal expression|S useScope, R metadata, R kindString, R payloadString|
|20|application|S useScope, R metadata, R function, R argumentsVector, R argumentLiftedVector, U HNF, U speculation|
|21|lambda|S useScope, R metadata, S bodyScope, R binderRefs, R body|
|22|let|S useScope, R metadata, U recursive, S bodyScope, R bindingsVector, R body|
|23|case|S useScope, R metadata, S branchScope, B caseBinder, R scrutinee, R alternativesVector|
|24|void expression|S useScope, R metadata|
|25|unsupported type-as-value|S useScope, R reasonString|
|32|binding definition|B binder, R expression, R orderedFieldsObject (without expr), N exprInsertionPosition|
|33|binder declaration|B binder, R exact legacy binder metadata object|
|34|alternative|S bodyScope, kind byte0 default/1 data/2 literal, R discriminator (null/string/[kind,payload]), R binderRefs, R body, R metadata|
|35|ordered logical binder vector|N count, mode byte; mode0 count<=16: count B; mode1 count>16: u64 payloadOffset then count little-endian u32 BinderIds|

All variable-length integer encodings are shortest form. Tags3/7 are disjoint;
zero and Int64-range numbers cannot use tag7. Exact equality of tag and fields,
including ordered children, drives collision-safe interning. Strings and object
keys participate. Display-type strings are not interpreted as nominal types.

## Meaning, scopes, and direct construction

Core opcodes correspond to the EXISTING erased grammar. Metadata retains every
current field in current order; absent, null, and empty differ. Tag9 separates
outer evaluated evidence from layout; nested component proofs remain intact.
Occurrence metadata contains representation USE and resolved source inheritance.
No Truffle executable node is shared merely because its immutable record is.

There is exactly one module scope0. Lambda creates bodyScope with parent=useScope;
binders belong there. Let creates bodyScope with parent=useScope; declarations
belong there; nonrecursive RHS remains useScope, recursive RHS is bodyScope.
Case scrutinee is useScope; case binder belongs to branchScope (parent=useScope);
each alternative has its own bodyScope (parent=branchScope) and own declarations.
Top-level declarations belong to scope0. All local uses require owner dominance.
Incoming expression edges must match these transitions: a forged useScope label
cannot grant access. All recursive declarations precede all RHS structural
records. Every non-declaration record containing a BinderId must occur after
that binder's indexed declaration record; the declaration's own BinderId is
its sole self-reference. This also prevents logical-reference inspection cycles
through binder metadata. The header index can name later declaration positions;
that does not permit forward binder USES or relax backward R checks.

Full verification visits each unique record and stored edge once, checks typed
edge obligations and computes scope DFS intervals once from verified topology.
Dense IDs support deterministic indexing; do not copy ancestor/free-variable
sets or full witness paths per node. Producer interning may use an ordered map;
that is not a worst-case-linear producer claim. Normal consumers retain IDs and
memoized decoded facts, never expand the DAG into JSON. Inspection alone expands
to familiar JSON with an explicit overrideable expanded-record-visit budget.

The source channel owns backing identity. Demand verifies bounded immutable
copies; close rejects new reads while already owned immutable decoded records
remain valid. Concurrent demand publishes one result/failure per demanded block
or node, allocating cache entries only on demand. An unused corrupt block may
remain unread during lazy load, but full audit must reject it.

Goldens cover all tags, >256 records, large spans, lexical transitions, integer
canonicality and malformed references; actual producer conversion must also
equal legacy JSON for genuine exported Core before default migration.
