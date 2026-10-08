# Recursive aggregate representation evidence

The exporter preserves logical unboxed tuple components and sum alternatives in
addition to GHC 9.14.1's physical `primReps` vector. Both runtime backends consume
that evidence at the following boundaries; the linked contracts define the exact
supported leaves, shapes and ownership rules.

| Boundary | Unboxed tuples | Unboxed sums |
| --- | --- | --- |
| Guest function results | [Typed result completion](tuple-results.md) | [Tag/payload completion](sum-results.md) |
| Guest inputs and PAP prefixes | [Logical arguments, typed fields](tuple-inputs.md) | [Typed inputs and prefixes](sum-inputs.md) |
| Owned closure/thunk captures | [Flattened owned fields](tuple-captures.md) | [Owned tag/payload fields](sum-inputs.md) |
| Local join inputs and lexical captures | [Parallel same-frame moves](tuple-joins.md) | [Parallel tag/payload moves](sum-inputs.md) |
| Local join results | [Local typed destinations](tuple-results.md) | [Local tag/payload destinations](sum-results.md) |
| Saturated boxed-constructor fields | [Owned aggregate fields](aggregate-heap-fields.md) | [Owned aggregate fields](aggregate-heap-fields.md) |
| Polyglot Core entries | [Logical field arrays](site/embedding.md#load-a-core-entry) | [Tag/payload arrays](site/embedding.md#load-a-core-entry) |

Nonrecursive unlifted aggregate lets use typed frame locals. Recursive or lifted
aggregate lets and global aggregate storage remain unsupported. Exact sums with
two or more alternatives can occur inside recursive tuples and supported sum
payloads. Complete logical shapes containing known boxed pointers remain
transportable when unknown levity prevents a native sum layout. Genuinely unknown
RuntimeRep payloads, missing logical components and unsupported leaves reject.
AST aggregate-field constructor workers support partial application; bytecode
lowering requires direct saturation.
[Owned exact aggregate fields](aggregate-heap-fields.md) include the original
compiler's unpacked `BoxedRep` payload.

Boxed tuples such as `(Int, Int)`, boxed unit `()`, and `Solo Box` retain one
`BoxedRep (Just Lifted)` carrier with ordinary `data` evidence. They have no
`aggregate`, `components`, or `alternatives` fields. Boxed unit is an object,
not a zero-width token. Conversely, a product declared with `UnliftedDatatypes`
can be boxed but unlifted: it retains one `BoxedRep (Just Unlifted)` carrier and
ordinary `data` evidence. Its outer value is evaluated, while its lazy lifted
fields remain unevaluated. Only logical unboxed `(# ... #)` tuples use tuple
aggregate layouts; unboxed empty/singleton tuples retain that distinction.

A tuple adds `components`, and a sum adds `alternatives`, to the existing
representation record. Each element is another complete representation record:

```json
{
  "primReps": ["IntRep"],
  "kind": "unknown",
  "evaluated": true,
  "aggregate": "unboxed-tuple",
  "components": [
    {"primReps": [], "kind": "unknown", "evaluated": true,
     "aggregate": "unboxed-tuple", "components": []},
    {"primReps": [], "kind": "void", "evaluated": true},
    {"primReps": ["IntRep"], "kind": "long", "evaluated": true}
  ]
}
```

This describes `(# (# #), State# s, Int# #)`. The logical empty tuple, zero-width
state token, and integer remain three distinct components despite occupying one
physical register. Singleton tuples also retain their aggregate boundary. Sum
alternatives are in GHC type order, corresponding to one-based constructor tags;
each alternative may itself be a tuple or sum. Their individual physical vectors
are not offsets into the enclosing sum vector: GHC shares and reorders payload
slots and adds a tag. This schema does not prescribe a THC return ABI.

`GHC.Types.RepType.unwrapType` exposes aggregate representation through casts,
foralls, synonyms, and newtype aliases with GHC's recursive-newtype cycle check.
`splitTyConApp_maybe`, the unboxed tuple/sum TyCon predicates, and
`dropRuntimeRepArgs` then supply the ordered logical child types. No printed type
text or physical register count is used to infer shape. The representation view
is used only to detect aggregates: scalar newtypes still receive their original
conservative classification, and opaque type families are not expanded. If an
abstract type variable or opaque family exposes a `TupleRep` or `SumRep` kind,
the aggregate marker remains, but `components` or `alternatives` is `null`:
the boundary is known while its logical decomposition is unavailable. This is
different from `components: []`, which proves a logical empty tuple. Consumers
must reject unknown layouts for aggregate execution, including when the exact
physical vector contains zero registers or one register. RuntimeRep TyCon identity
establishes these boundaries; register counts never do.

GHC also gives known primitives such as `State# s` and `Proxy# a` the kind
`TYPE ('TupleRep '[])`. Their exposed primitive TyCons, including through newtype aliases, preserve the
ordinary `void` proof without an aggregate marker. An abstract type at that same
kind could instead be a logical empty tuple, so its null layout conservatively
retains the unsupported boundary until the logical type is known.

Each node retains exact native representation evidence when available.
GHC 9.14.1's aggregate callbacks call the partial `runtimeRepPrimRep`, so the
exporter queries concrete tuple/sum children recursively. Tuples concatenate
child vectors; sums use GHC's placement APIs only when every native slot class
is known. Abstract aggregate kinds still require `typeHasFixedRuntimeRep` before
querying GHC. A levity-polymorphic boxed leaf retains `BoxedRep Nothing`: it is a
known traced pointer, but supplies neither a native sum slot class nor a WHNF
proof. A sum containing it has `primReps: null` and `alternativeSlots: null`;
an enclosing tuple also has `primReps: null`. Complete logical shapes still
permit JVM transport without forcing that pointer. A genuinely unknown RuntimeRep
leaf has no known carrier and remains unsupported.

Child `evaluated` is true only when GHC proves that child's type is unlifted.
An outer evaluated tuple or sum does not make a lifted data value, function,
type variable, or family payload evaluated. The existing expression/lexical
evaluatedness rules still determine the outer record. Generic constructor
`fieldTypes` retain their representation-polymorphic worker types; instantiated
application results, case binders, and lambda boundaries carry the actual
logical layout. Ordinary scalar records keep the existing schema.

## Sum storage projections

A sum with known native layout records `tagSlot: 0` and `alternativeSlots` alongside
its ordered logical `alternatives` and exact physical `primReps`. The outer list
follows constructor order; each inner list maps that alternative's ordered physical leaves to
zero-based slots in the enclosing sum, excluding the tag from the payload.
For `(# Int# | Word# #)`, GHC gives `[WordRep, WordRep]` and `[[1], [1]]`.
For `(# State# s | (# #) #)`, the vector is `[WordRep]` and the map is `[[], []]`;
the logical State and empty tuple alternatives remain distinct. Float and Double
use distinct slots, as do lifted and unlifted boxed references. A sum constructor
record adds `sumArity`, obtained from its GHC TyCon's constructor family. This is
separate from the existing `arity`, which counts the single logical payload.
Constructor tags remain one-based; slot indices are zero-based.

For concrete native slots, the exporter calls pinned GHC 9.14.1
`ubxSumRepType`, `primRepSlot`, and `layoutUbxSum`, matching
`GHC.Stg.Unarise.mkUbxSum`. The `slotPrimRep` vector supplies `primReps` and is
checked before publishing projections. Narrow/machine integral leaves use Word
slots; explicit Int64/Word64 leaves use Word64 slots, and their merge selects
Word64. Addresses share GHC word slots; vectors share only an exact species.
`BoxedRep Nothing` is excluded from these partial native placement APIs and
retains null native layout fields.

THC derives canonical JVM slots separately from the complete logical shape.
Lifted, unlifted and unknown-levity pointers share traced slots, preserving the
order and multiplicity of pointer fields within each alternative. These slots
supply no WHNF proof. Integral values share Long slots; Float, Double, managed
addresses and each vector species have separate storage classes. Recursive
projections place nested active payloads into that storage. Concrete native
`primReps` and `alternativeSlots` remain unchanged.

Missing native layout is accepted only for known-pointer unknown levity with a
complete logical shape, including nested sums. Abstract alternatives and tuple
`components: null` still reject, even when their native vector is known.

[Sum lowering](sum-results.md) validates complete logical alternatives, family
arity, native evidence and JVM projections before using typed destinations.
This metadata does not define a hardware call-register ABI.

JVM tuple flattening recursively expands a supported sum's storage slots,
while the logical tree still distinguishes sums, tuples, erased State tokens and
empty tuples, including sums nested inside sum payloads. The genuine
`VirtualRegWithFormat` worker has two logical fields but three JVM fields:
Long tag, shared Long payload, then the Format reference at offset 2. See the
[aggregate constructor contract](aggregate-heap-fields.md).

Public synthetic CBD controls qualify cold malformed-aggregate demand, the first
installed good call and diagnostic validation of unused logical host formals.
Fixture-free controls retain proof ownership, logical shape distinctions and result
loan cleanup. Genuine pre/post GHC multiple-outstanding tuple/forwarding and
compiled tuple/sum transport conjunctions remain unqualified by these controls.
