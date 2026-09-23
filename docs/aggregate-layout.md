# Recursive aggregate representation evidence

The exporter preserves logical unboxed tuple components and sum alternatives in
addition to GHC 9.14.1's physical `primReps` vector. Both runtime backends support
[exact tuple results](tuple-results.md) with scalar/reference inputs. Aggregate
arguments, join arguments/captures, ordinary captures, sums and unresolved layouts remain explicit boundaries. Exact tuple join results use local destination slots within the same root.
The metadata fixtures below test these boundaries independently of execution.

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
Additional controls load boxed tuple/unit/Solo and unlifted boxed product identities
on both backends. Boxed and unlifted boxed producers each store a lazy bottom in
their unused second field; observers return the first field at five integer
inputs on both backends and both export stages. These controls establish that
outer evaluatedness does not force the lifted payload. They add no unboxed
aggregate execution support.

`build/aggregate-layout/provenance.json` records full GHC `--info`, package
descriptions, compiler executable hashes, commands and environment overrides,
fixture/exporter/checker hashes, the built plugin, native objects/interfaces,
and both exported bundles. `checks.json` references that manifest's hash. Running
the checker again without `--prepare` verifies the recorded inputs and outputs
before checking metadata, so a stale bundle cannot certify an edited exporter.
These generated records are included in CI artifacts.

## Sum storage projections

A sum now records `tagSlot: 0` and `alternativeSlots` alongside its ordered logical
`alternatives` and exact physical `primReps`. The outer list follows constructor
order; each inner list maps that alternative's ordered physical leaves to
zero-based slots in the enclosing sum, excluding the tag from the payload.
For `(# Int# | Word# #)`, GHC gives `[WordRep, WordRep]` and `[[1], [1]]`.
For `(# State# s | (# #) #)`, the vector is `[WordRep]` and the map is `[[], []]`;
the logical State and empty tuple alternatives remain distinct. Float and Double
use distinct slots, as do lifted and unlifted boxed references. A sum constructor
record adds `sumArity`, obtained from its GHC TyCon's constructor family. This is
separate from the existing `arity`, which counts the single logical payload.
Constructor tags remain one-based; slot indices are zero-based.

The exporter calls pinned GHC 9.14.1 `ubxSumRepType`, `primRepSlot`, and
`layoutUbxSum`, matching the layout computation in `GHC.Stg.Unarise.mkUbxSum`.
It checks the resulting `slotPrimRep` vector against `typePrimRep_maybe` before
publishing projections. Address, narrow/wide integer, and fixed vector slots are
valid metadata even though those alternatives are outside current THC sum
execution support. The only partial `primRepSlot` case in this pinned API is
`BoxedRep Nothing`; it is rejected before calling either placement API.
Unknown physical representations, and abstract sum types with unknown logical
alternatives, retain `alternativeSlots: null`. A nested abstract tuple may have
known physical slots while retaining `components: null`; a physical projection
does not certify that missing logical structure. No printed type is parsed.

[SumLayoutAudit.hs](../compiler/test-fixtures/SumLayoutAudit.hs) and its native
driver check 130 values against an independent arithmetic model. Genuine exports
before and after Tidy retain 17 exact sum shapes, including nested sums/tuples,
newtype aliases, runtime/levity polymorphism, three-way sums, lazy boxed payloads,
and the zero-width distinctions above. Address/vector raising producers are
native compilation and metadata controls, never native execution claims.
An independent projection checker tests source field order, duplicate-slot
rejection, tag indexing, pointer levity, floating width, and null layouts.
`python3 scripts/check-sum-layout.py --prepare` freezes source, compiler, toolchain,
package, command and artifact hashes in `build/sum-layout/provenance.json`;
running it without `--prepare` verifies those hashes before checking the exports.
Normal test preparation and CI include these checks.

No sum capability is enabled. The auditor still rejects all retained-sum roots,
and `SumLayoutMetadataTest` checks strict load rejection on both backends and both
export stages. The direct-case control is optimized by GHC to ordinary scalar
Core and alone executes in THC. Future sum lowering must validate the complete
logical alternatives, family arity, physical storage classes and projection,
then construct and project the selected alternative using typed destinations.
It must preserve lazy reference leaves, validate tags, and keep unknown layouts
and unsupported input/capture boundaries explicit. This metadata does not define
a hardware call-register ABI or permit generic object-array sum payloads.
