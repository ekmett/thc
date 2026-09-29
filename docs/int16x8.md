# Int16X8 vectors

Both backends execute `VecRep 8 Int16ElemRep` with raw
`ShortVector.SPECIES_128` values. An activation carries one exact vector
reference; owned captures and boxed constructor fields store primitive lanes
inside the enclosing heap object. Equal bit width does not make another vector
shape compatible.

Supported operations include:
`packInt16X8#`, `unpackInt16X8#`, `broadcastInt16X8#`,
`plusInt16X8#`, `minusInt16X8#`, `negateInt16X8#`, `timesInt16X8#`.
For the complete operation inventory, see
[SIMD families](simd-families.md) and the [primop checklist](primops.md).

Pack takes one logical 8-component unboxed tuple; unpack returns that
scalar-lane tuple with exact `Int16Rep` leaves. Arithmetic wraps modulo
65,536, including low-16-bit multiplication and minimum-value negation.
Unpack sign-extends each lane to -32,768..32,767.

Guest vector arguments/results, PAP prefixes, joins, tuple fields, nonrecursive
unlifted lets, owned captures and boxed constructor fields are supported.
Exact vector fields in supported sums and raw JDK vectors at the
[Core host boundary](site/embedding.md#load-a-core-entry) are also supported.
Recursive or lifted vector lets remain unsupported; see the
[SIMD transport contract](simd.md).

Canonical `int16` literals use -32,768..32,767. After lowering, integral
narrow annotations share an `Int` carrier and the literal tag supplies narrowing.
Machine-word and explicit 64-bit integers retain `Long` and are distinct carriers.
Wrong physical carriers, malformed literal values, lifted operand flags, tuple
lane proofs and vector shapes are checked separately. The strict exporter audit
also checks exact source-level representations; it is not the runtime's scalar
carrier contract.
