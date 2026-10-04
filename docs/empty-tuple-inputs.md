# Exact empty tuple inputs

Both backends accept ordinary function arguments whose exact GHC proof is
`aggregate: unboxed-tuple`, `kind: unknown`, `components: []`, `primReps: []`.
These are `(# #)` values. Boxed `()` remains one lifted reference, while `State#`
remains a scalar void value with its existing convention and primitive contracts.
The [typed tuple input contract](tuple-inputs.md) also covers nested and nonempty
tuples, including singleton State components. [Owned tuple captures](tuple-captures.md),
[tuple join arguments](tuple-joins.md) and [binary sum inputs/captures](sum-inputs.md)
have their own typed paths. Nonrecursive unlifted aggregate lets use typed
locals. Recursive/lifted aggregate lets, global aggregate storage and unresolved
shapes remain unsupported. The [host ABI](site/embedding.md#load-a-core-entry)
transports logical empty and nested tuples.

Fixture-free `EmptyArgumentRuntimeTest` controls cover empty arguments in calls,
PAPs, overapplication and ordinary self/mutual tail transfers, including distinct
empty positions alongside independent scalar payloads. They observe values,
operand effects, lazy tuple results, released loans and first installed calls in
both backends. Genuine GHC-exported ordinary empty-argument calls, PAPs and
overapplication remain unqualified; the exported empty-tuple local-join control
is a separate contract.

An immutable argument layout separates logical arity from scalar payload offsets.
An empty argument consumes one logical parameter but contributes no array element,
frame slot, or generated handoff field. A PAP records its logical supplied count
independently of its scalar prefix: supplying only `(# #)` advances the former
while leaving the latter empty. Exact calls, underapplication, overapplication,
strictness marks, tuple-return callers and tail restoration use the same mapping.
Normal scalar calls retain the existing paths and introduce no wrapper allocation.

Zero width does not remove evaluation. The argument expression executes through
its tuple writer into an empty destination at the original operand position,
before later operands, PAP publication or an input loan. Effects, exceptions and
nontermination therefore remain observable even when the formal is unused.
Lifted scalar operands remain lazy unless the existing entry contract forces them.
A used empty formal is a proof-bearing tuple alias with no storage.

Known global/local lambdas and PAP prefixes receive positional aggregate proof
checks during loading. Dynamic targets receive the corresponding logical mask
check before application. An empty tuple cannot masquerade as a State token or a
boxed unit. No layout is inferred from an empty physical register vector alone.

The optional AST input handoff stores only the remaining scalar fields in its
existing generated `StaticShape` class. Input loans and tuple result loans have
separate ownership; callee entry releases its input after copying durable locals,
before executing guest code. Calls with empty inputs use the existing tail
trampoline and mapped frame restoration; scalar direct-self paths are unchanged.
Residual calls retain the Truffle object boundary, not a hardware tuple register ABI.
