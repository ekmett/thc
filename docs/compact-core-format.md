# Core Binary Distribution

Version 1.1 containers are the executable format for both runtime backends.
The compiler writes typed executable and header records directly, isolated metadata strings,
and optional name/source maps. The runtime decodes selected bindings on demand,
including all eight module-level foreign provenance families. Source locations
resolve when requested; original display names are available through explicit
lookup APIs. General runtime labels do not yet use the name map.
The project driver publishes these exact containers without an intermediate
Core JSON file. JSON manifests remain control metadata, never executable Core.

## Assembly and addressing

One module produces one ordinary ZIP archive named `.cbd`. Its seven members,
in physical and central-directory order, are `data`, `strings`, `names`, `filenames`,
`line-columns`, `symbols`, and finally `header`. All are present; absent debug maps
have zero length. Readers require this order and exactly one of each member.
The ZIP central directory owns physical placement and compression;
there is no separate THC offset footer or embedded whole-container member.

The producer constructs the six payload fragments through scoped binary
temporary handles, accumulating each fragment's size and CRC as it is emitted.
ZIP assembly copies or compresses bounded chunks. STORED members need no CRC
rescan; Deflate uses an additional scoped compressed temporary so actual sizes
are available in its local header. Standard ZIP64 fields carry sizes/offsets
that do not fit ordinary ZIP fields. Deterministic member order and timestamps
make identical inputs/options reproducible. Only the completed archive is
atomically published; owned temporaries are cleaned on success and failure.

Native linkage finalizes the header only after archive classification and all
link receipts are complete. In an exclusively owned mutable staging file, the
six preceding local records (including compressed bytes) stay in place, untouched.
Their central-directory records are carried verbatim at identical offsets.
Unbuffered, bounded file reads inspect ZIP framing and the old header only; the
replacement header and directory tail are completely prepared before seeking and
writing. Finalization never reads, inflates, hashes, repacks, recompresses or
reconstructs executable/debug payloads. Unchanged facts cause no writes. Publication
then copies/hashes the completed artifact into the immutable cache; finalization
must never target a published cache file. Capture retains the original
interface payload; intermediate archive classification stays in memory until
the final linkage stage.

All record references are relative to their uncompressed logical member.
A string reference is its byte start and byte
length in the raw UTF-8 common-string segment, not a string ID. Strings may be
interned while writing. Debug names contain their own display text; filename
records may cold-reference common strings. No debug segment participates in
linking, loading executable records, or execution.

## Fixed framing

Fixed-width integers are little-endian. The `header` member starts with 32 bytes:

| Byte | Width | Value |
| ---: | ---: | --- |
| 0 | 8 | ASCII `THCCBD1` followed by one zero byte |
| 8 | 2 | major version, `1` |
| 10 | 2 | minor version, `2` |
| 12 | 4 | actual module-summary flags |
| 16 | 8 | top-level binding count |
| 24 | 4 | debug-presence flags |
| 28 | 4 | reserved, `0` |

At byte 32 is a uint64 little-endian private string-pool byte length, followed
by that many raw UTF-8 bytes, then the unchanged typed facts grammar to the end
of the member. Facts carry module identity, compiler, target layout and operative
provenance. Every facts string span is relative to this private pool, not to
`strings`. Executable/debug strings remain relative to `strings`, and executable
origins to `data`. Identical spans can mean different text in the two pools;
metadata finalization cannot change executable string positions. Version 1.0
inputs require their pinned old reader, not a production compatibility fallback.

Summary bits 0 through 3 are, respectively, `containsDelimitedControl`,
`registrationObligations`, `mainAlias`, and `packageScalarDeclarations`, with
the same actual producer-derived meanings as the JSON unit manifest. Bit 4 is
`containsHostSignatures`, permitting the binding extension below. Remaining bits
are zero. Debug bits 0 through 2 indicate nonempty names, filename intervals,
and line/column intervals respectively; remaining bits are zero.

The uncompressed `symbols` length is `24 * bindingCount`, checked without
overflowing. Debug flags agree with the uncompressed member lengths. Local ZIP
extents use subtraction-based bounds checks. Unknown versions, reserved bits,
inconsistent flags/counts and invalid ZIP member extents are rejected without
a default whole-file hash or executable-record scan. Encryption and multi-disk
archives are unsupported.

## Integers and lookup

