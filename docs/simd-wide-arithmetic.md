# Generated SIMD arithmetic and lane insertion

The family table adds 48 local operations across Int64X4/X8, Word64X4/X8,
Word32X16, FloatX16 and DoubleX8. Each shape has pack, unpack, broadcast, plus,
minus and times; signed and floating shapes also have negate, and floating
shapes have divide. The table also includes Int16X16 and Word16X16, with pack, unpack, broadcast, plus,
minus, times, insertion and signed negate. Signed min/max use the existing
Int8X16, Int16X8, Int32X4, Int32X8, Int64X2, Int64X4 and Int64X8 guest shapes;
unsigned min/max use Word8X16,
Word16X8, Word32X4, Word32X8 and Word64X2/X4/X8. Floating min/max now cover
FloatX4/X8/X16 and DoubleX2/X4/X8 with a separate [Java-semantics contract](floating-vector-minmax.md).
The six wide byte/short shapes Int8X32/X64, Word8X32/X64, Int16X32 and
Word16X32 plus [integer quotient/remainder and shuffle](simd-quot-rem-shuffle.md)
bring the table to 315 operations, including 30 `insert` names.
These tested operations are implemented entries in the primop checklist.

The shared generator emits exact Core proofs and typed AST families, not JVM
vector wrapper classes. Java declarations are limited to the Bytecode DSL's
nested operations. Each operation uses a raw fixed-species JDK Vector API value;
Int16X16 and Word16X16 both use `ShortVector.SPECIES_256`, with signedness retained
in `VecRep` metadata. Signed unpack sign-extends; unsigned Word16 and Word32 unpack
zero-extends. Word64 preserves the full 64-bit pattern, and integer arithmetic
wraps at lane width.
Floating arithmetic follows Java semantics; NaN payload selection is not
claimed. Vector arguments/results, captures, heap fields and joins have a separate
[transport contract](simd.md); this operation corpus alone does not certify it.

Insertion takes the exact vector, an exact scalar lane, and an `Int#` index.
The generated Kotlin operations use the raw vector's `withLane` operation and
preserve the other lanes, including floating-point bit patterns. Indices outside
the shape's lane range raise a runtime fault before any narrowing. Insertion covers
all currently represented vector shapes.

The capability smoke uses 133 operation/lane drivers containing all 315
generated operations. Drivers keep arithmetic families separate and split large ones, with at most
112 lane-operation pairs to bound compiled code size. Quotient, remainder and
shuffle keep separate entries, leaving the ordinary arithmetic groups unchanged.
The current fixture has
22,462 native scalar observations and 1,848 Java floating-extrema edge requests.
Its JVM checks run interpreted and after explicit compilation on both backends,
observe every lane, include integer sign/overflow edges, and check retained compiled targets and released handoff
state. Exact guest-entry counts include GHC's argument-dropping workers where
present in the strict Core audit. Every insertion index is observed through every result lane, with integer
boundaries, signed zero, subnormals and quiet NaN payloads. There are no spin
warmup loops. Native expectations come from Haskell
scalar primops; the default oracle requires no SIMD instruction set. Optional
native vector comparison requires a host capable of the selected 512-bit ISA.

The previously recorded 160 names and 4,962 cases passed GHC signature checks, native scalar
comparisons, strict Core audits, and interpreted/compiled AST and bytecode checks
in both handoff modes. Word64X2/X4/X8 min/max add 76 cases; the previous 4,886
expectations are unchanged. The optional native vector comparison has not
been run for these wide shapes, and these checks make no hardware SIMD or
performance claim.

```sh
python3 scripts/generate-simd-families.py --check --verify-ghc
python3 scripts/prepare-simd-capability-smoke.py
./gradlew test --tests thc.runtime.SimdCapabilitySmokeTest --tests thc.runtime.SimdFamiliesTest
python3 scripts/primop-coverage.py --check
```

`--native-vector` on the preparation command also compares native vector output
with native scalar output. Pass the host's explicit compiler ISA flags as
`--ghc-option=...`; the manifest records the mode and flags. This check supplies
semantic evidence, not a JVM hardware-SIMD or performance guarantee.
