# Int8X16 vectors

Both backends execute `VecRep 16 Int8ElemRep` with raw
`ByteVector.SPECIES_128` values. An activation carries one exact vector
reference; owned captures and boxed constructor fields store primitive lanes
inside the enclosing heap object. Equal bit width does not make another vector
shape compatible.

Supported operations include:
`packInt8X16#`, `unpackInt8X16#`, `broadcastInt8X16#`,
`plusInt8X16#`, `minusInt8X16#`, `negateInt8X16#`, `timesInt8X16#`.
For the complete operation inventory, see
[SIMD families](simd-families.md) and the [primop checklist](primops.md).

Pack takes one logical 16-component unboxed tuple; unpack returns that
scalar-lane tuple with exact `Int8Rep` leaves. Arithmetic wraps modulo
256, including low-8-bit multiplication and minimum-value negation.
Unpack sign-extends each lane to -128..127.

Guest vector arguments/results, PAP prefixes, joins, tuple fields, nonrecursive
unlifted lets, owned captures and boxed constructor fields are supported.
Exact vector fields in supported sums and raw JDK vectors at the
[Core host boundary](site/embedding.md#load-a-core-entry) are also supported.
Recursive or lifted vector lets remain unsupported; see the
[SIMD transport contract](simd.md).

Canonical `int8` literals use -128..127. After lowering, integral
narrow annotations share an `Int` carrier and the literal tag supplies narrowing.
Machine-word and explicit 64-bit integers retain `Long` and are distinct carriers.
Wrong physical carriers, malformed literal values, lifted operand flags, tuple
lane proofs and vector shapes are checked separately. The strict exporter audit
also checks exact source-level representations; it is not the runtime's scalar
carrier contract.
