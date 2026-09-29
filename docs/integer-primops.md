# Unsigned scalar primops

Both backends implement these unsigned scalar families:

| Carrier | Primitives |
| --- | --- |
| Word# | `quotWord#`, `remWord#`, `gtWord#`, `geWord#` |
| Word8#/Word16#/Word32# | `quotWordN#`, `remWordN#`, `eqWordN#`, `neWordN#`, `gtWordN#`, `geWordN#` |
| Word8#/Word16#/Word32# | `andWordN#`, `orWordN#`, `xorWordN#`, `notWordN#`, `uncheckedShiftLWordN#`, `uncheckedShiftRLWordN#` |
| Word# / Word64# | `pdep#`, `pext#`, and their 8/16/32/64-bit variants |

Machine words retain all 64 bits in a Long, using Java's
unsigned division/remainder and comparison facilities. Narrow unsigned values use the [narrow integer carriers](narrow-integer-carriers.md);
masks truncate shifts and complements and bound division and comparison operands. Bit deposit/extract use `Long.expand` and
`Long.compress` with exact-width masks; the 8/16/32 variants consume and return
`Word#`, while the 64-bit variant uses `Word64#`. Each bytecode operation has a constant width
mask and primitive operands/results. No aggregate transport changed.

Division by zero and unchecked shifts outside `[0,width)` have no portable
numeric result. See [signed narrow operations](signed-narrow-primops.md),
[64-bit operations](explicit64-primops.md), [bit operations](bit-primops.md),
and [tuple arithmetic](tuple-arithmetic.md) for the other scalar families.
