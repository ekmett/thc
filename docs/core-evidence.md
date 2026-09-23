# Core evidence and local joins

THC uses GHC 9.14.1's optimized Core directly. The exporter now carries structured evidence about runtime representation, already evaluated values, and local join points. Both backends consume the same evidence parser and join validator. Demand signatures remain diagnostic text: they do not authorize skipping evaluation.

## Representation and evaluatedness

Binder, binding and expression metadata can carry:

```json
{"primReps": ["IntRep"], "kind": "long", "evaluated": true}
```

`primReps` comes from GHC's `typePrimRep_maybe`. `kind` describes the value after evaluation; `evaluated` is a separate fact that the value is already in weak head normal form. The exporter uses GHC's type predicates and `exprIsHNF`, not printed type names.

| Kind | Evidence and current meaning |
| --- | --- |
| `long` | Exactly one supported signed or unsigned integer register, carried by a JVM `long`. |
| `address` | Exactly `AddrRep`; the current literal-address value remains an object. |
| `void` | No payload registers, including retained coercion tokens. |
| `data` | A single boxed representation with an actual boxed data type constructor. |
| `closure` | A single boxed representation with a function type after type abstraction erases. |
| `object` | A boxed carrier without the more specific data/function evidence. |
| `unknown` | No usable carrier proof; existing conservative lowering applies. |

Newtypes, type families and unary class representations do not establish a data-object layout. Unboxed tuples and sums remain `unknown`, including the empty unboxed tuple: an empty register list alone does not make an unsupported aggregate a void token. The shared parser checks exact carriers for positive primitive/reference proofs. Older schema-1 trees without metadata remain accepted with unknown evidence; unsupported operations and unresolved required representations still fail explicitly.

A successful case establishes WHNF for its binder and original scrutinee variable within the alternatives. Pattern fields gain the same fact only when they are unlifted or the saturated constructor worker requires them to be strict. Worker strictness marks must align exactly with worker fields, including coercions. Lazy lifted fields remain lazy. Lexical facts propagate by GHC variable identity, so shadowing does not leak them into another binding.

The runtime also accounts for its own storage. GHC may certify a constructor expression as WHNF while THC stores that expression in a CAF or recursive update thunk. Such storage loses the evaluated flag until forced; recursive captures retain their indirection identity. Lifted function and join formals remain conservative under the current calling convention. Diagnostic substitutions also lose the unavailable value's original proofs. A type proof must never bypass a thunk introduced by lowering.

Exact evaluated `long` captures use a fixed primitive `StaticShape` field without an object field or a primitive/object tag. Unknown captures retain the existing adaptive representation and recursive cells. Case binders, pattern fields, lets and result paths preserve usable primitive evidence through their respective backend lowering. This does not change the object-based inter-root call ABI or unbox arbitrary Haskell heap objects.

The separate application `knownWhnf` and `safeToEvaluate` certificates still govern construction of lifted arguments. `exprOkForSpecEval` excludes all enclosing recursive groups, following GHC's guarded-recursion rule. A false speculation certificate overrides the older WHNF fallback. Representation evidence does not turn an arbitrary lifted computation into a strict argument.

## Join points

A join binding exports `joinValueArity` and `joinResultRep`. GHC's original join arity counts type lambdas, so it cannot be used directly after erasure. The exporter takes that exact original lambda prefix, removes only type binders, and retains value/coercion parameters. `joinResultRep` describes what remains after the prefix.

This matters when a join returns a function. If a flattened RHS has two value lambdas but the join prefix contains one, the second lambda remains a returned closure. A zero-argument join consumes no lambda prefix and returns its whole RHS. The runtime never guesses adjusted arity from `idArity` or the diagnostic `info.joinArity`.

The shared validator requires every reference to a local join to be exactly saturated in tail position within its lexical region. It rejects escaping joins, partial/overapplication, use beneath a returned lambda, and calls with pending work inside the same region. Recursive and nonrecursive scopes differ; ordinary binders shadow join names. Mixed ordinary/join binding groups are currently unsupported rather than guessed.

Validated joins stay inside the current guest root. The AST uses a lexical loop region with an internal control-flow transfer; bytecode uses local branches and a loop backedge. Both evaluate operands into temporaries before replacing parameters, preserving swaps and mutually recursive transfers. A join region may itself appear within a larger non-tail expression: leaving that region resumes the outer continuation. Join transfers do not allocate closures or ordinary application packets.

## Checks and limits

[RepresentationAudit.hs](../compiler/test-fixtures/RepresentationAudit.hs) exercises actual optimized GHC Core: integer/address carriers, boxed/newtype/family distinctions, strict versus lazy fields, empty unboxed tuples, recursive joins, erased type parameters, and function-returning joins. [check-representation-metadata.py](../scripts/check-representation-metadata.py) checks those export facts. [check-speculation-metadata.py](../scripts/check-speculation-metadata.py) separately checks safe arithmetic, rejected failing computations and recursive dictionary guards.

[CoreProofJoinTest](../src/test/kotlin/thc/runtime/CoreProofJoinTest.kt) and [BytecodeCoreProofTest](../src/test/kotlin/thc/BytecodeCoreProofTest.kt) cover primitive captures, wide values, recursive/mutual transfers, parallel moves, outer continuations, malformed joins and introduced-thunk forcing. [RealCoreJoinTest](../src/test/kotlin/thc/RealCoreJoinTest.kt) executes genuine exported joins on both backends before and after compilation, including cold branches and full-width values. The fixture preparation script generates its input with source notes enabled regardless of the ordinary export setting; source attribution itself is described separately in [debug-locations.md](debug-locations.md).

These are bounded contracts for the supported Core subset. They do not add arbitrary unboxed aggregates, floating/vector carriers, representation-polymorphic operations, or small/big `Integer` and `Natural` layouts. They also do not make every demand signature a calling-convention guarantee. New uses of evidence must preserve the distinction between the GHC value, THC's storage of that value, and the point where evaluation has actually occurred.

The [compiled graph check](source-note-graphs/README.md) also guards the intended
result specialization. The AST uses direct comparisons for the constant result
kind: Kotlin's enum-switch mapping array survived partial evaluation and kept
irrelevant typed body paths alive in the first implementation. The final direct
comparisons collapse those paths; the separate [measurement](debug-locations.md#graph-check-and-typed-dispatch-repair)
records the effect with source notes enabled throughout.
