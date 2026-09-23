# Exact empty tuple inputs

Both backends accept ordinary function arguments whose exact GHC proof is
`aggregate: unboxed-tuple`, `kind: unknown`, `components: []`, `primReps: []`.
These are `(# #)` values. Boxed `()` remains one lifted reference, while `State#`
remains a scalar void value with its existing convention and primitive contracts.
Nested empty tuples, singleton tuples containing State, nonempty aggregates,
sums, unresolved shapes, aggregate captures, ordinary aggregate let bindings and
aggregate join arguments remain outside this slice.

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

`EmptyTupleInputAudit.hs` and `prepare-empty-tuple-input-audit.py` supply genuine
pre/post-Tidy exports and a native oracle. Runtime controls cover logical PAPs,
overapplication, effect/throw ordering, zero-storage proofs, mixed targets,
tail recursion and primitive contract rejection on both backends. Sequence's
actual fold specializations use empty first parameters; other Sequence gaps must
remain separately reported rather than counted as supported by this feature.

Production [graph evidence](../bench/experiments/empty-tuple-inputs/README.md)
records the exact captured runtime and native inputs. All 24 pre/post,
AST/bytecode, inline/residual controls pass. Inline calls retain no guest input
layout reads, tuple carrier or packet allocation; residual packets contain only
the header and scalar fields. This includes a scalar-only control and seven
independently varied pairs checked by both graphs and instrumented JVM tests.

The same evidence directory records a separate genuine Sequence comparison.
`sequenceBuild`, `sequenceEnds`, `sequenceAppend` and `sequenceLazyPayloads`
strictly load after the empty-formal change and match all 152 selected native rows
on both backends in both handoff modes. It uses equivalent remote GHC exports,
verified against their recorded module hashes and original native TSV, and loads
the parsed Core directly without serializing a merged request. Compiled warm
rows and post-cold replay retain host/original/active guest validity and compiled
guest entry on every measured row. No settling calls or recovery loop were added.
`sequenceSplit`, `sequenceIndexUpdate` and `sequenceAggregate` retain their
separate missing-definition and constructor-proof frontiers. These four newly
accepted entries do not establish that the unrelated Sequence views first-call
host-linkage gap is fixed.
