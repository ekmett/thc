# Signed narrow scalar primops

Both runtimes execute these 48 scalar operations, for `N = 8, 16, 32`:

| Operations | Primops |
| --- | --- |
| Negation and wrapping arithmetic | `negateIntN#`, `plusIntN#`, `subIntN#`, `timesIntN#` |
| Signed division | `quotIntN#`, `remIntN#` |
| Equality and signed order | `eqIntN#`, `neIntN#`, `ltIntN#`, `leIntN#`, `gtIntN#`, `geIntN#` |
| Same-width signed/unsigned casts | `intNToWordN#`, `wordNToIntN#` |
| Defined-range shifts | `uncheckedShiftLIntN#`, `uncheckedShiftRAIntN#` |

The audit uses the actual pinned GHC 9.14.1
[primop definitions](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/compiler/GHC/Builtin/primops.txt.pp#L277)
and [StgToCmm lowering](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/compiler/GHC/StgToCmm/Prim.hs#L1172).
GHC selects width-specific arithmetic and signed comparisons; widening `IntN#`
uses signed conversion. THC therefore keeps the low N result bits and
sign-extends 8/16-bit results into its `Int` computation carrier; 32-bit results
retain their raw bits. Width and signedness are fixed when lowering AST nodes
and bytecode operations.
Comparisons return full-width `Int#` zero or one. Quotients truncate toward zero;
remainders have the dividend's sign when nonzero.
The casts preserve the low N bits. `intNToWordN#` zero-extends the result,
while `wordNToIntN#` sign-extends it; both keep the exact GHC `IntNRep` and
`WordNRep` input and output contracts. Computation uses `Int`; durable fields
retain byte, short or int storage. Explicit widening alone produces `Long`.
The signed shifts normalize the input to N bits; left shift truncates and
sign-extends the result, while arithmetic right shift propagates the sign bit.
Their `Int#` count must be between zero and N minus one, as required by GHC's
unchecked shift contract.

No portable result or exception protocol is promised for zero divisors
**after narrowing**, or `minBound / -1` for quotient and remainder.
See [logical shifts and tuple division](integer-completion.md) for those operations.
