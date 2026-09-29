# Explicit Int64 and Word64 scalar primops

Both backends execute these operations from the pinned GHC 9.14.1
[Int64#/Word64# definitions](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/compiler/GHC/Builtin/primops.txt.pp#L608):

| Family | Operations |
| --- | --- |
| Scalar conversions | `int64ToWord64#`, `word64ToInt64#`, `wordToWord64#`, `word64ToWord#` |
| Int64 arithmetic | `negateInt64#`, `plusInt64#`, `subInt64#`, `timesInt64#`, `quotInt64#`, `remInt64#` |
| Word64 arithmetic | `plusWord64#`, `subWord64#`, `timesWord64#`, `quotWord64#`, `remWord64#` |
| Comparisons | `eq`, `ne`, `lt`, `le`, `gt`, `ge` for both `Int64#` and `Word64#` |
| Word64 bitwise operations | `and64#`, `or64#`, `xor64#`, `not64#` |
| Int64 shifts | `uncheckedIShiftL64#`, `uncheckedIShiftRA64#`, `uncheckedIShiftRL64#` |
| Word64 shifts | `uncheckedShiftL64#`, `uncheckedShiftRL64#` |

The existing `intToInt64#`/`int64ToInt#` conversions remain supported. Every
operation uses a primitive Long instruction on THC's supported 64-bit targets.
Selection of a shared instruction does not rewrite the exported Core proof:
`IntRep`, `WordRep`, `Int64Rep` and `Word64Rep` remain distinct in GHC and the
strict exporter audit. Runtime lowering accepts their shared Long carrier;
the selected primitive determines signedness and arithmetic. Comparisons return
`IntRep`, and every shift count is `IntRep`. Signed right shifts extend the sign;
logical right shifts insert zeros. Word64 division and ordering are unsigned.
Arithmetic keeps the low 64 result bits, and scalar conversions preserve them.

The `word64` literal kind accepts canonical decimal values from zero through
18446744073709551615. A Long carries the full bit pattern, including the upper
unsigned half. Invalid spelling, negative values and overflow are load errors,
including in case alternatives and diagnostic mode.

No portable numeric result or exception protocol is promised for zero divisors,
signed minimum divided by minus one, or unchecked shifts outside `[0,64)`.
