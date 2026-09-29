# Native-endian Int16 and Word16 arrays

Both backends support `readInt16Array#`, `writeInt16Array#`,
`indexInt16Array#`, and their three `Word16Array#` counterparts. Typed indices
select two-byte elements; `Word8Array#` indices select individual bytes. The
exact payloads are `Int16Rep` and `Word16Rep`, not machine Int/Word or another
narrow width. Reads return genuine `(# State#, Int16# #)` or
`(# State#, Word16# #)` tuples, with no physical slot for the state token.
Writes return State; immutable indexing returns the narrow scalar. Values widen
with sign extension for Int16 and zero extension for Word16.

## Runtime and exact literal boundary

Both backends use a native-order primitive `short` VarHandle view of the same
managed `byte[]`, with no boxed or copied element array. Signed reads widen the
short to Long; unsigned reads zero-extend it; writes keep the low sixteen bits.
Full-width indices are checked against `byteLength/2` before narrowing/scaling,
so an odd trailing byte cannot be accessed as an element. State validation
precedes memory effects and result publication; unsafe freeze preserves identity.


Canonical signed `int16` syntax is accepted only in `[-32768,32767]`, including
literal alternatives. Both `int16` and `word16` forms
provide intrinsic exact representation proofs. The shared 16/32-bit proof
helper refines absent or genuinely unconstrained unknown/null metadata from
literal syntax. Known narrow integral metadata names may share the Int carrier;
the literal tag still determines range and signed interpretation. Incompatible
carriers and malformed records fail. Strict exporter auditing separately checks
GHC's exact type identities.

See [Unboxed16Arrays.hs](../src/examples/THC/Unboxed16Arrays.hs) for public
array examples. These operations use native byte order, not a portable wire
format. Use separate atomic primitives for concurrent updates.
