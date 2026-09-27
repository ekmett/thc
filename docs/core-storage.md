# Shared Core storage

`thc-interface --core-store FILE` writes an experimental binary representation
of THC's existing erased, post-Tidy Core. The exporter constructs immutable
shared records directly from GHC Core. It keeps executable expressions distinct
from auxiliary metadata and separates representation layouts from their outer
evaluated proofs.

This path is opt-in. Ordinary interface exports, project acquisition, package
caches, the semantic auditor and runtime loading continue to use their existing
JSON contracts. Producing or structurally verifying a store does not establish
that its foreign operations or primops are executable. Runtime and semantic
auditor integration are separate work.

## Export and inspect

Use the pinned compiler and a matching interface containing complete Core:

```sh
cabal run exe:thc-interface -- \
  --libdir /path/to/ghc/lib \
  --unit ACTUAL-INSTALLED-UNIT \
  --module Module.Name --interface /path/to/Module/Name.hi \
  --core-store module.thc-core
```

The successful response contains a `storage` descriptor with `format`, `schema`,
`path`, `bytes`, `indexBytes` and `indexSha256`. `format` is `thc-core-store` and
`schema` is 1. `indexBytes` in this descriptor includes the 64-byte header;
the binary header's own `indexBytes` field excludes that header. `indexSha256`
authenticates the exact header and index, including every block digest. It is
not a whole-file digest and does not change the meaning of any legacy `sha256`.

Explicit full verification and JSON inspection use the emitted index digest:

```sh
cabal run exe:thc-core-store -- verify module.thc-core \
  --index-sha256 INDEX-SHA256
cabal run exe:thc-core-store -- inspect module.thc-core module.json \
  --index-sha256 INDEX-SHA256
```

`verify` checks every block, record, typed structural edge and lexical scope,
including unreachable records. Its result says `structuralOnly: true`: it does
not replace the semantic Core audit or authorize execution. Without an external
`--index-sha256`, it checks internal consistency rather than authenticity of the
index itself.

`inspect` reconstructs the familiar expanded JSON grammar. The default budget
is ten million expanded record visits; `--budget N` changes that limit and
`--unlimited` removes it. `-` as the output path writes to stdout. The budget
counts visits, not output bytes. Inspection currently reads the complete store
and traverses the expansion before returning a Builder, so it is not a
bounded-memory streaming operation. Normal consumers must retain shared records
instead of calling this converter.

The exporter also accepts `--inspect-core-store JSONFILE` alongside
`--core-store`. This unbounded inspection option is for controlled comparisons
with the independent JSON exporter.

## Representation and access

Dense `RecordId`s identify immutable records. Structural child references always
point backward. Exact tags, scalars and ordered child IDs determine commoning;
strings and object keys participate. Field order, repeated vector elements,
absent fields and explicit nulls remain distinct. Display-type strings remain
display data; the format does not add erased GHC type applications or invent
nominal type identity from those strings.

`ScopeId` and `BinderId` are separate logical namespaces. Their indexes preserve
scope parents, exact binder names and declaration locations without introducing
scope/binder structural cycles. Incoming expression edges and binder dominance
are validated together. Source, binder and call evidence remains attached to
the appropriate occurrence. An interned immutable Core record does not authorize
sharing an executable Truffle node between occurrences.

Pages contain at most 256 records and a compact local offset directory. Strings
and long vectors occupy separately indexed payload blocks of at most 64 KiB.
The authenticated index has entries per block rather than a 64-bit offset per
record. A reader can open the header/index and authenticate only blocks needed
for a requested record. It must pin the source's identity and publish immutable
verified bytes; path replacement cannot redirect subsequent reads.

Lazy access checks local spans and required blocks. It must not scan earlier
records to establish a predecessor's payload end. Complete payload-partition
validation is an explicit full pass, performed before decoding potentially
overlapping payloads. The Haskell full decoder and scope validator provide that
pass; the `ValidatedStore` API exposes dense records and memoizable identities
without expanding JSON. Semantic admission still requires further checks.

The [wire specification](core-storage-format.md) defines exact bytes, namespaces
and validation obligations. The current writer uses uncompressed blocks and
buffers the payload before dividing it into blocks. Neither lower cache size
under equal compression nor lower runtime memory follows merely from structural
sharing; those comparisons require matched acquisition and load measurements.

## Development checks

```sh
cabal test core-store interface-json --test-show-details=direct
```

The Core store tests cover ordered inspection, representation proof separation,
shared diamonds, typed records, page and payload boundaries, integer encodings,
authentication failures, malformed references and lexical-scope violations.
Genuine interface comparisons should additionally export the same original Core
through both paths and compare all reconstructed JSON bytes, including original
identities, source and foreign metadata. No package default should change based
only on codec roundtrips.
