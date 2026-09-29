# Tuple arithmetic primitives

Both backends execute these saturated GHC 9.14.1 operations directly into two
primitive Long locals. Their exact signatures and result order follow the
[pinned GHC definitions](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/compiler/GHC/Builtin/primops.txt.pp).

| Primitive | Inputs and result fields | Meaning |
| --- | --- | --- |
| `quotRemInt#` | two `Int#`; `(# Int#, Int# #)` | quotient rounded toward zero, then remainder |
| `quotRemWord#` | two `Word#`; `(# Word#, Word# #)` | unsigned quotient, then remainder |
| `addIntC#` | two `Int#`; `(# Int#, Int# #)` | wrapped sum, then signed overflow flag |
| `subIntC#` | two `Int#`; `(# Int#, Int# #)` | wrapped difference, then signed overflow flag |
| `addWordC#` | two `Word#`; `(# Word#, Int# #)` | low wrapped sum word, then unsigned carry flag |
| `subWordC#` | two `Word#`; `(# Word#, Int# #)` | low wrapped difference word, then unsigned borrow flag |
| `plusWord2#` | two `Word#`; `(# Word#, Word# #)` | high carry word, then low sum word |
| `timesWord2#` | two `Word#`; `(# Word#, Word# #)` | high product word, then low product word |

Carry, borrow and signed overflow flags are zero when the mathematical result fits and one when
it does not; GHC specifies zero versus nonzero. Word subtraction reports borrow
when the unsigned left operand is smaller. Unlike `plusWord2#`, both WordC
operations return the low result first and an `IntRep` flag second. Words retain all 64 bits in the
existing Long carrier. Multiplication uses `Math.unsignedMultiplyHigh` for the
high word and ordinary wrapping multiplication for the low word.

The AST expression evaluates both operands once, computes both fields and writes
the destination slots. The bytecode operation has two primitive Long operands,
two constant local accessors and no stack result. Neither path constructs a
`DataValue`, tuple payload array or return slab for the saturated primitive.
Returning those fields through an ordinary function still uses the separate
[tuple result protocol](tuple-results.md); local primitive evaluation requires
no call boundary.

Load validation requires Long input carriers and a flat logical tuple of the
operation's result arity. The static auditor separately checks exact GHC register
names and order; runtime lowering trusts physically equivalent integral carriers.
A scalar result, same-width nested tuple, unknown leaf or missing proof is
rejected. Primops used as first-class values and partial or
oversaturated applications remain unsupported.

The [scalar integer completion](integer-completion.md) extends this protocol to
all six narrow quotient/remainder operations and the three-input double-word
unsigned division operation.

Division by zero and signed minimum divided by minus one report an
undefined-input fault; no numeric tuple result is published.
