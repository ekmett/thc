# Tuple arguments to local joins

Both backends accept exact recursively shaped unboxed tuple arguments to local
joins. Logical arity is unchanged: one tuple remains one join parameter, including
an empty tuple. Primitive leaves use existing typed frame slots; lifted leaves
remain lazy. State and other void components keep their logical positions without
occupying a payload slot. Vector leaves retain their exact species.

All operands are evaluated before any join formal is overwritten. Recursive
swaps therefore see the previous activation's values. The AST uses its existing
tuple-slot reader for the parallel copy; bytecode uses its existing typed locals.
Completed scratch references are cleared before the jump. Local transfers create
no function-call packet or tuple result carrier and borrow no handoff slab.
Actual logical shape, arity, levity and carrier checks remain in place; matching
register counts alone do not establish matching tuples.

An empty tuple `case` still evaluates its scrutinee. Original Tasty code contains
`case retry# state of {}` and equivalent bottoming tuple calls. Exceptions and
STM retry propagate normally. If malformed Core returns from that scrutinee,
the case raises the ordinary non-exhaustive-case fault. No bottom proof or
fabricated successful result is required.

This contract covers the original GHC 9.14.1 `GHC.Internal.Float.$wroundTo` local
join whose third argument is `(# Int, [Int] #)`. [Binary sum arguments and PAPs](sum-inputs.md)
and [owned tuple closure/thunk captures](tuple-captures.md) have separate typed
transport paths. Ordinary aggregate let/global storage and public host aggregate
parameters/results remain unsupported.

## Reproduce

With the pinned GHC/Graal toolchains and repository prerequisites:

```sh
# Stock GHC is sufficient for the four local join/exception roots.
cabal run exe:thc-fixtures -- tuple-join --local
./gradlew tupleJoinFullCoreTest tupleJoinFullCoreDenseTest

# Complete installed Core adds original roundTo and the STM exception dependency.
cabal run exe:thc-fixtures -- tuple-join
./gradlew tupleJoinFullCoreTest tupleJoinFullCoreDenseTest
./gradlew test --tests thc.runtime.TupleJoinLoweringTest
python3 bin/test-tuple-inputs.py
python3 bin/test-audit-core.py
```

For an existing production acquisition, set `THC_TUPLE_JOIN_PACKAGES` to its
`packages.json`. The producer verifies and copies its original ghc-internal/rts
bundles, retaining complete module bodies and hashes; it does not reacquire the
compiler or rewrite Core. Without that variable the normal installed-Core
acquisition supplies those libraries. A thin installation is an explicit missing
prerequisite for the original-library run, not a substitute package.

The native oracle has 222 rows across six roots and covers integer boundaries,
recursive swaps, nested and empty tuples, lazy bottoms, Float/Double leaves,
ties-to-even/carry formatting, retry fallback and caught exceptions. Genuine
pre/post-Tidy exports are strictly audited. The complete-library matrix makes
3,552 native comparisons across both backends, interpreter/first-compiled entries,
and default/dense handoff modes. The explicitly local CI subset makes 2,368
comparisons and does not claim to run original library Core or STM retry. Additional controls
check reference release, logical shape mismatches and a normally returning empty
case. This removes specific compiler/AD audit frontiers, not all GHC/Tasty gaps.
