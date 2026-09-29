# Word16X8 vectors

Both backends execute `VecRep 8 Word16ElemRep` with raw
`ShortVector.SPECIES_128` values. An activation carries one exact vector
reference; owned captures and boxed constructor fields store primitive lanes
inside the enclosing heap object. Equal bit width does not make another vector
shape compatible.

Supported operations include:
`packWord16X8#`, `unpackWord16X8#`, `broadcastWord16X8#`,
`plusWord16X8#`, `minusWord16X8#`, `timesWord16X8#`.
GHC provides no unsigned vector negation primitive.
For the complete operation inventory, see
[SIMD families](simd-families.md) and the [primop checklist](primops.md).

Pack takes one logical 8-component unboxed tuple; unpack returns that
scalar-lane tuple with exact `Word16Rep` leaves. Arithmetic wraps modulo
65,536, including low-16-bit multiplication and subtraction underflow.
Unpack zero-extends each lane to 0..65,535.
A high unsigned lane must not become a negative scalar.

Guest vector arguments/results, PAP prefixes, joins, tuple fields, nonrecursive
unlifted lets, owned captures and boxed constructor fields are supported.
Exact vector fields in supported sums and raw JDK vectors at the
[Core host boundary](site/embedding.md#load-a-core-entry) are also supported.
Recursive or lifted vector lets remain unsupported; see the
[SIMD transport contract](simd.md).

Canonical `word16` literals use 0..65,535. After lowering, integral
narrow annotations share an `Int` carrier and the literal tag supplies narrowing.
Machine-word and explicit 64-bit integers retain `Long` and are distinct carriers.
Wrong physical carriers, malformed literal values, lifted operand flags, tuple
lane proofs and vector shapes are checked separately. The strict exporter audit
also checks exact source-level representations; it is not the runtime's scalar
carrier contract.
