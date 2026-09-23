# Core coverage

Map got the runtime into a useful performance range. The next question is how
much Haskell it can run. The compatibility corpus gives that question a
repeatable answer against native GHC, across both executable backends.

Run `scripts/try.sh` from a fresh checkout. It builds the exporter, prepares the
native oracles and runs the JVM tests. The additional corpus is described in
[`examples/coverage.json`](../examples/coverage.json); it currently has 24 entries
and 458 distinct entry/input pairs, alongside the original fixtures and Map.

The separate [library suite](library-coverage.md), run by
`scripts/try-libraries.sh`, adds 13 executable entries and 2,524 native-oracle
pairs covering real `Data.IntMap.Strict`, `Data.IntSet` and word primitives. Its Set
workload records a rejected frontier separately. CI runs both suites on Linux
and macOS, and also runs the JVM suite with the opt-in dense handoff enabled.

| Group | What it exercises |
|---|---|
| Lists | Composed map/filter, source-defined append and fold reversal, unused bottom heads/tails, productive streams, a dynamic cyclic spine, two consumers sharing a list |
| Functions | Lists of captured closures, genuine overapplication, reused partial application with an unused bottom argument, a shared thunk captured by an escaping closure |
| Trees | Three constructor layouts, recursive construction/folds, a captured higher-order map, selective traversal past bottom, shared subtrees |
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
`GHC.Internal.Base.++` and `GHC.Internal.List.reverse1`. Complete source export
is still needed for those library bodies. The supported fixture uses a
source-defined recursive append and a fold reversal. Replacing append with
`foldr (:)` was insufficient: GHC rewrote it to the same missing `(++)` binding.
`map` and `filter` specialize/fuse into the tested pipeline; the test does not
establish execution of separate library call targets with those names.

Unboxed tuples and sums need an aggregate return convention. They remain
outside the supported corpus. Exported representation evidence now preserves
an explicit `aggregate` marker; physical register counts alone cannot distinguish
an empty/singleton tuple from a state token/scalar. Strict loading rejects these
boundaries even when there is no reachable constructor or the formal is unused.
Diagnostic mode retains cold traps, including after compilation. Older exports
without the marker should be regenerated.

The separate aggregate frontier retains genuine
GHC producers, forwarders, multiple outstanding results, zero-width components,
lazy payloads, cold alternatives and constructor-free identities. Its native
results and rejection checks are reported separately from supported execution.

Float/Double, Integer/Natural, mutable arrays, general IO and FFI remain major
coverage work. The [coverage issue](https://github.com/ekmett/thc/issues/2) records concrete missing definitions and
primops exposed by new programs.