Record unsigned integers use canonical ULEB128 with at most ten bytes and all
64 bits representable. A tenth byte has payload at most one and cannot continue.
An extra zero terminal group is noncanonical. Signed 64-bit integers use zigzag
encoding followed by ULEB128. Segment-relative spans are two ULEB128 integers:
start, then byte length. Selected UTF-8 spans are decoded strictly on demand.

Each fingerprint record is 24 bytes: the 16 canonical MD5 bytes of the exact
logical UTF-8 qualified binding ID, then its executable-data-relative u64LE
offset. Records are sorted by unsigned digest bytes. There is no name table,
collision bucket, or search-time full-name comparison. JSON identifiers remain
available on the reference inspection route. Fingerprints are generated from
the actual UTF-8 bytes (`fingerprintData`, not `fingerprintString`, in GHC).

## Typed semantic records

The record-level byte grammar uses explicit Core tags and typed fields,
not JSON-token serialization or an opaque JSON fallback. The expression families
are `var`, `prim`, `lit`, `lam`, `con`, `app`, `let`, `case`, `void`, and explicit `unsupported`.
Literal, alternative, binder, layout, constructor and foreign records have
distinct typed encodings. Explicit unsupported diagnostic nodes retain their
metadata and text, so a dormant binding stays dormant and fails if requested.
Unknown variants or fields fail encoding rather than silently disappearing.

The records preserve missing/unknown/empty representation distinctions; ordered
nested tuple and sum layouts; vector species; coercion and void slots; declared
and retained arities; recursion and lexical identity; nominal enum/data-to-tag
families; literal raw bytes and signedness; and all foreign ownership, calling,
safety, provenance and inventory facts. Shape interning contains only exact
immutable structure. Evaluatedness at every recursive occurrence, levity,
WHNF/speculation facts, entry strictness and demand saturation remain separate.
SomeException markers and foreign-exception bridge identities are semantic data.

Entry types preserve the two operative facts currently recognized by execution:
`IO ()` and `State# RealWorld`. A typed discriminator may encode these as
`IO_UNIT` and `STATE_REALWORLD`, with `OTHER` never accidentally satisfying either
entry check. Arbitrary pretty type text and display names are not substitutes
for these facts. Loader-required target layout and foreign records belong in
typed header facts, without a body scan to discover them.

Debug names use exact logical identity/data-position lookup. Source intervals
use predecessor lookup only with explicit no-source and restoration boundaries,
so a location cannot bleed into unrelated nodes. The immutable container identity
and data-relative node offset survive runtime cloning and inlining. Debug maps
can be absent without changing executable semantics.

### Ordered executable grammar

The following record grammar is implemented by the typed executable codec.
`u` means ULEB64; `s` means zigzag64; `b` is one byte, exactly 0 or 1; `str` is a
common-string `(start,length)` span. `list(T)` is `u(count)` followed by that many
`T` records. `p(T)` is a one-byte discriminator: 0 missing, 1 null/unknown, or 2
followed by `T`. Missing, null and an empty known list are distinct. Enum tags are
one byte. Records concatenate fields in the exact order listed below. There are
no JSON property names, generic object tags or opaque JSON payloads.
Within array slots (`argumentLifted`, `fieldLifted`, `fieldReps`), discriminator
0 is invalid: an array element can be null or known, but cannot be absent.

`Identity` is tag 0 followed by a global `str`, or tag 1 followed by a local `u`
ordinal. Local ordinals are scoped to their enclosing top-level binding, not the
module. Every lexical declaration receives a distinct ordinal. Recursive lets
allocate every group member before encoding any RHS; a nonrecursive binder is
not in scope in its own RHS. Debug labels cannot change this resolution.

`EntryType` is 0 OTHER, 1 IO_UNIT, or 2 STATE_REALWORLD.
`IdInfo` is `p(u joinArity), p(b cbvEligible), p(list(b) cbvMarks)`.
`Binder` is `u ordinal, EntryType, p(b lifted), p(b coercion), p(Rep), p(IdInfo)`.
`Binding` is `Identity, EntryType, p(b lifted), u arity, p(Rep), p(IdInfo),
p(list(b) entryStrict), p(str entryStrictSource), p(u joinValueArity),
p(Rep joinResultRep), Expr`.
Top-level bindings use global identity; let bindings use local identity. The
fingerprint points to the binding's first byte, not the first expression byte.

