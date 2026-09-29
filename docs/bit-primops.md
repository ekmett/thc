# Scalar bit primitives

THC implements these 24 unary GHC 9.14.1 primitives in both the AST and bytecode
backends, alongside machine `popCnt#`, `clz#`, and `ctz#`.

| Operations | Input | Result |
| --- | --- | --- |
| `popCnt8/16/32#`, `clz8/16/32#`, `ctz8/16/32#` | `Word#`, lower N bits | `Word#` count |
| `popCnt64#`, `clz64#`, `ctz64#` | `Word64#` | `Word#` count |
| `byteSwap16/32#`, `bitReverse8/16/32#` | `Word#`, lower N bits | `Word#`, N defined bits |
| `byteSwap64#`, `bitReverse64#` | `Word64#` | `Word64#` |
| `byteSwap#`, `bitReverse#` | machine `Word#` | machine `Word#` |
| `narrow8/16/32Word#` | machine `Word#` | zero-extended machine `Word#` |

The pinned `compiler/GHC/Builtin/primops.txt.pp` defines the narrow count
operations over the lower input bits. Higher input bits are ignored, including
negative signed host carriers. Both zero counts return N for an all-zero N-bit
input; population count returns zero. THC uses 64-bit machine words.

GHC marks only N output bits defined for narrow byte swaps and bit reversal.
THC chooses canonical zero upper bits. The native driver masks the undefined
upper bits before comparison; this makes no claim about their native contents.
The exported wrappers contain no output mask, so guest comparisons also check
THC's canonical result directly. Full-width results preserve all 64 bits in a
signed `Long` carrier. The explicit `Word64#` wrappers use the real
`wordToWord64#` and `word64ToWord#` conversion primitives.
The `narrowNWord#` conversions retain only the low N bits and zero-extend
the full result carrier, including for negative host inputs.

Widths become immutable AST fields or bytecode constant operands during
lowering. Guest execution uses primitive `long` operands and results, with
fixed masks/shifts and Java's population-count, zero-count, byte-reversal, and
bit-reversal operations. No generic boxed arithmetic is added.
