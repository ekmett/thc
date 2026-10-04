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

Fixture-free controls in `TupleJoinLoweringTest` and `EmptyArgumentRuntimeTest`
cover result scratch cleanup, an aliased destination, evaluated nonempty tuple
capture through a zero-arity join, and recursive lexical shadowing. They preserve
lazy lifted leaves through the first installed call in both backends. Genuine
GHC-exported tuple-result join capture remains unqualified; the existing
`RealCoreJoinTest` source controls return scalars.

An empty tuple `case` still evaluates its scrutinee. Original Tasty code contains
`case retry# state of {}` and equivalent bottoming tuple calls. Exceptions and
STM retry propagate normally. If malformed Core returns from that scrutinee,
the case raises the ordinary non-exhaustive-case fault. No bottom proof or
fabricated successful result is required.

Genuine GHC pre/post rich tuple-join input export, original installed
`GHC.Internal.Float.$wroundTo` hosting, and exported empty-case retry/raise
combinations remain unqualified. Fixture-free owners cover the runtime contracts.

[Binary sum arguments and PAPs](sum-inputs.md) and
[owned tuple closure/thunk captures](tuple-captures.md) use the existing typed
transport alongside joins. Both backends also support nonrecursive unlifted
aggregate lets and public host aggregate arguments/results. The host ABI preserves
logical tuple arrays, tagged sums, exact vector species and null State#/Void#
values. Recursive or lifted aggregate lets and global aggregate storage remain
unsupported.

[Reusable AST preparation](reusable-code.md) admits this synchronous typed
transport using predeclared physical slots and prebound destinations, without
executing guests or seeding profiles. Capture owners, sum projections, join
parallel moves and loan cleanup retain their ordinary checks. The native-cache
CLI's numeric input/output syntax is a separate presentation limit.
