# Compact Core container

This is the shared version 1 wire contract under implementation. The framing,
integer primitives and scoped assembly writer are the first implementation
slice; typed semantic records and runtime integration are not yet complete.
Existing JSON and unit-directory routes remain available and unchanged.

## Assembly and addressing

One module produces one mmap-able file. The producer constructs six streams:
executable data (preceded by the header), common strings, optional real names,
optional filename intervals, optional line/column intervals, and fingerprints.
The five auxiliary streams use scoped binary temporary handles. The producer
streams executable data, appends the five auxiliaries in that order, then writes
the fixed-size footer. It never patches the header or rebases payload references.
Owned temporary handles/files are cleaned on both success and failure; only the
completed container is published. These are construction streams, not a public
six-sidecar artifact API.

All record references are relative to their logical segment. Only the footer
contains absolute file positions. A string reference is its byte start and byte
length in the raw UTF-8 common-string segment, not a string ID. Strings may be
interned while writing. Debug names contain their own display text; filename
records may cold-reference common strings. No debug segment participates in
linking, loading executable records, or execution.

## Fixed framing

Fixed-width integers are little-endian. The 24-byte prefix is:

| Byte | Width | Value |
| ---: | ---: | --- |
| 0 | 8 | ASCII `THCCMP` followed by two zero bytes |
| 8 | 2 | major version, `1` |
| 10 | 2 | minor version, `0` |
| 12 | 4 | reserved, `0` |
| 16 | 8 | typed header-facts byte length |

Typed facts immediately follow the prefix. They carry module identity, compiler,
target layout and operative provenance; they are not a separate metadata file.
The executable-data segment starts at `24 + factsLength`.

The footer is exactly 128 bytes, located at `fileSize - 128`:

| Byte | Width | Value |
| ---: | ---: | --- |
| 0 | 8 | ASCII `THCCEND1` |
| 8 | 16 | executable data: absolute offset, byte length (two u64) |
| 24 | 16 | common strings: offset, length |
| 40 | 16 | real names: offset, length |
| 56 | 16 | filename intervals: offset, length |
| 72 | 16 | line/column intervals: offset, length |
| 88 | 16 | fingerprints: offset, length |
| 104 | 8 | top-level binding count |
| 112 | 4 | actual module-summary flags |
| 116 | 4 | debug-presence flags |
| 120 | 8 | reserved, `0` |

Summary bits 0 through 3 are, respectively, `containsDelimitedControl`,
`registrationObligations`, `mainAlias`, and `packageScalarDeclarations`, with
the same actual producer-derived meanings as the JSON unit manifest. Remaining
bits are zero. Debug bits 0 through 2 indicate nonempty names, filename intervals,
and line/column intervals respectively; remaining bits are zero.

The six segments are contiguous in the listed order. An empty segment starts at
the current cursor and has length zero. The fingerprint segment ends exactly at
the footer. Its length is `24 * bindingCount`, checked without overflowing.
Local extent checks use subtraction, not unchecked `offset + length` arithmetic.
Unknown versions, reserved bits, inconsistent flags/counts, overlaps, gaps and
out-of-file extents are rejected. These framing checks do not traverse executable
records, strings or debug data and do not imply a default whole-file hash pass.

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

The record-level byte grammar is the next implementation slice, not yet a
completed reader/writer contract. It uses explicit Core tags and typed fields,
not JSON-token serialization or an opaque JSON fallback. The expression families
are `var`, `prim`, `lit`, `lam`, `con`, `app`, `let`, `case`, and `void`.
Literal, alternative, binder, layout, constructor and foreign records have
distinct typed encodings. Unsupported variants must fail conversion rather than
silently discard operative fields.

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

## Shared controls

Manual byte vectors live in
[`test/compact-core/golden`](../test/compact-core/golden/integers-v1.json).
`header-v1.hex` and `footer-v1.hex` describe a framing-only 181-byte fixture with
zero header-facts bytes, two data bytes, three string bytes and one fingerprint.
They are not a valid typed Core module. The integer vectors cover canonical
thresholds and signed/unsigned endpoints. The native Haskell tests also reject
truncation, overlong integers, overflow, invalid versions/reserved fields and
inconsistent segment extents. Kotlin consumes the same byte contract independently.

Run the focused native checks with `cabal test compact-core-tests --offline
-fdevelopment --test-show-details=direct` using the repository's pinned GHC.
