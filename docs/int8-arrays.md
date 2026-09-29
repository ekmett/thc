# Signed and unsigned byte arrays

The signed byte family and mutable unsigned read are:

| Primitive | Value arguments | Result |
| --- | --- | --- |
| `readInt8Array#` | `MutableByteArray# s`, `Int#`, `State# s` | `(# State# s, Int8# #)` |
| `writeInt8Array#` | `MutableByteArray# s`, `Int#`, `Int8#`, `State# s` | `State# s` |
| `indexInt8Array#` | `ByteArray#`, `Int#` | `Int8#` |
| `readWord8Array#` | `MutableByteArray# s`, `Int#`, `State# s` | `(# State# s, Word8# #)` |

Existing `writeWord8Array#` and `indexWord8Array#` complete the unsigned family.
Both array references have exact `BoxedRep (Just Unlifted)` proofs. Signed and
unsigned payloads retain `Int8Rep` and `Word8Rep` in GHC and the strict exporter
audit. Runtime lowering accepts integral metadata aliases sharing the Long
carrier; the selected primitive determines width and signedness. State has no
physical result slot. Both loaders still check arity, argument flags, actual
carriers and recursive result shape.

The carrier remains the primitive JVM `byte[]`, with no wrapper or boxed
individual bytes. The AST uses fixed-child nodes; bytecode uses typed Long
operands and destinations. Signed reads widen the byte to `[-128,127]`, unsigned
reads to `[0,255]`; writes keep the low eight bits. Indices remain full-width
Long values until checked against the byte length. One-byte elements require
no endian conversion or alignment scaling. State is evaluated and validated
before access or result publication. Unsafe freeze preserves the array's
identity; mutating an immutable alias remains unsafe.

See [Unboxed8Arrays.hs](../t/fixtures/core/Unboxed8Arrays.hs) for public array
examples. Use atomic primitives for concurrent updates; ordinary reads and
writes do not provide that guarantee.