An optional declared `hostSignature` uses prefix byte 2, `p(HostSignature)`,
then the unchanged `Binding` record. Presence 0 is invalid in this extension;
an absent field emits no prefix and preserves the original record bytes.
The extension requires header summary bit 4, so older readers reject it before
decoding records. `HostSignature` is `list(HostType) inputs, HostType result`;
`HostType` is `Rep, list(HostCarrier)`. Carrier tags are 0 (ordinary), 1 (`Object#`),
and 2 (`InteropLibrary#`), in logical scalar-leaf preorder including void tokens.
The nominal tags do not alter shared execution shapes. Host entry checks the
declared representation against the optimized worker before admitting raw Java
references; unannotated unlifted guest arrays retain their existing boundary.

`Expr` is a tag byte, then `Meta`, then the tag-specific payload:

| Tag | Expression | Payload after Meta |
| ---: | --- | --- |
| 0 | var | `Identity` |
| 1 | prim | `str exactPrimop` |
| 2 | lit | `Literal` |
| 3 | lam | `list(Binder), Expr body` |
| 4 | con | `str constructorId, u retainedArity` |
| 5 | app | `Expr function, list(Expr) arguments, list(p(b)) argumentLifted, b HNF, b speculatable` |
| 6 | let | `b recursive, list(Binding), Expr body` |
| 7 | case | `Expr scrutinee, u binderOrdinal, p(Binder), list(Alternative)` |
| 8 | void | empty |
| 9 | unsupported | `str diagnostic` |

`Meta` is `p(Rep), p(Rep resultRep), p(list(b) entryStrict),
p(str entryStrictSource), p(CallDemand), p(ForeignCall), p(ExceptionPayload),
p(EnumFamily), p(TagFamily), p(str unsafeEqualityCase)`. Case binder information
is stored in the case payload, not redundantly in Meta.
`CallDemand` is `u arity, list(b) strictArgs`.
`ExceptionPayload` is `u schema, str exactNominalType`.
`EnumFamily` is `str typeConstructor, list(str) constructors`.
`TagFamily` is `EnumFamily, u smallFamilyLimit, b smallFamily`.

`Alternative` begins with tag 0 DEFAULT (no discriminator payload), tag 1 DATA
(constructor `str`), or tag 2 LITERAL (`Literal`), followed by `list(Binder),
Expr body`. Its binder IDs are derived from those actual binders, not a second
independently interpreted ID list. Conversion requires original IDs and binder
metadata to agree; it does not fabricate missing proofs.

`ForeignCall` is `u schema, Target, Convention, Safety, u declaredArity,
u suppliedArity, list(Rep) argumentReps, Rep resultRep, p(str intrinsic),
p(str javascriptSource)`. `Target` tag 0 STATIC carries `str symbol, p(str unit),
b isFunction`; tag 1 DYNAMIC carries nothing. Convention tags 0..4 are ccall,
capi, stdcall, prim, javascript. Safety tags 0..2 are unsafe, safe, interruptible.
These fields do not confer provenance on a similarly named ordinary Haskell call.
Schema 1 ends at `javascriptSource`. Schema 2 appends
`p(list(p(str))) argumentTypes`, aligned with the declared argument list. It is
present only when GHC supplied a primitive `ByteArray#` or `MutableByteArray#`
argument: those entries retain that exact identity and all other entries are
null. Existing `argumentReps` still owns scalar widths; schema-1 bytes do not
change.

### Representation shapes and occurrences

`Rep` is `ShapeUse, EvaluationTree`. `ShapeUse` tag 0 is followed by an inline
`Shape`; tag 1 is followed by the data-relative `u` offset of an earlier tag-0
definition. That earlier shape is read directly on demand, never discovered by
a prefix scan. A producer interns by exact immutable `Shape` equality. A decoder
rejects a reference cycle or a reference that does not identify a definition.

`Shape` is `Kind, p(list(PrimRep)), p(Vector), p(Aggregate),
p(list(ShapeUse) components), p(list(ShapeUse) alternatives), p(u tagSlot),
p(list(list(u)) alternativeSlots)`.
Kind tags 0..9 are long, float, double, address, void, data, closure, object,
vector, unknown. Aggregate tags 0 and 1 are unboxed-tuple and unboxed-sum.
An unknown aggregate has a known Aggregate tag but null components/alternatives;
an empty tuple has a known empty component list. Neither is a State# token.

PrimRep tags 0..16 are IntRep, WordRep, Int8Rep, Int16Rep, Int32Rep, Int64Rep,
Word8Rep, Word16Rep, Word32Rep, Word64Rep, FloatRep, DoubleRep, AddrRep,
BoxedRep Nothing, BoxedRep (Just Lifted), BoxedRep (Just Unlifted), and VecRep.
VecRep carries a `Vector`; other tags have no payload. `Vector` is `u lanes,
Element`. Element tags 0..9 are Int8ElemRep, Int16ElemRep, Int32ElemRep,
Int64ElemRep, Word8ElemRep, Word16ElemRep, Word32ElemRep, Word64ElemRep,
FloatElemRep, DoubleElemRep.

