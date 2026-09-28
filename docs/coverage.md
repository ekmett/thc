# Core coverage

The compatibility corpus compares ordinary Haskell programs against native GHC
on both executable backends. Strict loading checks the complete reachable Core;
passing a finite corpus does not establish support for every use of a library.
The [primop checklist](primops.md) records implemented operations, while
[behavioral limits](primop-behavior.md) and the guides below describe their scope.

## Constructor tags

Saturated GHC 9.14.1 `dataToTagSmall#` and `dataToTagLarge#` calls use retained
concrete algebraic-family proofs. Both backends demand the outer data value and
return its zero-based constructor tag through class-owned layout guards, leaving
lazy fields untouched. The exporter records the complete ordered family and
GHC's target pointer-tag limit; a wrong small/large variant, newtype, function,
unknown family, or bare/partial primitive remains rejected. This is separate
from `tagToEnum#` and from unboxed tuple/sum tags. `prepare-data-to-tag.py` checks
293 fresh native/model rows, a forced-bottom exception, and pre/post-Tidy strict
frontiers, including unlifted boxed data, ordinary boxed tuples, and a legal
newtype unwrap before the operation on its underlying data value.
Family constructors retain the existing heap-field representation limits; this
operation adds no closure, foreign-pointer, aggregate-argument or newtype-tag ABI.
Tag selection currently explodes a linear scan of the complete family through
expected layout guards. The largest native control has nine constructors;
larger families may grow compiled graphs substantially, and this slice makes
no throughput claim. It adds no per-object tag or layout pointer and does not
resolve a layout through the global class registry on the tag-reading path.

## Preparing the corpus

Run `bin/try.sh` from a fresh checkout. It builds the exporter, prepares the
native oracles and runs the JVM tests. The additional corpus is described in
[`examples/coverage.json`](../examples/coverage.json); it currently has 28 entries
and 507 distinct entry/input pairs, alongside the original fixtures and Map.

`GHC=ghc cabal run exe:thc-primops -- coverage` writes `build/primop-coverage.json`
from the pinned compiler's actual `allThePrimOps` table, including its generated
vector families. Preparation rejects advertised names or value arities that do
not match GHC. The report retains every signature and marks whether THC advertises
it; this inventory is not a claim that every operation or input is tested. The
native and compiled-execution suites below provide that separate evidence.

The compiled `thc-primops` tool also verifies or regenerates scalar signatures
with its `scalars` command. Both commands use the GHC 9.14.1 API directly,
reject a different compiler or word size, and record the selected compiler,
tool binary and source hashes. Run `cabal test primop-tools` for the inventory,
scalar-contract, stale-file and command-line controls. The report remains
schema 2.

## Focused primitive suites

The corpus is complemented by native/model fixtures for
[unsigned integers](integer-primops.md), [signed narrow integers](signed-narrow-primops.md),
[explicit 64-bit values](explicit64-primops.md), [tuple arithmetic](tuple-arithmetic.md),
and [bit operations](bit-primops.md). Their guides describe exact input domains,
negative controls and interpreted/compiled checks.

[Floating primitives](floating-primitives.md) cover scalar arithmetic,
conversions, bit-sensitive operations and decomposition.
[Floating tuple results](tuple-results.md) retain primitive fields and slots;
generic scalar call boundaries still use Truffle's Object ABI.

