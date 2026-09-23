# Local Int16X8 vectors

The bounded contract is exactly seven GHC 9.14.1 primitives:
`packInt16X8#`, `unpackInt16X8#`, `broadcastInt16X8#`,
`plusInt16X8#`, `minusInt16X8#`, `negateInt16X8#`, and
`timesInt16X8#`. The exact vector representation is
`VecRep 8 Int16ElemRep`. Each lane is `Int16#`/`Int16Rep`, not
machine Int, Word16, or another 128-bit vector shape.

Pack takes **one logical eight-component unboxed tuple**; unpack returns that
scalar-lane tuple. Arithmetic wraps each lane modulo 65536, including low-16-bit
multiplication and negation of -32768. Widening sign-extends. No vector formal,
function result, capture, heap field, or vector-containing tuple ABI is enabled
by these fixtures. The explicit `vectorArgument` negative control must be
rejected specifically for its vector formal.

The AST and bytecode loaders use a durable carrier with eight final primitive
`short` fields (16 bytes of lane payload, not a 16-byte Java object). Arithmetic
constructs transient `ShortVector.SPECIES_128` values with constant lane indices,
then reconstructs the short fields. No durable vector object, generic payload
array, or boxed lane is stored. The interpreted JDK fallback may allocate private
`short[]` arrays; compiled allocation elimination is a separate evidence gate.
Operation dispatch is selected numerically while lowering Core. Unpack writes
eight sign-extended primitive Long frame slots. Exact unlifted operand flags and
recursive lane proofs are mandatory; intrinsic narrow literals may refine absent
or genuinely unconstrained metadata without relaxing variable or tuple proofs.

## Scalar signatures and independent observations

The fixture `compiler/test-fixtures/SimdInt16X8.hs` keeps vectors local.
All host arguments/results are machine `Int#` on a required 64-bit host.
The four graph-oriented entries `plusCase`, `minusCase`, `timesCase`,
and `negateCase` have two scalar arguments, `a` and `b`.
The packed lanes, before narrowing, are:

| Lane | Left | Right | Checksum weight |
| ---: | --- | --- | ---: |
| 0 | a | b+2 | 3 |
| 1 | b | a-3 | 5 |
| 2 | a+1 | 7*b+13 | 7 |
| 3 | b-1 | 11*a-17 | 11 |
| 4 | a+32767 | b+32768 | 13 |
| 5 | b-32768 | a-32767 | 17 |
| 6 | 3*a+7 | 13*b+19 | 19 |
| 7 | 5*b-11 | 17*a-23 | 23 |

The result checksum widens each output lane separately, multiplies it by its
distinct weight, and sums in machine Int. Its absolute value is below 2^22.
Scalar input arithmetic may wrap at machine width; reduction to the low 16 bits
makes the independent arbitrary-precision model equivalent without overflow UB.

`packCase` checks pack/unpack roundtripping. `broadcastCase` broadcasts
the narrowed value `a-b+29` and observes all eight lanes. These also have
arity two.

Checksums alone are not the complete lane evidence. `laneCase` has arity four:
`operation, lane, a, b`. Operations 0..5 select plus, minus, times, negate,
pack, or broadcast, respectively; lanes 0..7 select one signed result. Each
branch completes the local vector operation and returns a scalar; no vector
crosses the branch result boundary. The declared corpus uses only those selectors.

For every lane, the left and right inputs depend on different scalar seeds
through odd affine coefficients. The Python model inverts those coefficients
modulo 65536 to test the complete 9-by-9 operand grid
`[-32768,-32767,-257,-1,0,1,257,32766,32767]` independently at every
lane. Broadcast cases arrange every boundary value at every lane directly.
This gives 3,888 separate lane observations, exposing permutations and individual
lane failures that might cancel in a checksum.

## Residual scalar and tuple boundaries

`scalarHelperCase` invokes an opaque scalar worker and adds 17 to its result;
the worker computes the plus checksum with a 31 bias. Its model is therefore
`plusChecksum+48`. `tupleHelperCase` calls an opaque worker returning
the eight actual scalar Int16 lanes from vector multiplication, then computes the
weighted checksum in its caller. Neither helper accepts or returns a vector.
Only these residual controls are opaque; the graph-oriented entries have no
optimizer fences.

Both Core stages prove one actual guest root per direct entry (including
`laneCase`) and two per residual helper entry. Checks reject changed closure
membership, extra local lambdas, unsaturated or conditional helper calls, wrong
scalar formals, wrong tuple leaves, and changed vector-operation counts. The
same exact counts are required with Truffle inlining enabled or disabled; these
exclude the public host bridge. A static count is not itself compiled-execution
evidence.

## Preparation

With the pinned environment and the shared resource gate:

```sh
python3 scripts/test-int16x8-model.py
python3 scripts/prepare-int16x8-audit.py
```

The eight arity-two entries each have 268 distinct seed pairs, covering narrow
sign/wrap boundaries, all sixteen bit positions and neighbors, varied high bits,
and signed64 endpoints. Together with `laneCase`, the corpus has 6,032
native/model rows. The model uses integers only, and tests cross-check every row
against a separate mask/sign implementation, verify every lane's operand grid,
and exercise malformed tuple/root proofs.

Preparation regenerates original pre/post-Tidy Core, requires all eighteen
positive root audits to accept, and requires the negative vector-formal audit at
each stage to have precisely that one issue and no missing globals. It compiles
`SimdInt16X8Native.hs` and compares complete unique native rows with the
independent model. `build/simd-int16x8/provenance.json` records entry arities
and cases, actual-root proofs/counts, toolchain identity, commands, and hashes for
all source inputs and captured artifacts. `oracle.tsv` and `expected.tsv`
contain the native and model rows separately.

`--export-only` is explicitly pre-Tidy/model-only: no native code generation,
post-Tidy claim, or native oracle; `nativeRows` and `modelMatched` are null.
The mode removes an older oracle/provenance claim before starting.

Native and static evidence does not claim JVM execution, packed hardware code,
allocation elimination, throughput, or timing improvement. Those require the
separate runtime tests and retained graph/final-LIR evidence.

`SimdInt16VectorTest` checks dense storage and all 65,536 lane encodings, rejects
malformed proofs/flags and unsupported vector formals, and runs the full corpus
on each available Core stage and both AST/bytecode backends. It tests Truffle
inlining enabled and disabled separately, installing actual residual callees
before callers. Every measured invocation requires exactly one or two compiled
guest entries, unchanged active target identities and valid last-tier code;
there are no post-compilation settling calls or retries. Both result and argument
handoff pools must be released after every call. With native pre/post inputs this
is 48,256 checked invocations and 52,544 guest entries per handoff mode.

The separate [packed graph harness](../bench/experiments/int16x8-foundation/README.md)
checks actual result-connected i16x8 arithmetic, all eight observed output lanes,
and allocated XMM word instructions. It disallows surviving vector/carrier/array
allocations, lane boxing, field traffic and fallback calls, while allowing the
public host Long result box. Instrumentation is disabled only for these graph
captures, not for the correctness tests above.
