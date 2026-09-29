# Float and machine-Word unboxed arrays

These operations support the corresponding `array` element types. `Float` storage is four bytes and has exact `FloatRep` payloads;
machine `Word` storage is eight bytes on the required 64-bit host and has
`WordRep`, not `Word64Rep`. Both use element indices and native byte order.

The selected primitive families are `readFloatArray#`, `writeFloatArray#`,
`indexFloatArray#`, `readWordArray#`, `writeWordArray#`, and `indexWordArray#`.
Reads return genuine `(# State# s, Float# #)` or `(# State# s, Word# #)`
tuples. Writes return `State# s`; immutable indexing returns the scalar.
Their value arities are respectively three, four, and two.

## Runtime storage and validation

Both AST and bytecode use the existing shared `byte[]` allocation and unsafe
freeze identity. Float accesses use a native-order four-byte `VarHandle` view
and primitive Float expressions, frame slots and bytecode locals. Word accesses
reuse the eight-byte raw-Long storage operations, preserving every bit.
GHC and the strict exporter audit retain exact `WordRep` type identity;
runtime lowering accepts machine/64-bit metadata aliases sharing the Long
carrier. Narrow Int-carried integers remain distinct.
Float remains a different carrier and cannot be replaced by an integer payload.

Each full-width element index is checked against the complete-element count
before narrowing or multiplication; partial trailing bytes are inaccessible.
Reads and writes evaluate and validate their State token before memory access,
and a failed read cannot publish a result.

See [array examples](../src/examples/)
for public array examples. Floating arithmetic and signaling-NaN identity follow
[the JVM floating-point contract](floating-primitives.md).
