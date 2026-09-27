# Typed results and tail cycles

Both backends preserve typed values through admitted execution paths and
close tail cycles at matching active roots. A cycle can reenter an interior
root, such as `A → B → C → D → E → C`, rather than always unwinding to the
outer trampoline.

## Typed execution

`Expr` has typed entry points for `Long`, `Float`, `Double`, closures,
constructors and managed addresses, backed by a Truffle type system. Case alternatives invoke the
selected child's typed entry directly and profile which alternative matches.
Primitive scrutinees, unlifted bindings, captured fields, and constructor fields
can travel through primitive frame slots and properties without an Object-array
bridge. AST constructor construction initializes the final fields directly.

A typed forcing node that encounters a thunk evaluates it once and remembers
that it needs the generic forcing path. Widening consumes the value saved in
`UnexpectedResultException`; it never retries the expression. Each cloned node
owns its widening state. This matters because repeatedly throwing that exception
would repeatedly invalidate compiled code.

Lambda bodies retain their primitive path up to Truffle's Object-valued root
return. Applications still cross Truffle's Object-array calling convention; an
inlined call can eliminate that boundary. The tests cover closed and captured
lambdas, partial and overapplication, changing captures, cold case alternatives,
and a polymorphic lifted result changing from data to a closure.

`Int#` and `Word#` retain their machine arithmetic semantics. Typed transport
does not replace GHC's `Integer` or `Natural` representation or promise a
small/big-number widening scheme.

## Closing a tail cycle

Every root has a nonempty mask in a 64-bit bloom filter. A non-tail call starts
fresh ancestry. A tail call can extend the active chain when
`(seen & targetMask) != targetMask`; adding the target mask then sets at least one
previously absent bit. The chain therefore cannot keep growing indefinitely.
A bloom hit throws a tail transfer carrying the target and its complete argument
packet. Collisions can cause an early transfer, but cannot hide a repeated root.

A root consumes a transfer only when the target has the same body identity.
Clones retain that identity. Other roots rethrow it. In
`A → B → C → D → E → C`, E and D unwind, C restores the new arguments and captures,
and C takes its loop backedge. A and B remain suspended. C keeps its original
`A | B | C` ancestry; D and E do not accumulate in the filter across iterations.
A subsequent transfer to B can unwind to B and close the loop there instead.

The bytecode implementation catches a matching transfer at a tail application,
restores the root's bytecode locals, and branches to a Bytecode DSL loop backedge.
Its existing direct-self path remains packet-free. Non-tail dispatch protects
pending work, including the first stage of overapplication. When a conservative
bloom hit has no matching active root, the outer trampoline resets ancestry and
continues the call.

## Checks and boundaries

[`TailCycleTest`](../src/test/kotlin/thc/TailCycleTest.kt) covers two-root and
interior cycles, outward retargeting, changed captures, PAP prefixes,
overapplication, pending non-tail work, cloned targets and bloom collisions.
Its collision-free controls require matching-root reentry with zero outer
trampoline iterations; stack safety alone would not establish that behavior.
[`TypedExecutionTest`](../src/test/kotlin/thc/runtime/TypedExecutionTest.kt) checks
that widening consumes an already-produced value without reexecuting its child.
[`TypedApplicationTest`](../src/test/kotlin/thc/TypedApplicationTest.kt) covers
typed paths through ordinary application.

A tail-transfer protocol does not make arbitrary non-tail recursion stack-safe.
[Async-enabled AST execution](async-exceptions.md) has a separate saved-continuation
driver and explicit transaction limits. Typed tuple, sum and vector inputs and
results also retain their distinct [layout and ownership contracts](aggregate-layout.md).

The [typed-tail investigation](../research/typed-tail-checkpoint.md) retains
the original graph comparisons, test counts and measured Map results. Those
captures describe their frozen builds, not the current cost of these protocols.