`EvaluationTree` is `p(b evaluated)`, then the evaluation tree for every known
component shape, then for every known alternative shape, preserving their order.
Child counts come from the selected Shape, not another count in the occurrence.
Thus shape sharing cannot share or overwrite a child occurrence's evaluatedness.

### Literals and constructors

Literal tags and payloads are:

| Tag | Kind | Payload |
| ---: | --- | --- |
| 0 | int | `s` |
| 1 | word | `u` |
| 2..5 | int8, int16, int32, int64 | `s` |
| 6..9 | word8, word16, word32, word64 | `u` |
| 10 | bignat | `u byteLength`, unsigned little-endian magnitude bytes |
| 11 | char | `u codePoint` |
| 12 | string-bytes | `u byteLength`, raw bytes, including NUL/non-UTF8 |
| 13 | float | four IEEE-754 bits-as-bytes, little-endian |
| 14 | double | eight IEEE-754 bits-as-bytes, little-endian |
| 15 | null-addr | empty |
| 16 | rubbish | none; shape comes from the enclosing expression metadata |
| 17 | function-addr | `str exactSymbol` |
| 18 | data-addr | `str exactSymbol` |
| 19 | unsupported | `str diagnostic` |

BigNat zero uses zero magnitude bytes; a nonempty magnitude has a nonzero final
byte. Narrow literal payloads must fit their declared kind. Literal string bytes
are not common UTF-8 strings and are never decoded as text.

`Constructor` is `str id, u arity, u tag, ConstructorKind, list(b) strictFields,
list(p(b)) fieldLifted, list(p(list(PrimRep))) fieldReps, list(InlineRep)
fieldTypes, p(u sumArity), p(EnumFamily), p(TagFamily)`. ConstructorKind tags
0..3 are boxed, unboxed-tuple, unboxed-sum, newtype. Fields describe worker slots,
including void/coercion slots, not source-level field counts. `InlineRep` uses
the same shape and evaluation grammar, but recursively inlines shapes with no
ShapeUse tag or data references; header constructors therefore require no
executable-body access. Nonempty module-level foreign provenance families without
a typed payload schema are rejected by the converter, not discarded or embedded
as generic JSON.

### Header facts

The agreed header record order is `u originalSchema, str ghc, str unit,
str module, str boundary, p(list(str)) providedModules, p(TargetLayout),
list(Constructor), p(ForeignArtifacts), p(ForeignExceptionBridge),
p(str foreignExceptionBridgeUnit)`, then eight typed optional provenance fields:
`foreignLink`, `staticForeignImportStubs`, `staticForeignImports`,
`staticForeignExports`, `staticForeignExportRegistration`, `packageScalarLink`,
`packageNativeLink`, `packageNativeArchive`, in that order. Their typed payload
schemas are specified below; missing/null remain distinct in every slot. The
converter rejects unknown fields instead of omitting, guessing or hiding them
in JSON text. These records preserve original admission facts; their presence
does not replace existing ABI, ownership or native-access checks.

An optional trailing header extension starts with tag 1, followed by
`p(list(str)) roots, p(list(str)) sourceModules,
p(list(MissingDefinition)) missingDefinitions, list(BindingOrigin)`.
`MissingDefinition` is `str id, str type, str reason`; `BindingOrigin` is
`str id, p(str) origin, p(str) originModule`. This retains original interface
frontier and source-owner evidence without executable JSON. No extension bytes
are emitted when absent, preserving existing headers. Unknown tags reject.

`TargetLayout` carries `u documentSchema`, then four compiler strings (`id`,
`abi`, `platform`, `way`), then `u layoutSchema, b profiled, u wordBytes,
Endianness, str targetPlatform, b tablesNextToCode`. Endianness tags 0/1 mean
little/big. The document format is the fixed typed identity `thc-target-layout`.
Layout schema 1 has exactly 57 unframed ULEB64 fields, in this exact order:

