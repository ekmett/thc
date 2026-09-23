# Core coverage

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

Map got the runtime into a useful performance range. The next question is how
much Haskell it can run. The compatibility corpus gives that question a
repeatable answer against native GHC, across both executable backends.

Run `scripts/try.sh` from a fresh checkout. It builds the exporter, prepares the
native oracles and runs the JVM tests. The additional corpus is described in
[`examples/coverage.json`](../examples/coverage.json); it currently has 28 entries
and 507 distinct entry/input pairs, alongside the original fixtures and Map.

`GHC=ghc python3 scripts/primop-coverage.py` writes `build/primop-coverage.json`
from the pinned compiler's actual `allThePrimOps` table, including its generated
vector families. Preparation rejects advertised names or value arities that do
not match GHC. The report retains every signature and marks whether THC advertises
it; this inventory is not a claim that every operation or input is tested. The
native and compiled-execution suites below provide that separate evidence.

The [unsigned primop suite](integer-primops.md) adds 40 operations checked against
56,791 native/model rows on both backends, including installed-code checks for
each row. The [signed narrow suite](signed-narrow-primops.md) adds 36 operations
with 73,453 native/model rows and direct canonical-result checks. The
[explicit64 suite](explicit64-primops.md) adds 36 scalar operations, Word64
literals and 66,117 native/model rows with exact representation checks. The
[tuple arithmetic suite](tuple-arithmetic.md) adds six operations with 8,279
native two-field rows, exact local destinations and pre/post-Tidy compiled-entry
checks on both backends.

The [scalar bit suite](bit-primops.md) adds 21 population/zero-count, byte-swap,
and bit-reversal operations with 11,923 native/model rows, pre/post-Tidy exports,
and exact compiled-entry checks for every row on both backends.

The [floating suite](floating-primitives.md) adds 28 scalar `Float#`/`Double#`
operations and 441 native/model rows, with primitive locals, fields and captures.
Two additional square-root primitives have a separate 418-row native/model suite.
Four raw Float/Word32 and Double/Word64 bit casts have 13,555 exact-bit native/model
rows, including signed signalling NaNs, retained fields and captures, and separate
encode/decode controls against array storage.
Its compiled loop retains both precisions without boxing in the continuing loop;
generic scalar call boundaries still use the Object ABI. The floating tuple result
slice adds genuine `Data.Complex` CPR workers, 44 native/model rows and eight IEEE
bit rows, using concrete float/double result fields and local slots on both backends.

The [SIMD slice](simd.md) supports six local operations each for `Int64X2#` and
`Int32X4#`. Exact vector metadata keeps these values distinct from each other and
from unboxed tuples. The Int64 controls include actual Core graph evidence of
packed arithmetic with temporary carriers eliminated on AArch64 and x86. Vector
calling conventions and other shapes remain work.
The [FloatX4 foundation](floatx4.md) adds another six local vector primops and
2,196 native/model rows, including signed zeros, NaNs, exact subnormal ties and
separate multiply/add rounding. The [DoubleX2 foundation](doublex2.md) adds the
corresponding six binary64 operations, with an exact integer-significand model
and bit-sensitive edge controls. Vector ABI boundaries remain unchanged.
The [Int16X8](int16x8.md) and [Int8X16](int8x16.md) foundations each support
pack, unpack, broadcast, add, subtract, negate and multiply with exact narrow
lane proofs. Their native/model corpora contain 6,032 and 9,168 rows respectively,
including independently observable lanes and residual scalar/tuple calls.
Both use dense primitive carriers and retain the same local-only vector boundary.
The [Word8X16 foundation](word8x16.md) adds six unsigned byte-vector operations
and 7,712 native/model rows, with exact Word8 proofs, zero-extension and explicit
signed/unsigned mismatch controls. There is no GHC unsigned vector negate primop.
The [Word16X8 foundation](word16x8.md) adds the corresponding six unsigned
16-bit operations and 5,116 native/model rows. Word16 and Int16 proofs remain
distinct; unpack widens all eight lanes to 0..65535 without changing vector ABIs.
The [Word32X4 foundation](word32x4.md) adds six unsigned 32-bit operations and
4,882 native/model rows. Four Word32 lanes retain distinct proofs from Int32,
wrap modulo 2^32 and unpack to 0..4294967295; vector ABI limits remain unchanged.

The separate [library suite](library-coverage.md), run by
`scripts/try-libraries.sh`, adds 13 executable entries and 2,524 native-oracle
pairs covering real `Data.IntMap.Strict`, `Data.IntSet` and word primitives. Its Set
workload records a rejected frontier separately. CI runs both suites on Linux
and macOS, and also runs the JVM suite with the opt-in dense handoff enabled.

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
representations. Every host wrapper has type `Int# -> Int#`; the host ABI still
accepts machine integers only. Payloads include signed 64-bit extremes while
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
primitives to remain reachable. In particular, the chooser had to remain an
exported function to preserve its arity-one return boundary: `OPAQUE` alone
allowed GHC to eta-expand it to three arguments.

## Narrow unsigned words

