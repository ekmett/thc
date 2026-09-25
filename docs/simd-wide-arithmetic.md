# Generated SIMD arithmetic and lane insertion

The family table adds 48 local operations across Int64X4/X8, Word64X4/X8,
Word32X16, FloatX16 and DoubleX8. Each shape has pack, unpack, broadcast, plus,
minus and times; signed and floating shapes also have negate, and floating
shapes have divide. The table also includes Int16X16 and Word16X16, with pack, unpack, broadcast, plus,
minus, times, insertion and signed negate. Signed min/max use the existing
Int8X16, Int16X8, Int32X4, Int32X8, Int64X2, Int64X4 and Int64X8 carriers;
unsigned min/max use Word8X16,
Word16X8, Word32X4 and Word32X8. The table contains 130 arithmetic names and 24 `insert`
operations, for 154 names. These remain partial entries
in the primop checklist.

The shared generator emits Kotlin carriers with final primitive lane fields,
exact Core proofs and AST families. Java declarations are limited to the
Bytecode DSL's nested operations. Int16X16 and Word16X16 each hold sixteen final
`Short` fields. Signed unpack sign-extends; unsigned Word16 and Word32 unpack
zero-extends. Word64 preserves the full 64-bit pattern, and integer arithmetic
wraps at lane width.
Floating arithmetic follows Java semantics; NaN payload selection is not
claimed. Vector calls, returns, captures, heap fields and joins remain outside
this local operation scope.

Insertion takes the exact vector, an exact scalar lane, and an `Int#` index.
The generated Kotlin methods replace that lane directly and preserve the other
lanes, including floating-point bit patterns. A shared typed helper uses the
legacy integer fields and FloatX4/DoubleX2 `pack`/`lane` APIs, preserving each
carrier representation. Indices outside the shape's lane range raise a runtime fault before any narrowing. Insertion covers
all currently represented vector shapes.

The capability smoke uses thirteen operation/lane drivers containing all 154
generated operations. Each driver contains whole arithmetic families, with at
most 112 lane-operation pairs to bound compiled code size. It checks 4,886
cases interpreted and after explicit compilation on both backends, observes
every lane, includes integer
sign/overflow edges, and checks retained compiled targets and released handoff
state. Every insertion index is observed through every result lane, with integer
boundaries, signed zero, subnormals and quiet NaN payloads. There are no spin
warmup loops. Native expectations come from Haskell
scalar primops; the default oracle requires no SIMD instruction set. Optional
native vector comparison requires a host capable of the selected 512-bit ISA.

All 154 names and 4,886 cases have passed GHC signature checks, native scalar
comparisons, strict Core audits, and interpreted/compiled AST and bytecode checks
in both handoff modes. Word32X8 min/max add 32 cases; the previous 4,854
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