```text
infoTableBytes infoTablePtrsOffset infoTablePtrsBytes
infoTableNptrsOffset infoTableNptrsBytes infoTableTypeOffset
infoTableTypeBytes infoTableSrtOffset infoTableSrtBytes
infoProvEntBytes infoProvBytes infoProvEntInfoOffset
infoProvEntProvOffset infoProvNameOffset infoProvDescOffset
infoProvDescBytes infoProvTyDescOffset infoProvLabelOffset
infoProvUnitOffset infoProvModuleOffset infoProvFileOffset
infoProvSpanOffset closureRetBco closureRetSmall
closureRetBig closureRetFun closureUpdateFrame
closureCatchFrame closureUnderflowFrame closureStopFrame
closureStack closureAtomicallyFrame closureCatchRetryFrame
closureCatchStmFrame closureAnnFrame stackHeaderBytes
stackCatchHandlerBytes stackCatchFrameBytes stackCatchStmCodeBytes
stackCatchStmHandlerBytes stackCatchStmFrameBytes stackUpdateeBytes
stackUpdateFrameBytes stackAtomicallyCodeBytes stackAtomicallyResultBytes
stackAtomicallyFrameBytes stackCatchRetryAltCodeBytes
stackCatchRetryFirstCodeBytes stackCatchRetryAltBytes
stackCatchRetryFrameBytes stackRetFunSizeBytes stackRetFunFunBytes
stackRetFunPayloadBytes stackRetFunFrameBytes stackAnnPayloadBytes
stackAnnFrameBytes stackClosurePayloadBytes
```

Layout schema 2 retains that exact prefix and appends six fields, for exactly
63 values:

```text
rtsFlagsBytes traceFlagsBytes rtsTraceFlagsOffset rtsTraceFlagsBytes
traceUserOffset traceUserBytes
```

The selected GHC headers supply `sizeof(RTS_FLAGS)`, `sizeof(TRACE_FLAGS)`,
`offsetof(RTS_FLAGS, TraceFlags)` and its member width, and
`offsetof(TRACE_FLAGS, user)` and its member width. The layout schema selects
the exact count; unknown schemas reject. Schema-1 bytes remain unchanged and
do not supply RTS flag offsets. The outer document and container schemas
are unchanged.

These are original target/compiler facts, never reconstructed from the producer
or reader host. Existing ABI checks still apply when admitting the selected module.

`ForeignArtifacts` is `u schema, str execution, p(Stubs), list(ForeignFile)`.
`Stubs` is `str header, str source, list(Label) initializers, list(Label)
finalizers`. `Label` is `b isInitializer, str unit, str module, str name`.
`ForeignFile` is `str language, str source, str extension`. C source and labels
remain semantic/provenance content, not optional display-name debug data.
`ForeignExceptionBridge` is `u schema, str unit, str module, str box, str project,
str payloadType, str exceptionType`; its unit reference remains semantic too.

### Retained import and export provenance

This ordered grammar is implemented by the typed-header codec. Header
slots `staticForeignImportStubs` and `staticForeignImports` use `ImportProof`;
`staticForeignExports` uses `Exports`; `staticForeignExportRegistration` uses
`Registration`. The surrounding `p(T)` presence bytes are unchanged.

`QualifiedName` is four semantic strings: `str unit, str module, str occurrence,
str namespace`. These names identify declarations and type constructors; they
are not optional display names. `ForeignType` has these one-byte tags:

| Tag | Type | Payload |
| ---: | --- | --- |
| 0 | tycon | `QualifiedName, list(ForeignType) arguments` |
| 1 | application | `ForeignType function, ForeignType argument` |
| 2 | function | `ForeignType multiplicity, ForeignType argument, ForeignType result` |
| 3 | bound-variable | `u index` |
| 4 | forall | `ForeignType binderKind, ForeignType body` |

Binder indices preserve their original scope. Declared and representationally
normalized types are separate operative provenance, not pretty text.

`ImportProof` is `u schema, str scope, str execution, str profile, str unit,
str module, ImportStatus`. Status tag 0 UNCLASSIFIED and tag 1 REJECTED each
carry a reason `str`. Tag 2 VERIFIED carries `u wordBits, ForeignArtifacts,
list(ImportAssociation), list(HeaderForeignCall) expectedCalls`.
`ImportAssociation` is `QualifiedName binder, p(str header), str symbol,
p(str unit), b isFunction, Convention, Safety, ForeignType declaredType,
ForeignType normalizedType, str normalizationRole, EmittedCall`.
`EmittedCall` is `str symbol, p(str unit), Convention, Safety, list(str)
arguments, list(str) result`; its exact ABI labels include the original void
slots. `HeaderForeignCall` has the existing ForeignCall order, except that
every argument/result representation uses `InlineRep`, never a DATA shape
reference. Complete expected-call order and multiplicity remain intact without
reading executable bodies during metadata admission.

