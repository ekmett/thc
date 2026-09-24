# Explicit Int64 and Word64 scalar primops

Both backends execute 36 additional operations from the pinned GHC 9.14.1
[Int64#/Word64# definitions](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/compiler/GHC/Builtin/primops.txt.pp#L608):

| Family | Added operations |
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
`IntRep`, `WordRep`, `Int64Rep` and `Word64Rep` remain distinct. Comparisons return
`IntRep`, and every shift count is `IntRep`. Signed right shifts extend the sign;
logical right shifts insert zeros. Word64 division and ordering are unsigned.
Arithmetic keeps the low 64 result bits, and scalar conversions preserve them.

The `word64` literal kind accepts canonical decimal values from zero through
18446744073709551615. A Long carries the full bit pattern, including the upper
unsigned half. Invalid spelling, negative values and overflow are load errors,
including in case alternatives and diagnostic mode.

`Explicit64PrimopsAudit.hs` exposes genuine typed scalar entries so conversions
cannot cancel around an Int# wrapper during optimization. The Haskell fixture
producer exports Core; JVM tests check
each intended primitive and the exact argument and result representations.
A separately compiled native driver bridges those types only at its I/O boundary.

There are 66,117 native/model rows across the 36 operations and two literal/case
controls. An independent JVM BigInteger model checks signed endpoints,
unsigned sign transitions, wraparound, neighboring values, alternating and
deterministically generated bit patterns. Every valid shift count from 0 to 63 is used.
Zero divisors, signed minimum divided by minus one, and shifts outside `[0,64)`
are excluded; no portable numeric result or exception protocol is promised for
those inputs.

`Explicit64PrimopsTest` runs every row before and after compilation on both
backends, requires one installed guest entry per compiled call and verifies each
exact target remains valid. Other tests reject wrong arities, malformed literals
and contradictory Int64/Word64 lexical proofs. Normal clean-checkout preparation
runs the fixture; SHA-256 manifests cover source/exporter inputs, Core and oracle,
with full `ghc --info` provenance. Gradle tracks the inputs and CI retains the
manifest, Core export, and oracle. Tuple results, counts, byte swaps, bit
reversal, floating point, SIMD and 32-bit target semantics are separate slices.
