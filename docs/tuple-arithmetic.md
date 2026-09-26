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
| `addWordC#` | two `Word#`; `(# Word#, Int# #)` | low wrapped sum word, then unsigned carry flag |
| `subWordC#` | two `Word#`; `(# Word#, Int# #)` | low wrapped difference word, then unsigned borrow flag |
| `plusWord2#` | two `Word#`; `(# Word#, Word# #)` | high carry word, then low sum word |
| `timesWord2#` | two `Word#`; `(# Word#, Word# #)` | high product word, then low product word |

Carry, borrow and signed overflow flags are zero when the mathematical result fits and one when
it does not; GHC specifies zero versus nonzero. Word subtraction reports borrow
when the unsigned left operand is smaller. Unlike `plusWord2#`, both WordC
operations return the low result first and an `IntRep` flag second. Words retain all 64 bits in the
existing Long carrier. Multiplication uses `Math.unsignedMultiplyHigh` for the
high word and ordinary wrapping multiplication for the low word.

The AST expression evaluates both operands once, computes both fields and writes
the destination slots. The bytecode operation has two primitive Long operands,
two constant local accessors and no stack result. Neither path constructs a
`DataValue`, tuple payload array or return slab for the saturated primitive.
Returning those fields through an ordinary function still uses the separate
[tuple result protocol](tuple-results.md); local primitive evaluation requires
no call boundary.

Load validation requires Long input carriers and a flat logical tuple of the
operation's result arity. The static auditor separately checks exact GHC register
names and order; runtime lowering trusts physically equivalent integral carriers.
A scalar result, same-width nested tuple, unknown leaf or missing proof is
rejected. Primops used as first-class values and partial or
oversaturated applications remain unsupported.

The [scalar integer completion](integer-completion.md) extends this protocol to
all six narrow quotient/remainder operations and the three-input double-word
unsigned division operation.

`TupleArithmeticAudit.hs` retains each genuine primitive in both pre- and
post-Tidy Core. A dynamic selector observes each result field separately without
turning the tuple into a boxed product. `thc-fixtures tuple-arithmetic` exports
both boundaries, audits ten entry roots at each boundary, and produces native two-field rows.
Inputs include signed endpoints, word sign/carry transitions, neighbors of
powers of two through bit 63 (every bit for WordC), equal operands, negative divisors and reproducible
bit patterns. Division by zero and signed minimum divided by minus one
are excluded from the native oracle; the runtime reports an undefined-input
fault for those cases and assigns no numeric result.

`TupleArithmeticTest` checks native fields against an independent `BigInteger`
model and executes every field in both backends before and after compilation,
for both export boundaries. Every checked compiled call must enter installed
guest code, each exact entry must remain valid, and tuple result pool allocations
must stay zero. Other tests reject forged layouts, wrong arities and first-class
primops, and verify recovery after invalid division inputs. Python auditor
tests include matching shape and saturation negatives.

`WordCarryTest` additionally checks direct and opaque mixed `WordRep`/`IntRep`
results with normal and disabled inlining, pre/post Tidy and both backends. The
opaque producer adjusts the low word by one and its caller subtracts one to retain
an actual return boundary instead of an eta-reduced first-class primop. The flag
stays unchanged. Each measured invocation must enter exactly one direct or two
caller/producer guest roots, retain installed original and active target identities,
and leave argument/result pools empty. Malformed field order and signedness are
rejected. Operand-failure controls require both destinations to remain unchanged.

This adds only the two missing WordC operations. Original Integer/Natural arithmetic
still has exact mutable-size/shrink and GMP/exception frontiers; removing the one
reachable WordC issue from each addition root does not make either closure supported.
The existing enum dispatch is unchanged; no new graph or throughput claim is made.

Normal clean-checkout preparation runs this fixture. Its manifest fingerprints
the source, exporter, preparation script, capability audit, exported Core and
native oracle, and records full `ghc --info` provenance. Gradle tracks these test
inputs and CI retains the generated artifacts. These eight operations use the same two-Long destination protocol;
the separate [typed tuple input protocol](tuple-inputs.md), aggregate capture
restrictions, and other arithmetic families retain their own coverage boundaries.