Import proof schema 2 appends `list(AddressAssociation)` after the verified
status's `expectedCalls`; schema 1 is unchanged. `AddressAssociation` is
`QualifiedName binder, p(str header), str symbol, b isFunction, Convention,
ForeignType declaredType, ForeignType normalizedType, str normalizationRole,
b hasCallback`, followed, only when true, by `list(str) arguments, str result`.
This preserves stock GHC address declarations separately from calls. The
C-finalizer IO-unit callback profile requires the actual normalized `FunPtr
(Ptr a -> IO ())` or `FunPtr (Ptr env -> Ptr a -> IO ())` identity and a native
component with the same argument list; the address record alone does not grant
executable authority.

Import proof schema 3 retains the schema-2 address list and then appends
`list(WrapperAssociation)`. Each wrapper is the `ExportAssociation` layout
below, with its symbol field naming the emitted helper, followed by
`str typeString`. It preserves the declared callback ABI and the actual
`createAdjustor` helper/encoding association; it does not link GHC's RTS stub.

Import proof schema 4 retains both lists (either may be empty), then appends
`ForeignArtifacts importForeign`. This is the stock import-only product of a
module also declaring static exports; `expectedForeign` still records the whole
original product. Admission requires the matching verified export registration,
and the import partition cannot contain initialization/finalization obligations.
Schemas 1–3 retain their original encoding.

`Exports` is `u schema, str producer, str scope, str execution, str unit,
str module, list(ExportAssociation)`. `ExportAssociation` is `QualifiedName
binder, str symbol, Convention, ForeignType declaredType, ForeignType
normalizedType, str normalizationRole, list(ForeignType) arguments,
ForeignType result, Effect`; Effect tags 0/1 mean pure/io.

`Registration` is `u schema, str scope, str execution, str profile,
RegistrationStatus`. Status tags 0 UNCLASSIFIED and 1 REJECTED carry a reason
`str`; tag 2 VERIFIED carries `list(QualifiedName) roots, u wordBits,
ForeignArtifacts expectedForeign, Exports expectedExports`. Retained product
and export-inventory equality remain the existing admission rules; encoding
these records does not grant new foreign execution authority.

### Linked and archived native products

`blob` is `u byteLength` followed by those raw bytes. Original canonical
lowercase `bitcodeHex` is decoded to this blob, not UTF-8 or optional debug;
the inspector reconstructs its exact hex representation. Hash strings remain
the original producer evidence, never rewritten by conversion.

`foreignLink` uses `u schema, str format, str unit, str module, str sourceSha256,
str bitcodeSha256, blob, str target, list(str) symbols, list(SymbolKind) abi,
p(list(HeaderHash)) headerHashes`. `SymbolKind` is `str symbol, str kind`;
`HeaderHash` is `str name, str sha256`.

`LinkPayload` is `u schema, str format, str profile, str unit, str target,
str componentSha256, str bitcodeSha256, blob`.
`packageScalarLink` is `LinkPayload, list(ScalarABI)`; `ScalarABI` is
`str symbol, str entry, list(str) arguments, str result`.
`packageNativeLink` is `LinkPayload, list(NativeABI), p(NativeBuildInputs),
NativeExtras`. `NativeABI` is `str symbol, str entry,
Convention, Safety, list(str) arguments, str result`.
`NativeExtras` tag 0 is empty, preserving existing complete-link bytes. Tag 3
carries `p(NativeCompanion), p(list(str)) dataSymbols`; `NativeCompanion` is
`str sha256, blob`, retaining the exact host-library bytes without hex expansion
on the wire. Data symbols name address-returning ABI entries, not executable
function-call entries. Tags 1 and 2 belonged to the retired partial-entry
protocol and are rejected, never reinterpreted as native libraries.
Tag 4 has the tag-3 fields, then `list(str) exports, list(NativeComponent)
dependencies`. Both lists must be present together in inspection output.
`NativeComponent` is `LinkPayload, list(str) exports,
list(NativeComponent) dependencies, p(NativeCompanion)`. Its profile is
`thc-package-native-component-v1`, schema 1; it carries the actual complete
component bytes and dependency graph, not a fabricated Haskell ABI.
Native link schema 2 appends `list(str) finalizers` after `NativeExtras`;
these are existing namespaced ABI entry names, not original symbols or an
invented foreign-call inventory. Every entry must be a proved `ccall unsafe`
one- or two-pointer/void adapter with its matching original definition retained
by native linking. These argument lists use the existing ABI encoding. Schema 1 has no appended list and preserves its original bytes.

