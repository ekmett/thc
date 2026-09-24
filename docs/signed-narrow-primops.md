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
sign-extends them into its Long carrier. A fixed shift is selected at AST node
construction and passed as a constant operand to each bytecode operation.
Comparisons return full-width `Int#` zero or one. Quotients truncate toward zero;
remainders have the dividend's sign when nonzero.
The casts preserve the low N bits. `intNToWordN#` zero-extends the result,
while `wordNToIntN#` sign-extends it; both keep the exact GHC `IntNRep` and
`WordNRep` input and output contracts even though THC stores each in a Long.
The signed shifts normalize the input to N bits; left shift truncates and
sign-extends the result, while arithmetic right shift propagates the sign bit.
Their `Int#` count must be between zero and N minus one, as required by GHC's
unchecked shift contract.

The numeric oracle excludes zero divisors **after narrowing** and the
`minBound / -1` overflow pair for both quotient and remainder. This slice does
not promise a portable result or exception protocol for those inputs. It does
not add logical narrow right shifts, tuple-producing operations,
or new aggregate argument/capture support.

`SignedNarrowPrimopsAudit.hs` contains dynamic wrappers around the actual primops.
The Haskell `thc-fixtures` executable exports Core and compiles a native GHC driver with
Core/STG lint enabled. The 94,032 oracle rows include signed endpoints, zero,
equal and adjacent operands, every bit boundary and its neighbors, overflow
products, both signs of quotient/remainder, all `Word8#` values for its signed cast,
and 64-bit host values that truncate on entry. Negation covers every `Int8#` value;
the new shifts cover every valid count and every `Int8#` value.
A SHA-256 manifest covers source,
compiler/exporter inputs, exported modules, and the native oracle.

`SignedNarrowPrimopsTest` checks every native result using independent
`BigInteger` arithmetic, then executes every row on AST and bytecode before and
after explicit compilation. After compilation it requires exactly one compiled
guest entry per row, with zero unsupported traps and blackholes. Separate direct
primitive-result controls omit `intNToInt#`: a final conversion cannot conceal a
noncanonical arithmetic result. All 48 names reject both too few and too many
arguments during strict and diagnostic loading. The six casts also reject
contradictory argument and result register metadata in both loading modes.

Preparation is part of the normal `scripts/prepare-tests.sh` clean-checkout flow.
Gradle tracks the generated artifacts and preparation inputs, and CI retains the
manifest, Core export, and oracle. For a focused reproduction with the pinned
GHC/GraalVM environment:

```sh
compiler/build.sh
cabal run exe:thc-fixtures --offline -- signed-narrow
./gradlew test --tests thc.SignedNarrowPrimopsTest
```

The macOS AArch64 run on GHC 9.14.1 and GraalVM 25.3.4.1 passed the focused
JVM and native checks. No timing or throughput claim is made.
