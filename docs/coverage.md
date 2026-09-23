# Core coverage

Map got the runtime into a useful performance range. The next question is how
much Haskell it can run. The compatibility corpus gives that question a
repeatable answer against native GHC, across both executable backends.

Run `scripts/try.sh` from a fresh checkout. It builds the exporter, prepares the
native oracles and runs the JVM tests. The additional corpus is described in
[`examples/coverage.json`](../examples/coverage.json); it currently has 20 entries
and 318 distinct entry/input pairs, alongside the original fixtures and Map.

The separate [library suite](library-coverage.md), run by
`scripts/try-libraries.sh`, adds six executable entries and 972 native-oracle
pairs covering real `Data.IntMap.Strict` and unsigned word primitives. Its Set
workload records a rejected frontier separately. CI runs both suites on Linux
and macOS, and also runs the JVM suite with the opt-in dense handoff enabled.

| Group | What it exercises |
|---|---|
| Lists | Composed map/filter, source-defined append and fold reversal, unused bottom heads/tails, productive streams, a dynamic cyclic spine, two consumers sharing a list |
| Functions | Lists of captured closures, genuine overapplication, reused partial application with an unused bottom argument, a shared thunk captured by an escaping closure |
| Trees | Three constructor layouts, recursive construction/folds, a captured higher-order map, selective traversal past bottom, shared subtrees |
| Narrow integers | Ordinary `Data.Int` conversions, truncation/sign extension and unpacked `Int8Rep`/`Int16Rep`/`Int32Rep` fields |
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

Preparation fingerprints its Haskell sources, compiler and preparation inputs,
exported Core, structural report and native oracle. A local test against stale
inputs fails with a request to prepare again. Reports live in `build/corpus/`;
CI retains them with the test results. The same checks run on Linux and macOS
for pushes, pull requests and merge groups.

## Boundaries found by the corpus

The first ordinary list export found missing executable unfoldings for
`GHC.Internal.Base.++` and `GHC.Internal.List.reverse1`. Complete source export
is still needed for those library bodies. The supported fixture uses a
source-defined recursive append and a fold reversal. Replacing append with
`foldr (:)` was insufficient: GHC rewrote it to the same missing `(++)` binding.
`map` and `filter` specialize/fuse into the tested pipeline; the test does not
establish execution of separate library call targets with those names.

Exact unboxed tuple results now execute on both backends with scalar/reference
inputs, including empty/singleton/nested results, lazy references, forwarding,
PAPs and overapplication. The [result protocol](tuple-results.md) is independent
of the optional input handoff experiment. Aggregate formal arguments, captures,
ordinary let bindings, join parameters/results, sums, scalar void tuple components
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