`NativeBuildInputs` is `list(CompileGroup) translationUnits,
list(NativeProvider), p(list(NativeDependency)), list(NativeLibrary),
list(str) unresolved, list(ArgumentBridge)`.
`CompileGroup` tag 0 carries one `CompileInput`; tag 1 carries
`list(CompileInput)`, preserving original wrapper groups versus individual C
translation units. `CompileInput` is `str compiler, str clang, list(str)
arguments, p(str language), str nativeTarget, str target, list(FileHash)`.
`FileHash` is `str path, str sha256`.
`NativeProvider` is `str provider, list(str) symbols, str bitcode,
str bitcodeSha256, str target, CompileInput`.
`NativeLibrary` is `str provider, list(str) symbols, str compiler,
str compilerSha256, list(str) arguments`.
The build-input presence tag 3 denotes extended inputs, retaining the same
layout except that each native-library record appends
`p(list(str)) dependencyArguments, p(str) objcopy, p(str) objcopySha256,
p(list(list(str))) objcopyArguments`. Tag 2 retains the original known-input
layout; tags 0/1 remain missing/null. The extension preserves the actual native
dependency-link and embedded-bitcode extraction recipe.
`ArgumentBridge` is `str profile, str source, str sourceSha256,
str inputBitcodeSha256, list(list(str)) definitions`.

`NativeDependency` is `str profile, str unit, SourceIdentity,
str registration, str registrationSha256, list(ArchiveProduct),
list(NativeProduct)`.
`SourceIdentity` is `p(str id), p(list(str)) depends, p(str type),
p(str style), p(str pkg-name), p(str pkg-version), p(list(Flag)) flags,
p(str component-name), p(str pkg-src-sha256), p(str pkg-cabal-sha256)`;
`Flag` is `str name, b enabled`, ordered by name with no duplicate names.
`ArchiveProduct` is `str path, str sha256, list(HeaderHash) members`.
`NativeProduct` is `NativePiece, str bitcodeSha256`; `NativePiece` is
`str root, str object, str objectSha256, str bitcode, str target, CompileInput`.
These preserve actual resolved C-only unit and archive membership evidence.

`packageNativeArchive` is `u schema, str profile, str execution, str unit,
str module, list(EmittedCall) unsupportedImports, p(str unclassifiedReason),
list(str) unresolvedSymbols, p(NativeLink) artifact,
p(list(EmittedCall)) conflictingImports, byte 0`.
The final byte reserves the former entry-resolution slot. Nonzero tags and the
retired JSON `entryResolution`/`availableEntries` fields are rejected. Conversion
does not promote unlinked archives to executable products or change recipes.

### Optional debug tables

The following table grammar is implemented by the optional-debug producer.
A nonempty debug segment ends with a 16-byte local directory: `u64LE indexStart,
u64LE rowCount`. Payload bytes precede the fixed-width rows; the rows end exactly
at that directory. Offsets in a debug table address that same debug segment,
except for explicitly identified DATA positions and common-string spans. There
is no auxiliary string-ID table. An absent debug segment has length zero and no
directory. Reading semantic header or executable records never reads these tables.

The name table has 32-byte rows: `u64LE topBindingDataOffset, u64LE ordinalSlot,
u64LE nameStart, u64LE nameLength`. Rows are sorted by the unsigned numeric key
pair, with no duplicate keys. Slot 0 names the top-level binding; local ordinal
`n` uses slot `n + 1`. Lookup is exact, not predecessor-based. Name payloads are
raw UTF-8 bytes in this segment, independent of the common-string segment.
The enclosing binding's immutable DATA offset scopes local ordinals. Original
names are display data; no loader or linker resolution depends on their presence.
The reserved first key `UINT64_MAX` names header constructors instead of DATA:
slot `constructorIndex + 1` selects the original constructor display name; slot
0 is unused. This reserved key cannot be a real DATA position. Readers compare
keys unsigned and expose constructor-name lookup separately from binding origins.

Each source table has 24-byte rows: `u64LE dataStart, u64LE dataEndExclusive,
u64LE payloadOffset`. Rows are ordered by DATA start and have nonempty,
nonoverlapping ranges. Lookup finds the predecessor start, then requires the
requested position to be below its end. A position outside every range has no
location. A payload starts with one byte: tag 0 is explicitly no source; tag 1
has the typed location payload below. Producers record entry, no-source and
restoration transitions; equal adjacent states can coalesce independently in
the two tables. An inherited source cannot bleed past its actual range.

