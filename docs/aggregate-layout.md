# Recursive aggregate representation evidence

The exporter preserves logical unboxed tuple components and sum alternatives in
addition to GHC 9.14.1's physical `primReps` vector. This is metadata groundwork:
both runtime backends and the strict auditor still reject aggregate boundaries.
None of these fixtures adds a supported execution or benchmark claim.

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

Each node retains the exact vector from `typePrimRep_maybe`, when available.
GHC 9.14.1's `TupleRep` and `SumRep` callbacks internally call the partial
`runtimeRepPrimRep`; the nominally optional query can panic on a valid native
definition such as `forall r (a :: TYPE r). Box -> (# a, Int# #)`. For those
aggregate kinds, the exporter first requires `typeHasFixedRuntimeRep`. Otherwise
the enclosing vector is `null`, while independently known child vectors remain
available. A levity-polymorphic boxed leaf retains `BoxedRep Nothing`; its
enclosing unresolved aggregate remains conservative. The exporter never invents
a register vector or flattens known children across an unresolved one.

Child `evaluated` is true only when GHC proves that child's type is unlifted.
An outer evaluated tuple or sum does not make a lifted data value, function,
type variable, or family payload evaluated. The existing expression/lexical
evaluatedness rules still determine the outer record. Generic constructor
`fieldTypes` retain their representation-polymorphic worker types; instantiated
application results, case binders, and lambda boundaries carry the actual
logical layout. Ordinary scalar records keep the existing schema.

Run `python3 scripts/check-aggregate-layout.py --prepare` with the pinned GHC and
ghc-pkg to rebuild the plugin, compile the fixture natively without the plugin,
and check genuine optimized exports before and after Tidy. It checks eleven exact
recursive layouts, including mixed physical reps, lifted payloads, nested
polymorphism, and constructor-free newtype aliases, plus seven partial or unknown
layouts for abstract/family types. Controls cover a recursive scalar newtype,
a newtype over the zero-width state primitive, and the zero-width `Proxy#` primitive.
The existing aggregate frontier driver
runs this check during normal test preparation. `AggregateLayoutTest` checks
strict loading on both backends at both stages before invoking any guest input.

`build/aggregate-layout/provenance.json` records full GHC `--info`, package
descriptions, compiler executable hashes, commands and environment overrides,
fixture/exporter/checker hashes, the built plugin, native objects/interfaces,
and both exported bundles. `checks.json` references that manifest's hash. Running
the checker again without `--prepare` verifies the recorded inputs and outputs
before checking metadata, so a stale bundle cannot certify an edited exporter.
These generated records are included in CI artifacts.