`THC.NarrowWordCoverage` uses ordinary `Word8`, `Word16` and `Word32` operations,
with `Int#` only at the host entry. Its three arithmetic entries combine width
conversion, wrapping addition/subtraction/multiplication and unsigned `<`, `<=`
and equality. The record entry shares eight records between two order-sensitive
folds. There are no `OPAQUE`/`NOINLINE` fences: structural checks require the
actual producer and consumers to retain all three unpacked field widths and
both references to the shared list. Runtime checks require `records` to be
evaluated exactly once per call.

Actual GHC 9.14.1 Core required only these additional operations for each width
`N` in 8, 16 and 32: `wordToWordN#`, `wordNToWord#`, `plusWordN#`, `subWordN#`,
`timesWordN#`, `ltWordN#` and `leWordN#`. Equality already lowered to `eqWord#`.
The new `word8`, `word16` and `word32` literal forms accept canonical unsigned
decimal values in their exact ranges, including in case alternatives. Invalid
values remain load errors even in diagnostic mode.

Both runtimes use zero-extended primitive `Long` carriers for narrow words;
signed narrow integers retain their existing sign-extending behavior. Arithmetic
is reduced modulo the declared width. In particular, `Word32` maximum times
itself is 1, even though the full product exceeds signed 64-bit range, and
widening `0xffffffff` yields 4294967295, not -1. Typed unpacked storage already
supported these representations; no boxed numeric carrier was introduced.

The four entries add 140 native-oracle rows over signed-machine extremes and
values around every narrow sign and wrap boundary. A separate arbitrary-precision
model checks all those native results. Focused JVM tests also compare primitive
arithmetic with `BigInteger`, exercise both comparison operands and equality,
compile each primitive family, and reject malformed arities and literal values.
The general corpus checker supplies the held-out cold paths and requires
installed-code entry for every input after broad warmup and recompilation.

At this narrow-word checkpoint, Linux x86-64 with GHC 9.14.1 and GraalVM
25.3.4.1 passed 256 JVM tests in both default and opt-in handoff modes, plus
12 capability-auditor tests. Corpus preparation verified 24 strict entries,
458 native rows and 20 retained-structure facts. Existing library regressions
also passed 7,572 comparisons each on AST, bytecode and AST handoff, retaining
strict rejection of the Set frontier and the existing compiled-entry checks.

Preparation fingerprints its Haskell sources, compiler and preparation inputs,
exported Core, structural report and native oracle. A local test against stale
inputs fails with a request to prepare again. Reports live in `build/corpus/`;
CI retains them with the test results. The same checks run on Linux and macOS
for pushes, pull requests and merge groups.

## Boundaries found by the corpus

The first ordinary list export found missing executable unfoldings for
`GHC.Internal.Base.++` and `GHC.Internal.List.reverse1`. The list group now
compiles the complete, unmodified, SHA256-verified `Base` and `List` sources
from GHC's `ghc-9.14.1-release` tag under their original `ghc-internal` unit.
Post-Tidy export preserves the exact identities referenced by the installed
Prelude, so the fixtures use ordinary `(++)` and `reverse`. Both original
recursive bodies must remain reachable in the strict structural audit.
The missing-interface report is retained; the complete source modules resolve
those identities without aliases or reconstructed algorithms. Sources, boot
dependencies and `boot-provenance.json` join the corpus fingerprints.

`compiler/export-boot.py --frontier lists --build-dir build/corpus/groups/lists`
performs this source export using a private dynamic-interface overlay. It
loads the compiled exporter with GHC's `-fplugin-library` option: normal plugin
interface loading imports `GHC.Driver.Plugins` and its `Semigroup` instance,
which would import the installed `Base` into the very unit being rebuilt.
Installed interfaces and sources remain unchanged.

`map` and `filter` specialize/fuse into the tested pipeline; the test does not
establish execution of separate library call targets with those names.

Exact unboxed tuple results now execute on both backends with scalar/reference
inputs, including empty/singleton/nested results, lazy references, forwarding,
PAPs and overapplication. The [result protocol](tuple-results.md) is independent
of the optional input handoff experiment. Aggregate formal arguments, captures,
ordinary let bindings, join parameters/captures, sums
and unresolved layouts remain rejected, including unused and constructor-free
boundaries. Physical register counts alone never establish an aggregate layout.

The separate aggregate frontier reports three supported result-only entries and
eight rejected entries at both native export stages. `TupleReturnAudit` supplies
94 native rows for result-only boundary tests, including deep self/mutual tail
calls. These semantic controls remain separate from the library corpus and
provide no timing claim.

Float/Double, Integer/Natural, mutable arrays, general IO and FFI remain major
coverage work. The [coverage issue](https://github.com/ekmett/thc/issues/2) records concrete missing definitions and
primops exposed by new programs.

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
controls, not cross-runtime allocation-identity claims. Set remains outside the
supported execution corpus until its tuple and remaining cold paths are supported.

Exact scalar primop applications also use a [shared pinned signature contract](scalar-primitive-signatures.md)
to reject contradictory present argument/result proofs during lowering and audit.
Absent/unknown legacy metadata and representation-preserving newtype casts remain compatible.