Filename tag 1 is `list(File)`; `File` is `str originalFileId, str path,
p(str content)`. These are common UTF-8 spans, read only for an explicit source
request. Missing/null content keeps location-only source behavior. Line/column
tag 1 is `u primaryIndex, list(Note)`. `Note` is `text originalSpanId,
p(text label), u startLine, u startColumn, u endLine, u endColumn,
p(u charIndex), p(u charLength)`, where `text` means `u UTF8ByteLength` followed
by that many inline UTF-8 bytes in this source segment. All counts, indices,
coordinates and optional payload integers here use the canonical ULEB64 and
`p(T)` grammar above, not fixed-width integers. Coordinates are one-based and
end-exclusive; character offsets retain the original exporter values.

The filename and coordinate lists have equal count and corresponding order for
the selected location. They retain ordered, distinct effective source-note
provenance, including inherited notes; `primaryIndex` selects the debugger
location and must be within that list. The independently coalesced tables need
not have matching range boundaries. Readers check selected rows/payloads and
matching lists locally, without a debug-table prescan. Binder/expression origins
retain immutable container identity and DATA position through cloning/inlining.

## Direct production and explicit inspection

Build the native tool with `cabal build exe:thc-compact --offline -fdevelopment`.
Its input is CBD; JSON is inspection output only:

```sh
cabal run exe:thc-compact -- decode Module.cbd inspected.json
cabal run exe:thc-compact -- source Module.cbd 0
cabal run exe:thc-compact -- name Module.cbd 0 0
```

The compiler calls the existing typed encoder on its in-memory module value.
Normal encoding preserves supplied original names and source notes, recording
origins while semantic records are emitted. Source-note selection remains a
compiler option. Unknown semantic fields and malformed provenance fail encoding.
Header constructors, target-layout facts, foreign artifacts, native-link recipes
and exception-bridge facts are typed. Linked bitcode is stored as raw bytes and
reconstructed as canonical hexadecimal by the JSON inspector.
The test fixture executable has a separate modeled-input command; it is not a
runtime loader or a production JSON compatibility route.

Compression defaults to ZIP STORED for every member. The encoder library also
supports per-member Deflate levels 1 through 9. The archive records the methods,
so readers need no matching compression setting.

`decode` is an explicit full-module semantic inspection, separate from runtime
demand loading. It emits flat records with deterministic `@local/N` identities
and synthetic display names, not recovered original spellings. `source` and
`name` inspect original debug values separately at the requested exact DATA
origin/name key; they do not decode executable records. Native inspection
validates/inflates the archive explicitly; it is separate from runtime member
laziness. The semantic dump preserves only the
two operative entry-type facts, not arbitrary pretty types or printed IdInfo.
For lossless IEEE inspection it emits `float-bits` and `double-bits` literals with
unsigned decimal bit payloads. The typed encoder preserves signed zero and NaN
payload bits. There is no JVM JSON Core reader.

The manifest representation retains the original module `sha256`, name,
boundary and four actual summary booleans. Its `compact` field contains `path`,
the container `sha256`, and `format: "thc-cbd-v1"`. Original unit identity,
dependencies and any real canonical `targetLayout` remain in the unit descriptor.
Compact paths are absolute. Each nonempty unit selects compact containers for
all its modules. JSON/symbol pairs, legacy ZIP execution and JSON extents or
indexes are not runtime inputs.

The runtime shares immutable mappings while each Context owns its decoded
bindings and CAF state. Ordinary cold references do not open their modules;
constructor, foreign registration and exception-bridge requirements can demand
module metadata. Normal loads check framing and accessed records without a
whole-file hash or body scan. Explicit artifact verification hashes a fresh
mapping, retains that same mapping, and enumerates the selected module's bindings
for admission checks. It does not open unrelated modules.

STORED members are slices of the exact mapped snapshot. Deflated members inflate
once on demand into shared immutable memory, with exact length and CRC checked
during inflation. Inflation is per member; record decoding remains per binding.
Thus the first DATA access inflates all of `data`; metadata reads access only
`header`, including its private pool, without inflating `strings` or source text.
Diagnostics distinguish directory reads, member
inflations, inflated bytes, compressed bytes read, slab-cache hits and explicitly
verified STORED bytes from selected-record decode bytes. Idle inflated storage
has separate bounded caching; active member handles retain their snapshot or
slab independently of the archive owner.