[SIMD transport](simd.md) supports 30 exact `VecRep` shapes through guest
arguments/results, PAPs, joins, tuple fields and owned heap fields. Activation
transport uses raw fixed-species JDK vectors, also accepted as arguments and
results by the [Core host ABI](site/embedding.md#load-a-core-entry).
The [family inventory](simd-families.md) distinguishes
the complete operation coverage from focused foundation fixtures:
[FloatX4](floatx4.md), [DoubleX2](doublex2.md), [Int16X8](int16x8.md),
[Int8X16](int8x16.md), [Word8X16](word8x16.md), [Word16X8](word16x8.md),
and [Word32X4](word32x4.md). Exact vector proofs remain distinct from
equal-width vectors or scalar tuples. Managed vector loads/stores and their
offset units are covered separately for [128-bit arrays](simd128-array-memory.md),
[wide arrays](simd-wide-array-memory.md) and [128-bit addresses](simd128-address-memory.md).

The separate [library suite](library-coverage.md), run by
`bin/try-libraries.sh`, declares 17 supported entries and 2,676 native-oracle
pairs covering real `Data.IntMap.Strict`, `Data.IntSet`, four `Data.Sequence`
workloads and word primitives. Set and three additional Sequence entries are
explicit strict frontiers, not supported execution. The Build workflow runs
library checks on Linux and macOS with both backends and both handoff modes.

| Group | What it exercises |
|---|---|
| Lists | Composed map/filter, Prelude append and reverse from original GHC sources, unused bottom heads/tails, productive streams, a dynamic cyclic spine, two consumers sharing a list |
| Pointers | Non-strict reference-identity shortcuts with value-based equality fallbacks and untouched bottom-valued payloads |
| Functions | Lists of captured closures, genuine overapplication, reused partial application with an unused bottom argument, a shared thunk captured by an escaping closure |
| Trees | Three constructor layouts, recursive construction/folds, a captured higher-order map, selective traversal past bottom, shared subtrees |
| Int64 conversions | Exact `Int#`/`Int64#` argument and result boundaries, canonical literals and full-width endpoints |
| Narrow integers | Ordinary `Data.Int` conversions, truncation/sign extension and unpacked `Int8Rep`/`Int16Rep`/`Int32Rep` fields |
| Narrow words | Ordinary `Data.Word` conversions, modular arithmetic, unsigned comparisons and shared records with unpacked `Word8Rep`/`Word16Rep`/`Word32Rep` fields |
| Numeric | Word wraparound and rotations, signed quotient/remainder, signed narrowing, mixed primitive/reference fields and captures, Unicode characters through U+10FFFF |

The functional programs use ordinary Haskell `Int`, lists, functions and data
types internally. The numerical fixtures expose particular primitive
representations. Every wrapper in this corpus has type `Int# -> Int#`; this fixture interface is
not a description of every [managed embedding API](site/embedding.md). Payloads include signed 64-bit extremes while
list lengths, tree depths and numeric loops stay bounded.

## What a passing entry establishes

Each group is compiled at `-O2 -dcore-lint` with the pinned GHC 9.14.1 exporter.
Its reachable interface closure is exported separately: compiling roots from
several modules into one directory can overwrite the plugin's frontier file.
The strict dependency audit examines every reachable alternative and local
right-hand side, including unchosen lazy paths. Missing definitions or
unsupported constructs fail preparation. Diagnostic traps are not enabled for
this corpus.

A generated native driver compiles the same source with `-O2 -dcore-lint
-dstg-lint` and produces the expected results. The manifest selects disjoint
warm and held-out inputs for each entry. Each backend gets a fresh context for
each entry, then checks:

1. Native agreement on warm inputs before requested compilation.
2. Successful guest compilation and observed installed-code execution.
3. Native agreement on inputs withheld from warmup; these may deoptimize.
4. Recompilation after the broader input set, followed by native agreement and
   observed compiled entry for **every** input.

The shared list, subtree and captured-thunk cases also require exactly one
recorded evaluation of their named shared binding per call. Pure result
equality alone would miss duplicate evaluation. The unused-bottom cases must
terminate without a blackhole, and every supported entry must record zero
unsupported traps.

Structural checks keep the fixtures honest about what survived GHC. They check
actual callee arity and argument count for PAPs/overapplication, the dynamic
list back edge, shared binding identities, captured functions, unforced list
fields and recursive tree alternatives. Numeric entries require their intended
primitives to remain reachable. The chooser is exported to preserve its
arity-one return boundary; a source annotation alone is not evidence that a
particular application shape survived optimization.

## Narrow unsigned words

`THC.NarrowWordCoverage` uses ordinary `Word8`, `Word16` and `Word32` operations,
with `Int#` only at the host entry. Its three arithmetic entries combine width
conversion, wrapping addition/subtraction/multiplication and unsigned `<`, `<=`
and equality. The record entry shares eight records between two order-sensitive
folds. There are no `OPAQUE`/`NOINLINE` fences: structural checks require the
actual producer and consumers to retain all three unpacked field widths and
both references to the shared list. Runtime checks require `records` to be
evaluated exactly once per call.

The fixture's GHC 9.14.1 Core uses these operations for each width
`N` in 8, 16 and 32: `wordToWordN#`, `wordNToWord#`, `plusWordN#`, `subWordN#`,
`timesWordN#`, `ltWordN#` and `leWordN#`. Equality lowers to `eqWord#`.
The `word8`, `word16` and `word32` literal forms accept canonical unsigned
decimal values in their exact ranges, including in case alternatives. Invalid
values remain load errors even in diagnostic mode.

Both runtimes compute 8/16/32-bit signed and unsigned integers in primitive `Int`
carriers. `Word32` retains raw bits; declared unsigned widening zero-extends them.
Signed narrow integers preserve sign extension. Arithmetic
is reduced modulo the declared width. In particular, `Word32` maximum times
itself is 1, even though the full product exceeds signed 64-bit range, and
widening `0xffffffff` yields 4294967295, not -1. Unpacked fields store the
primitive values without a separate numeric wrapper.

The four entries add 140 native-oracle rows over signed-machine extremes and
values around every narrow sign and wrap boundary. A separate arbitrary-precision
model checks all those native results. Focused JVM tests also compare primitive
arithmetic with `BigInteger`, exercise both comparison operands and equality,
compile each primitive family, and reject malformed arities and literal values.
The general corpus checker supplies the held-out cold paths and requires
installed-code entry for every input after broad warmup and recompilation.

Preparation fingerprints its Haskell sources, compiler and preparation inputs,
exported Core, structural report and native oracle. A local test against stale
inputs fails with a request to prepare again. Reports live in `build/corpus/`;
CI retains them with the test results. The full Build workflow runs the corpus
on Linux and macOS; focused PR checks select tests according to changed files.

## Original library sources and aggregate boundaries

The list group compiles the complete, unmodified, SHA256-verified `Base` and `List` sources
from GHC's `ghc-9.14.1-release` tag under their original `ghc-internal` unit.
Post-Tidy export preserves the exact identities referenced by the installed
Prelude, so the fixtures use ordinary `(++)` and `reverse`. Both original
recursive bodies must remain reachable in the strict structural audit.
The complete source modules resolve those identities without aliases or
reconstructed algorithms. Sources, boot
dependencies and `boot-provenance.json` join the corpus fingerprints.

`python3 bin/export-boot.py --frontier lists --build-dir build/corpus/groups/lists`
performs this source export using a private dynamic-interface overlay. It
loads the compiled exporter with GHC's `-fplugin-library` option: normal plugin
interface loading imports `GHC.Driver.Plugins` and its `Semigroup` instance,
which would import the installed `Base` into the very unit being rebuilt.
Installed interfaces and sources remain unchanged.

`map` and `filter` specialize/fuse into the tested pipeline; the test does not
establish execution of separate library call targets with those names.

Exact unboxed tuples support guest inputs/results, owned captures and local joins,
including empty, singleton and nested layouts. Binary unboxed sums have separate
supported input/result, join and owned-field paths. Ordinary aggregate
let/global bindings and unresolved layouts remain rejected. Read the
[aggregate layout](aggregate-layout.md), [tuple capture](tuple-captures.md),
[tuple join](tuple-joins.md), and [sum input](sum-inputs.md) contracts for their
exact restrictions. Physical register counts alone do not establish a layout.
These semantic tests are separate from the library corpus.

General boot-library closure, IO and FFI coverage remains incomplete. A supported
primitive or aggregate representation does not supply a missing Haskell body.
The [library guide](library-coverage.md) distinguishes tested consumers from
source/provider frontiers; the [coverage issue](https://github.com/ekmett/thc/issues/2)
tracks broader missing definitions and operations.

## Pointer identity

`reallyUnsafePtrEquality#` compares the two current object references and returns
an `Int#` represented by a JVM `long` containing zero or one. It does not force
either lifted operand, compare fields, or follow an updated thunk's result.
This follows GHC 9.14.1's [pointer comparison contract](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/compiler/GHC/Builtin/primops.txt.pp#L3618)
and [direct Cmm pointer comparison](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/compiler/GHC/StgToCmm/Prim.hs).

The native corpus tests a sound identity shortcut followed by key equality when
references differ. Its values therefore agree even when native GHC and the JVM
allocate or share differently. Structural checks require the primitive to remain
the first branch condition with two lifted operands, non-strict demand metadata,
an `IntRep` result, and an intact fallback. A second fixture retains irrelevant
bottom-valued payloads on both objects.

Separate runtime controls cover same and distinct heap objects, objects whose
host `equals` methods agree, bottom thunks on either side, updated thunk aliases,
and full-width arithmetic on both outcomes. They check local forwarding before
and after each alias is forced, selective forcing only in a chosen fallback,
installed compiled execution, and strict arity rejection. These are identity
controls, not cross-runtime allocation-identity claims. The library suite's Set
bundle remains a strict missing-definition frontier; pointer identity and tuple
support do not by themselves supply those cold Haskell bodies.

Scalar primop applications use a [shared pinned signature contract](scalar-primitive-signatures.md).
The exporter auditor checks source-level representations; lowering shares a
`Long` carrier across integral annotations and lets the selected operation
supply signedness and narrowing. Physical carrier, arity, aggregate and vector
shape checks remain meaningful runtime boundaries.
