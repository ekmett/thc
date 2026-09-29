# Scalar floating primitives

THC supports a bounded scalar `Float#`/`Double#` foundation in both the AST and
bytecode backends. It includes floating literals, primitive locals, constructor
fields and closure captures, scalar arguments/results, and 87 primops:

| Family | Float# | Double# |
| --- | --- | --- |
| Arithmetic | `plusFloat#`, `minusFloat#`, `timesFloat#`, `divideFloat#`, `negateFloat#` | `+##`, `-##`, `*##`, `/##`, `negateDouble#` |
| Fused multiply/add | `fmaddFloat#`, `fmsubFloat#`, `fnmaddFloat#`, `fnmsubFloat#` | `fmaddDouble#`, `fmsubDouble#`, `fnmaddDouble#`, `fnmsubDouble#` |
| Square root | `sqrtFloat#` | `sqrtDouble#` |
| Scalar math | `fabsFloat#`, `expFloat#`, `expm1Float#`, `logFloat#`, `log1pFloat#`, `sinFloat#`, `cosFloat#`, `powerFloat#` | `fabsDouble#`, `expDouble#`, `expm1Double#`, `logDouble#`, `log1pDouble#`, `sinDouble#`, `cosDouble#`, `**##` |
| Trigonometric and hyperbolic | `tanFloat#`, `asinFloat#`, `acosFloat#`, `atanFloat#`, `sinhFloat#`, `coshFloat#`, `tanhFloat#` | `tanDouble#`, `asinDouble#`, `acosDouble#`, `atanDouble#`, `sinhDouble#`, `coshDouble#`, `tanhDouble#` |
| Inverse hyperbolic | `asinhFloat#`, `acoshFloat#`, `atanhFloat#` | `asinhDouble#`, `acoshDouble#`, `atanhDouble#` |
| Operand extrema | `minFloat#`, `maxFloat#` | `minDouble#`, `maxDouble#` |
| Comparisons | `eqFloat#`, `neFloat#`, `ltFloat#`, `leFloat#`, `gtFloat#`, `geFloat#` | `==##`, `/=##`, `<##`, `<=##`, `>##`, `>=##` |
| Int conversion | `int2Float#`, `float2Int#` | `int2Double#`, `double2Int#` |
| Unsigned Word conversion | `word2Float#` | `word2Double#` |
| Precision conversion | `double2Float#` | `float2Double#` |
| Raw bit casts | `castFloatToWord32#`, `castWord32ToFloat#` | `castDoubleToWord64#`, `castWord64ToDouble#` |
| Integer decomposition | `decodeFloat_Int#` | `decodeDouble_Int64#`, `decodeDouble_2Int#` |

The exporter retains `FloatRep` and `DoubleRep` as distinct scalar proofs.
AST execution has `executeFloat`/`executeDouble` paths; frames and StaticShape
fields/captures store JVM `float`/`double` directly. Bytecode operations use those
same concrete types with Bytecode DSL boxing elimination enabled for each.
There is no implicit widening between the two types. Every floating operation
rounds to its declared precision; comparisons use IEEE arithmetic equality and
ordering, including unordered NaNs and equal positive/negative zeros.

## Numeric behavior

Arithmetic rounds to the declared precision. Comparisons use IEEE equality and
ordering, including unordered NaNs and equal positive/negative zeros. Scalar math
uses JVM `Math`; results need not be bit-identical to a platform's native `libm`.
Float results round to binary32. Arithmetic does not promise NaN payload or sign
preservation. Floating-to-integer conversion has no portable result for non-finite
or out-of-range inputs.

The fused variants compute `x*y+z`, `x*y-z`, `-x*y+z` and `-x*y-z` with one
nearest-even rounding through `Math.fma`. Float never uses a Double intermediate
for these operations. Square root uses `Math.sqrt`, preserving signed zero;
Float operands widen exactly before narrowing the root.

Unsigned `word2Float#` and `word2Double#` accept all 64 Word bits and round to
nearest, ties to even. The Float conversion avoids a Double intermediate that
could double-round integer inputs. `maxBound :: Word` rounds to 2^64.

GHC leaves scalar min/max operand choice unspecified for equal values and NaNs.
THC selects the second operand when its strict comparison is false. This differs
from the separate [vector extrema contract](floating-vector-minmax.md).

Inverse hyperbolic functions use cancellation-resistant formulas, returning NaN
for domain errors, signed infinity at `atanh(±1)`, and preserving signed zero for
`asinh`/`atanh`. They do not promise correctly rounded results for every input.

The raw bit casts preserve encodings rather than convert numeric values. Their
integer sides are `Word32#` and `Word64#`. Quiet-NaN payload and signed-zero bits
are retained during supported movement; signaling-NaN identity is not portable
across all JVM platforms. Floating literal case alternatives are invalid GHC Core
and rejected.

## Integer decomposition

`decodeFloat_Int#` and `decodeDouble_Int64#` return a signed integer significand
and binary exponent in an unboxed tuple. Both signed zeros return `(0,0)`.
The smallest positive subnormals return `(2^23,-172)` and `(2^52,-1126)`.
Non-finite encodings follow the pinned GHC bit decomposition, not finite
mathematical values.

`decodeDouble_2Int#` returns sign, high 32 significand bits, low 32 bits and
exponent. Its two Word fields are unsigned. The native RTS leaves the sign output
uninitialized for zero; THC deterministically returns `(+1,0,0,0)` for either
zero. See [FloatDecode.hs](../t/fixtures/core/FloatDecode.hs) for an example.

## Storage and calls

Float and Double remain distinct in typed locals, fields, captures and
[tuple results](tuple-results.md). Generic residual scalar calls still use
Truffle's Object call boundary and may box. The
[Core host ABI](site/embedding.md#load-a-core-entry) accepts their exact scalar
values; declared managed exports have their separate signature checks.

Original Haskell floating predicates and rounding imports require their
[ordinary native linkage](interface-foreign.md). Primitive support does not
replace a missing foreign library or supply the complete Integer/formatting
library closure.
