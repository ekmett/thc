# Managed Int arrays

`readIntArray#`, `writeIntArray#`, and `indexIntArray#` extend the existing
[managed ByteArray operations](bytearrays.md) on both execution backends.
They provide machine-Int storage for `UArray Int Int` and `STUArray s Int Int`.

The payload is still one primitive JVM `byte[]`: a native-endian `long` view
accesses each eight-byte machine Int on THC's 64-bit targets. Offsets count
**elements**, not bytes. Full-width bounds checks precede narrowing and byte
offset multiplication; incomplete trailing words are inaccessible. Writes
preserve every bit, and byte operations see the same backing storage. There is
no separate `Long[]` or `long[]`, wrapper, copy, or per-element box.

The contracts are exact, not inferred from a shared Java carrier:

| Operation | Arguments | Result |
| --- | --- | --- |
| `readIntArray#` | mutable array, `Int#` index, `State# s` | `(# State# s, Int# #)` |
| `writeIntArray#` | mutable array, `Int#` index, `Int#` value, `State# s` | `State# s` |
| `indexIntArray#` | immutable array, `Int#` index | `Int#` |

`Int64Rep` and `WordRep` cannot substitute for `IntRep`. The read tuple retains
two logical components but has one primitive Long destination; the State token
has no payload slot. State expressions are evaluated before memory access, and
failed reads do not publish a result. Plain accesses make no atomic or
concurrent-use guarantee.

See [UnboxedArrays.hs](../t/fixtures/core/UnboxedArrays.hs) for public array
examples. A complete program also needs its library error paths and native
imports; primitive support alone does not supply those dependencies.
