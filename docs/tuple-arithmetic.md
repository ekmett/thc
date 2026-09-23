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
| `plusWord2#` | two `Word#`; `(# Word#, Word# #)` | high carry word, then low sum word |
| `timesWord2#` | two `Word#`; `(# Word#, Word# #)` | high product word, then low product word |

Signed overflow flags are zero when the mathematical result fits and one when
it does not; GHC specifies zero versus nonzero. Words retain all 64 bits in the
existing Long carrier. Multiplication uses `Math.unsignedMultiplyHigh` for the
high word and ordinary wrapping multiplication for the low word.

The AST expression evaluates both operands once, computes both fields and writes
the destination slots. The bytecode operation has two primitive Long operands,
two constant local accessors and no stack result. Neither path constructs a
`DataValue`, tuple payload array or return slab for the saturated primitive.
Returning those fields through an ordinary function still uses the separate
[tuple result protocol](tuple-results.md); local primitive evaluation requires
no call boundary.

Load validation and the static auditor require the exact scalar input register
names and a flat logical tuple with exactly two corresponding scalar leaves.
A scalar result, same-width nested tuple, unknown leaf, missing proof or wrong
signedness is rejected. Primops used as first-class values and partial or
oversaturated applications remain unsupported.

`TupleArithmeticAudit.hs` retains each genuine primitive in both pre- and
post-Tidy Core. A dynamic selector observes each result field separately without
turning the tuple into a boxed product. `prepare-tuple-arithmetic.py` checks
8,279 native two-field rows against Python's unbounded integer arithmetic.
Inputs include signed endpoints, word sign/carry transitions, neighbors of
powers of two through bit 63, equal operands, negative divisors and reproducible
random bit patterns. Division by zero and signed minimum divided by minus one
are excluded from the native oracle; the runtime reports an undefined-input
fault for those cases and assigns no numeric result.

`TupleArithmeticTest` checks native fields against an independent `BigInteger`
model and executes every field in both backends before and after compilation,
for both export boundaries. Every checked compiled call must enter installed
guest code, each exact entry must remain valid, and tuple result pool allocations
must stay zero. Other tests reject forged layouts, wrong arities and first-class
primops, and verify recovery after invalid division inputs. Python auditor
tests include matching shape and saturation negatives.

Normal clean-checkout preparation runs this fixture. Its manifest fingerprints
the source, exporter, preparation script, capability audit, exported Core and
native oracle, and records full `ghc --info` provenance. Gradle tracks these test
inputs and CI retains the generated artifacts. This adds six operations;
the separate [typed tuple input protocol](tuple-inputs.md), aggregate capture
restrictions, and other arithmetic families retain their own coverage boundaries.
