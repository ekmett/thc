# Native immutable strings

`THC.Prim` exposes `TruffleString#` and `TruffleStringEncoding#` as raw
unlifted references to Truffle's immutable string and encoding objects. No
Haskell heap wrapper or Java String conversion is inserted. Operations use
specialized cached TruffleString nodes at each AST/bytecode call site.

`truffleStringEncoding#` accepts these stable codes, not Java enum ordinals:

| Code | Encoding |
| --- | --- |
| 0 | UTF-8 |
| 1, 2 | Native-endian UTF-16, UTF-32 |
| 3, 4, 5 | ISO-8859-1, US-ASCII, BYTES |
| 6, 7 | UTF-16LE, UTF-16BE |
| 8, 9 | UTF-32LE, UTF-32BE |

Unknown codes fail. Except for the numeric parsers, the first argument selects
the expected encoding (the **target** encoding for `SwitchEncoding#`). Passing
a string incompatible with that encoding is an error. Compatible ASCII strings
may be shared across encodings by Truffle.

## Operations and units

All names below have the `truffleString` prefix and `#` suffix.

| Operations | Contract |
| --- | --- |
| FromByteArray, ToByteArray | Input byte offset/length; always copied. Output is a fresh ByteArray#. |
| FromCodePoint, FromInt64 | Unicode scalar or signed decimal integer creation. Unrepresentable scalars fail. |
| ByteLength, CodePointLength, IsValid | Encoded byte length, decoded length, validity (0/1). |
| ReadByte | Unsigned byte at a byte offset. |
| CodePointAt, CodePointAtByte | Code-point index or byte offset as named; malformed input returns -1. |
| CodePointByteLength | Byte offset; invalid code points return -1, incomplete terminal sequences return -1 minus the number of missing bytes. |
| ByteToCodePointIndex, CodePointToByteIndex | Byte offset plus an index relative to that offset; returned index is also relative. |
| Equal, CompareBytes, Hash | Encoding-aware equality (0/1), lexical encoded-byte comparison, Truffle hash. Not Unicode collation or normalization. |
| IndexOfCodePoint, IndexOfString | Code-point start/end range, end exclusive; -1 if absent. |
| ByteIndexOfCodePoint, ByteIndexOfString | The same search in byte offsets. |
| Substring, SubstringBytes | Start/length in code points or bytes, respectively. |
| Concat, Repeat | Immutable concatenation/repetition. No caller-visible mutable backing. |
| SwitchEncoding | Truffle's default transcoding policy: invalid/unrepresentable input is replaced with U+FFFD for Unicode destinations or '?' otherwise. |
| ParseInt64, ParseDouble | Native Truffle numeric parser; integer radix is explicit. Invalid numeric text and integer overflow become genuine THC.Exception ForeignException values, retaining the native NumberFormatException through host rethrow. |

Ranges must fit the upstream signed 32-bit indexing API; THC checks rather than
truncating its machine-width Int#. Invalid bounds and invalid UTF-16/32 byte
lengths fail. Byte creation can retain malformed sequences; check IsValid before
assuming valid Unicode. It does not silently sanitize bytes.

Byte-array creation never aliases caller-owned storage. Managed allocation input
uses the existing checked copy, including pointer-cell rejection and bounds.
The private snapshot can become native string storage because it is not exposed.
There is no public no-copy constructor, mutable-string API, or builder in this
immutable slice. Truffle may internally share immutable substring/encoding data.

## Interop

`isString#` and `asTruffleString#` take the existing `Object# s` and
`InteropLibrary# s`, and sequence through `State# s`. The supplied dispatcher
must accept the receiver. These call the corresponding InteropLibrary messages,
preserving foreign exception and context ownership behavior.

`truffleStringAsObject#` is an erased reference conversion for other raw interop
APIs; it allocates neither a wrapper nor a Java String. Host member-name APIs may
still require `InteropLibrary.asString`, independently of native string operations.

A language exporting `asTruffleString` can return its native immutable string
directly. THC does not route through `asString`; however Truffle's default
`asTruffleString` implementation itself falls back to `asString` when a receiver
does not implement the native message. Thus no claim is made that every
JavaScript/Python receiver avoids an upstream conversion.

See [StringPrimitives.hs](../src/examples/StringPrimitives.hs) for Unicode,
encoding roundtrips, slicing, numeric parsing and a code-point loop. The pinned
API has FromLong but no FromDouble formatting node; this API does not invent
a Java String formatter to fill that gap.

The ordinary string and suspended-operand checks use `thc-fixtures truffle-strings`.
Genuine Haskell numeric catch/rethrow also needs an authenticated installed Core
manifest, supplied by `THC_FOREIGN_EXCEPTION_INSTALLED` (or the existing
`build/foreign-exceptions/installed/packages.json`). Run those separately with
`./gradlew truffleStringExceptionTest truffleStringExceptionDenseTest`.
