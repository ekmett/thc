> **2026-10-06 constraint:** Native .hi support must not make the existing CBD
> decode pass more expensive. Preserving execution speed alone is insufficient.
> The proposed eager callable/nominal recovery-fact extension is rejected and
> frozen, entirely unshipped. The plan below records the earlier analysis; its
> CBD expansion is not authorized to proceed. Reconsider this direction before
> changing the established format or decoder.

# Native Core loading: representation recovery

Status: proposed design for [#1063](https://github.com/ekmett/thc/issues/1063).
The design and R1–R14 were recorded before the source audit. The implementation
plan is derived from that audit. This is a proposed target and a source review,
not a claim that the current native loader meets the target.

## 1. Design

THC consumes GHC's already checked Core. A native `.hi` reader recovers the
information needed to execute that Core and lowers into the same executable
representation as CBD. Types, kinds and coercions are intermediate information
for this recovery; they are not an invitation to reproduce GHC's typechecker.
CBD is an optional bundled form of Core. Each dependency independently selects
one input, `.hi` or CBD, with one owner and one shared linker/runtime.

### What survives

Execution needs value identity and lexical binding; value arities and call
structure; storage representations and logical aggregate layouts; constructor
identity, tags and fields; and evaluation/calling facts that affect sharing,
strictness, joins and continuations. Foreign ABI, native linkage, trusted
annotations and module provenance remain their existing execution contracts.
These are not a general source-type environment.

The native adapter retains type/kind binders, applications, relevant declaration
facts and coercion information only while a concrete execution question needs
them. Use lexical identities and capture-avoiding substitution. Query kinds to
recover representations, expose function structure when lowering applications,
and follow newtype/synonym/coercion information where it affects those answers.
Do not infer source types, solve class constraints, or validate already checked
Core by comparing all source types again.

Ordinary executable value binders and arguments have fixed representations in
GHC 9.14.1. Type instantiation does not require differently sized closure fields
or parameter frames. Result polymorphism and join results still require correct
call/continuation handling: an abstract result may be tail-forwarded without
being stored. Compiler-provided representation-polymorphic operations and
implicit workers must be instantiated, expanded or erased according to their
actual semantics. A symbolic result is not permission to invent a boxed layout.

Both adapters supply existing executable module, binding and constructor facts.
For dependencies, ask for the fact execution needs rather than assuming the
provider has a full source signature. Reuse concrete CBD facts and the existing
body/call metadata. Any extra persisted fact must have an identified consumer
and an actual example of information that cannot be recovered from those facts.
This design does not preselect a new CBD schema or a general representation
specialization subsystem.

### Loading flow and ownership

1. Select one source for a module from the ordinary package/dependency graph.
2. Validate its artifact identity, supported encoding and references; establish
   the module owner before recursively resolving dependencies.
3. Index declarations and retained bindings. Decode demanded bodies and recover
   their execution facts; skip unrelated, independently skippable interface data.
4. Admit, link and execute through the existing shared paths. The AST and bytecode
   backends receive the same contracts regardless of the original file format.

There is no runtime GHC helper, temporary CBD, CBD companion for `.hi`, or `.hi`
companion for CBD. A normal Cabal build is usable when its selected components
and dependencies actually retain the necessary Core and supply declared native
artifacts. A thin interface cannot supply arbitrary missing executable bodies.
Compiler-defined implicit workers/selectors use their genuine declaration facts
and shared lowering rules; ordinary library functions require genuine bodies.

Rich source types and coercion proofs may be retained separately for a future
debugger. Execution must not acquire or normalize that information solely to
make future debugging possible. Existing runtime-visible reflection values are
ordinary program values and must still execute normally.

### Laws

- Alpha-renaming a bound variable cannot change execution facts or behavior.
- Capture-avoiding substitution respects identity and composition. Where a
  representation query depends on explicit arguments, normalizing after their
  substitution agrees with substituting into the corresponding symbolic query.
- Erasing type/coercion syntax after recovering execution facts preserves value,
  effects, demand, sharing, exceptions and tail-call behavior.
- Equivalent genuine module inputs in `.hi` and CBD produce equivalent execution
  behavior, including when the other dependencies use the opposite format.
- Lazy lookup and eager discovery of the same available modules give the same
  ownership, binding identities and behavior. Lookup order is not an input.

## 2. Requirements

| ID | Requirement |
| --- | --- |
| R1 | Load retained Core directly from the pinned GHC `.hi` encoding in the JVM. No helper process or intermediate serialization is part of execution. |
| R2 | Every module independently uses `.hi` or CBD. Both dependency directions work without a same-module companion or format-specific dependency graph. |
| R3 | Recover runtime facts from the full legal input domain of the supported GHC version. Fixtures sample this contract; they do not restrict it to particular types, modules or expressions. |
| R4 | Preserve lexical binding and explicit type/coercion instantiation only as needed to compute execution facts. Use structural substitution; source type inference and repeated semantic typechecking are outside the loader contract. |
| R5 | Recover fixed storage at value-binding, argument, constructor and foreign ABI boundaries. Preserve logical zero-width values and nested tuple/sum/vector structure. Never guess a layout from an unresolved representation. |
| R6 | Support abstract tail results, joins, partial/over-application and mandatory compiler-provided erasure/expansion through existing runtime mechanisms. Do not add arbitrary per-instantiation closure/frame specialization. |
| R7 | Preserve execution-relevant identity, constructor layout, evaluation state, sharing, PAPs, exceptions, masking and continuation/FFI ownership across both backends and handoff modes. Equal storage alone does not establish identity or evaluation state. |
| R8 | Dependency lookup requests executable facts. A full source signature is not a mandatory provider interface. Before extending CBD, identify the exact missing runtime fact and why current metadata/body information cannot supply it. |
| R9 | Decode and retain only the interface information demanded by execution or required to safely traverse the encoding. Omit debug/source presentation, optimizer-only rules and recompilation bookkeeping from the execution model. Distinguish serialized syntax decoding from constructing a semantic type environment. |
| R10 | Share admission, linking, foreign ABI validation, package-native linkage and execution after decoding. Trusted annotation provenance remains checked at its existing boundary. Ordinary native library functions keep ordinary package linkage. |
| R11 | Reject malformed encodings, unsupported versions, invalid references, conflicting identities and genuinely absent required bodies/facts with useful owning-artifact diagnostics. Trust GHC typing after decoding; file validation is not a Core typechecker. |
| R12 | All required build inputs are explicit and reproducible. Ordinary Cabal outputs with retained Core are discoverable; pinned library acquisition remains available when installed libraries lack bodies. Format selection must not force unrelated library rebuilds. |
| R13 | Preserve lazy body loading, immutable input snapshots and context isolation. Release temporary type structures when no longer needed; cached declaration facts must have an execution consumer. Debug retention is optional and separate. |
| R14 | Qualify changes with focused evidence of these laws and contracts, including mixed formats and both execution backends where relevant. Reuse unchanged evidence, record cost, and add no broad fixture acquisition or full-suite run merely to validate this design. |

Scope authority: the maintainer's requirements in this discussion; GHC 9.14.1
Core semantics; the existing runtime ownership and ABI contracts. Relevant GHC
sources are `compiler/GHC/Core.hs` (representation and join invariants),
`compiler/GHC/Tc/Utils/Concrete.hs` (fixed representations and special Ids), and
`compiler/GHC/CoreToStg/Prep.hs` (mandatory preparation). These semantics are
inputs to the design, not implementation requirements imported from a fixture.

## 3. Source audit

Snapshot: `5f6ed0dc8edf6fcd2ae11083adb69bcccc84df90` plus the existing
native-foreign integration changes in `CoreUnitDirectory`, `Json`,
`ManagedExportPlan` and `CoreBoxedForeignDeclarations`. No production code was
changed for this audit. The audit inspected source consumers and the pinned GHC
serialization/semantics; it did not inspect fixture behavior or run builds.
Line numbers below refer to that snapshot. “Present” describes inspected code,
not successful end-to-end qualification. Implementation checkpoints below record
subsequent repairs without rewriting the audit's original evidence.

### Findings against the requirements

**A1 — R1/R12: direct decoding exists; normal driver publication still uses the
old conversion model.** `CoreHiReader.java:39–51` parses the pinned format in
Java; `CoreUnitDirectory.java:317–323` publishes its checked byte snapshot.
However, `CoreInterfaceSource.java:91–122` starts the GHC helper and writes a
temporary CBD. `Driver/Installed.hs:283–339` publishes that helper descriptor for
installed demand; `Driver/Project.hs:593–644` selects it or acquired CBD and
`:712–714` enables helper processes. `Project.hs:1879,2159` still discovers
exported `.cbd` files for source bundles. `THC/Interface.hs:308–312` explicitly
serializes recovered interface Core to CBD. These conversion paths are not native
loading. Ordinary Cabal output discovery/publication remains part of the work.
The native reader currently admits only 64-bit vanilla interfaces
(`CoreHiReader.java:44–48`); producer discovery must account for actual selected
interface ways rather than silently treating every Cabal artifact as vanilla.

**A2 — R2/R8: the native dependency contract imposes full types and the same
format.** `CoreUnitDirectory.java:155–159` rejects a CBD owner in the native
callback. `CoreHiModule.java:153–157,937–940` demands a complete imported `Ty`,
then `:1021–1033` substitutes it through an application. In contrast,
`Sources.binding/containsSymbol` (`CoreUnitDirectory.java:198–208`) and
`CoreUnitProgram.java:108–115` already resolve either selected format. This is a
native-adapter dependency, not a requirement of shared linking or execution.

**A3 — R5/R6/R8: substantial execution information is already persisted.**
`Compact/Core.hs:39–55,65–87,140–153` retains logical/physical shape, separate
evaluation state, binder and binding reps, join metadata and constructor fields.
`Plugin.hs:844–899` erases type syntax and casts after recording expression,
parameter and lambda-result reps. `CoreCompactRecords.java:153–201` decodes those
facts. `runtime/CoreInputCalls.java:46–79` and
`runtime/CoreDemandBindings.java:98–127` follow lambda inputs, aliases and PAP
suffixes. `runtime/GenericInputCall.java:40–90` uses the actual closure/root
contract for arguments, PAPs, over-application and results. Missing full source
signatures do not justify a schema extension. The follow-up in A13 identifies a
specific missing nominal representation fact, separate from these existing facts.

This is not a proof that imported result recovery is complete. The two input
resolvers above do not recover results, but a separate existing operation does:
`CoreRepresentations.java:59–94` resolves lambda `resultRep` through cycle-bounded
aliases and under-saturated application suffixes. Reuse that algorithm rather than
add another one. It works on available bindings and does not instantiate erased
type arguments. Native let/function/cast contexts may provide an expected result,
but `CoreHiModule.lower` propagates no expected-result fact. At `:1061` a case still
requires the scrutinee's source `Ty`.

A specific unresolved information flow is an imported function of type
`forall r (a :: TYPE r). (Int# -> a) -> Int# -> a` which tail-calls its callback.
Its stored parameters have fixed reps while its CBD result rep can be abstract.
A native caller instantiates a tuple result and scrutinizes it. The supplied
callback and caller can contain the needed result facts; the inspected adapter
has no path connecting them before case lowering demands the imported type.
The body identifies the formal callback and its tail application; the actual
callback supplies a concrete result when known. No inspected operation connects
those facts to native case lowering. This is an information-flow gap, not evidence
of a missing full interface payload or variable-sized stored layouts. It does not
prove that body analysis suffices for every abstract-result program.

The exporter removes ordered type binders and explicit type applications
(`Plugin.hs:847–850,860–883`). Its instantiated occurrence reps are enough for
already exported CBD callers, but abstract `Shape` fields retain neither binder
identities nor a substitution relation (`Compact/Core.hs:39–54,110–128`). A new
native caller therefore cannot instantiate that relation from `resultRep` alone.
Context does not always fill the gap: `IfaceCase` omits the scrutinee type
(`GHC/Iface/Syntax.hs:682–689`), and its enclosing result need not match the
scrutinized aggregate. An alternative's tag and arity do not supply its component
representations. The missing fact is the result-shape relation at an instantiation
and saturation boundary, including returned-function boundaries. Preserve that
execution projection where needed rather than infer it by walking arbitrary
bodies, branches and transitive calls. Its encoding remains to be designed;
physical PrimReps alone would discard logical aggregate boundaries.

There is also a distinct runtime boundary. `runtime/TypedInputs.java:174–178`
rejects an exact call with a concrete tuple destination when the callee root has
no tuple result shape. `GenericInputCall.java:63,88–90` subsequently consumes the
root's shape. Thus contextual result recovery alone does not establish aggregate
tail transport through an abstract root. A result/destination contract must reach
the actual producer while preserving tail handoff and continuation ownership;
removing the shape guard alone is not a fix. This is a conditional source finding,
not a reproduced execution failure.

**A4 — R4/R9/R11: some type machinery has real consumers, and some repeats GHC
checking.** `CoreHiTypes.java:140–161,186–223` already implements pi application
and binder-safe substitution. `:365–392,695–795` derives kinds, reps, levity and
aggregate layouts. Those are relevant mechanisms to reuse. In contrast,
`CoreHiModule.java:955–957` computes both coercion endpoints and compares the
whole source type before lowering a cast. Representation recovery should not
require rechecking that equality. This is not a reason to delete every equality
operation: nominal foreign-proof matching at `CoreHiModule.java:173–196` has a
separate admission consumer, and kind/coercion queries may need selected
endpoints to determine storage. Remove operations by consumer, not by name.

**A5 — R3: valid literal and expression forms are missing from the decoder.**
`CoreHiModule.java:701–731` handles tags 1–6 but rejects tag 0, `LitChar` in
`GHC/Types/Literal.hs:263`. Its expression decoder at `:638–688` also lacks
`IfaceTick`, encoded as tag 8 at `GHC/Iface/Syntax.hs:2837–2840`. Tick handling
must follow the execution/profiling contract of the supported tick, not assume
every tick is ignorable. These gaps follow from the production codec, independent
of any fixture. Full type/coercion/tick and axiom-operation coverage remains
unverified; this is not a claim that every other form is correct.

**A6 — R9/R13: body lowering is lazy, but native body decoding is eager and
repeated.** `CoreUnitDirectory.java:72,293` constructs a `CoreHiModule` for
publication admission; `:155` constructs it again for execution.
`CoreHiModule.java:75–116` parses declarations and every retained group/body into
objects, and `:212–216` computes all constructor layouts for metadata. CBD instead
indexes and decodes a demanded binding at `CoreCompactModule.java:58–63`.
`CoreHiReader.java:99–117` already indexes/skips independent interface sections.
Sequential traversal needed to find boundaries or startup obligations is not the
same requirement as retaining every body and semantic type tree. Annotation or
foreign inventory checks may legitimately demand a complete inventory; retain
that boundary without eagerly lowering unrelated code.

**A7 — R11/R13: byte snapshots are present, but the directory is not fully
immutable.** `CoreHiReader.java:40` clones native bytes; source readers are
context-owned; the WIP `CoreUnitDirectory.java:24` freezes component metadata.
However, `UnitRecord` retains list references (`:18–20`), the directory retains
`units` and a mutable flattened module list (`:43–50`), and getters expose them
(`:92–95`). A host can mutate enumeration after owner indexes are built. Freeze
those published collections at their owner. Separately,
`CoreFileMappings.java:138–139` uses a read-only CBD mapping: it does not itself
prevent external in-place writes. Immutable content-addressed publication must
be an explicit artifact contract; atomic replacement and in-place mutation are
different cases. Do not claim that a mapping alone is an immutable snapshot.

**A8 — R7/R10: reuse the shared admission and execution boundaries.**
`CoreModuleAdmission.java:27–64` owns export/import/archive/package admission.
`PackageScalarLinks.java:144–151` checks component identity/digests;
`runtime/CorePackageScalarForeign.java:64–98` validates ABI transport.
`CoreHiAnnotations.java:108–111` checks exact private producer/type/owner
identity. `CoreUnitProgram.java:95,230–246` supplies common constructor identity,
demand preparation and backend dispatch. These are existing owners, not reasons
for another native-`.hi` linker or FFI layer. Reuse is established from source;
full native preservation of sharing, masking and continuations is unverified.

**A9 — R11/R12: native verification is expressly unavailable.**
`CoreUnitDirectory.java:210–214` rejects native modules under `verifyArtifacts`;
`CoreModules.java:376` rejects verified loose native inputs. Raw input hashing
already occurs, but the existing option also asks for an offline execution
audit. Those are different guarantees. Reuse shared semantic/admission auditing
on native-decoded records; do not make the option succeed by silently omitting
its existing guarantees. A separate structural diagnostic gap occurs at
`CoreHiTypes.java:565`: an axiom branch index reaches a list lookup without an
owning bounds check. `CoreHiReader.java:184–188` admits only RealUnit, and the
native parser imposes a nesting limit of 256. These are explicit implementation
restrictions, not laws of GHC Core or its serialization.

**A10 — R6/R7: some reported runtime limits are narrower than ordinary loading.**
`runtime/Program.java:220–224,522–554` rejects unresolved join results only in
opt-in reusable-code preparation. Ordinary `CoreUnitProgram.java:246` constructs
the default program and does not enter that check. It is not an established
ordinary native-loading blocker. `runtime/BytecodeProgram.java:5539–5542`
rejects a bare unsaturated aggregate-field constructor, while saturated
applications use `:6203,6248` directly. Reachability of a legal demanded bare
occurrence after mandatory preparation is unverified. A3 identifies a separate
fixed-root result check on ordinary input dispatch that an abstract aggregate
forwarder would encounter. Keep source-established boundaries distinct from
executed failures; neither weaken the guards nor infer every path is reachable.

**A11 — R9/R13: unused source/optimizer data has concrete retention sites.**
`CoreHiModule.java:37–43,299–341,365–377` retains class functional dependencies,
minimal formulas, associated/method defaults and full pattern-synonym records.
No execution consumer for those fields was found. Implicit selectors consume
dictionary parameters, layout and selector index (`:395–404`); actual pattern
matcher/builder bodies have ordinary binding identities. Associated declarations
can still supply needed kinds, so discard unused fields rather than dropping the
whole declaration. At `:534–550`, public optimizer unfoldings are parsed into
expression objects and discarded; parsing can still modify constructor/control
inventory. Separate structural traversal from execution discovery.
`CoreHiAnnotations.java:36–99` also materializes all Reflection type trees and
opaque payloads. Unknown envelopes need no semantic type environment; complete
inventories required by a valid owned proof remain an actual consumer.

**A12 — R6: mandatory preparation rules already exist in the exporter but are
missing from the native adapter.** `THC/Wired.hs:37–67,87–101` implements genuine
compiler-owned identity operations (`lazy`, `noinline`, `noinlineConstraint`,
`nospec`), `runRW#` application/first-class lowering and the exact dead-binder
unsafe-equality case rule. `CoreHiModule.java:905–945,1042–1058` handles unary
class identity and selected wired constants, but the other operations fall
through to ordinary body demand. This is missing shared preparation semantics,
not a need to find or invent library implementations. Preserve the erasure,
remaining-argument and demand rules for all uses of the actual compiler identity.

**A13 — R4/R5/R8: nominal representation queries need information that ordinary
CBD constructor records do not retain.** Consider a native binder `x :: Provider.N`
where a CBD-only provider declares `newtype N = MkN Int#`. An ordinary `IfaceTyCon`
occurrence carries its name, promotion and syntax sort, not its kind
(`GHC/Iface/Type.hs:297–330,416–423`; `CoreHiTypes.java:819–822`). The CBD record is
keyed by `MkN` and contains its newtype flag and `IntRep` field, but no parent `N`
identity or equation connecting that identity to the field (`Compact/Core.hs:146–157`,
`CoreCompactRecords.java:331–347`, producer `Plugin.hs:681–698`). Entry types only
classify IO-unit/State-RealWorld/Other (`Plugin.hs:575–579`), and tag-family metadata
excludes newtypes (`:965–967`). No ordinary persisted `Provider.N -> IntRep` mapping
was found. Guessing a constructor name cannot answer the binder's storage query.
This establishes a needed nominal kind/representation fact; it does not establish
a need for full source signatures or select a serializer.

The general physical-storage query needs the nominal constructor's quantified
kind, instantiated by capture-avoiding substitution, with enough normalization to
expose a concrete `TYPE r` or `CONSTRAINT r`. GHC itself defines
`typePrimRep_maybe ty = kindPrimRep_maybe (typeKind ty)`
(`GHC/Types/RepType.hs:547–558,603–636`). Thus `N :: TYPE IntRep` answers the example
without a newtype RHS equation. Higher-kinded arguments and promoted constructors
need their genuine kinds. Synonyms inside queried kinds need expansion or an
already normalized fact; an unresolved representation family is not a license
to guess storage or solve source constraints.

Physical storage is not the full executable shape: `State# s` and `(# #)` both
have kind `TYPE (TupleRep '[])`, but one is a logical token and the other an empty
tuple. `Compact/Core.hs:38–49`, `Plugin.hs:458–474` and
`runtime/TupleShape.java:139–140` explicitly retain that distinction. Newtype or
synonym structure can therefore still be needed for logical aggregate children,
or selected coercion endpoint queries. Reuse existing field Shapes where they
answer the question. A kind fact does not replace logical shape, identity or
evaluation facts, and those facts do not require every source declaration field.

Constructor alternative binders raise a related, bounded question. `IfaceAlt`
stores names without binder types (`GHC/Iface/Syntax.hs:706`, `CoreToIface.hs:622`).
Their order is existential TyCoVars then worker arguments
(`IfaceToCore.hs:1770–1778`, `Core/Utils.hs:2433–2466`). CBD worker field reps derive
from `dataConRepArgTys`, whereas value arity includes existential CoVars and the
exporter only erases TyVars (`Plugin.hs:681–698,909–918`). Counts and field reps do
not by themselves identify each omitted existential's TyVar/CoVar category or
its dependent kind. A coercion is a zero-width logical value once classified;
source field transformations need not be reconstructed to obtain stored layout.
Determine which binder/kind facts a demanded RHS can supply before persisting
anything extra. Parameterized nominal queries and coercion uses remain to be
traced; the closed newtype example is evidence of a gap, not the proposed limit.

### Execution-fact sources traced in plan step 1

This follow-up inspected production code at `f1fe15703` with the same integration
WIP. It changed no runtime or fixture code and ran no builds.

| Query | Existing source | Remaining work |
| --- | --- | --- |
| Concrete value and worker field storage | CBD binder/field `Rep`; native local kinds and representation queries | Consume the existing facts directly; do not reconstruct source field types. |
| Saturated concrete function result | Lambda `resultRep`: `Plugin.hs:883`, `CoreCompactRecords.java:244`, `CoreRepresentations.java:263` | Reuse `CoreRepresentations.knownFunctionSignature/knownFunctionResult` (`:59–94`); connect it to native lowering. |
| PAP and returned closure | Existing result resolver follows cycle-bounded aliases and under-saturated application suffixes; literal returned lambda metadata and actual runtime closure/root | Keep static facts distinct from facts available only after executing the call. Preserve demanded lookup when reusing the resolver across modules. |
| Abstract tail result | Caller context and callback/body relation where available; eventual producing call | CBD erases the general result-shape substitution relation. Preserve the needed call projection and carry aggregate destinations through abstract roots (A3). |
| Nominal imported type representation | Quantified kind for concrete physical storage; separate logical shape where execution consumes it | CBD lacks the demonstrated `N` association (A13). Retain the needed kind/shape projection, not full declarations; encoding remains undecided. |
| Alternative binders and representation-relevant coercions | Worker layouts already persist; native RHS/type/coercion syntax supplies some local facts | Establish missing existential categories, dependent kinds and necessary coercion endpoints; no full declaration requirement follows yet. |
| Joins | Existing join arity/result metadata and local lexical context | Preserve outer abstract results; distinguish ordinary execution from opt-in reusable-code guards (A10). |

Fact recovery must preserve demand behavior. `CoreDemandBindings.java:10–11,36–87`
reserves a cold cell without decoding its body and checks occurrence facts when
that definition is demanded. Walking every imported body to recover source-like
signatures would trade the current format restriction for an eager dependency
walk and violate R13. Prefer existing metadata and local/context facts; any
necessary demanded analysis must retain the existing owner and cycle handling.

### Implementation checkpoints

- A7: published unit/dependency/module lists now snapshot their inputs; owner lookup
  and enumeration cannot diverge through mutable getters. Commit `91351ab91`.
  The new fixture-free regression failed on the original mutable dependency list;
  all six directory checks passed after the change (53 ms test execution, 1.22 s
  targeted compilation). Existing foreign integration WIP was excluded from the
  commit. No complete runtime qualification is implied by these directory checks.

### Requirement disposition

| Requirements | Source-audit conclusion |
| --- | --- |
| R1, R12 | Direct reader present; default discovery/publication and legacy helper retirement incomplete (A1, A9). |
| R2, R8 | Native ownership/signature contract conflicts; nominal kind/shape and abstract-result relations are missing, but full declarations are not justified (A2, A3, A13). |
| R3 | Partial legal-form support; character/tick holes, remainder not fully qualified (A5). |
| R4 | Useful substitution/recovery exists alongside redundant source-type checking (A4). |
| R5 | Existing storage facts cover concrete workers; nominal and existential recovery remains incomplete (A3, A4, A13). |
| R6, R7 | Shared runtime mechanisms present; imported abstract-result flow and specific boundaries need proof; preparation laws are missing (A3, A8, A10, A12). |
| R9, R13 | Independent sections can be skipped, but eager materialization, unused retention and mutable publication conflict with the target (A6, A7, A11). |
| R10 | Shared boundary ownership is present and should be preserved (A8). |
| R11 | Structural guards present; native offline audit absent and cast rechecking exceeds the contract (A4, A7, A9). |
| R14 | This task used source inspection only. Execution, memory and timing claims remain unverified; implementation evidence must follow the requirements. |


### Selected recovery contract

Reuse the existing `CoreHiTypes.Ty` lexical algebra for callable projections;
do not build a second symbolic Shape calculus. Preserve ordered `ForAll` slots
(including unused slots), binder category/dependent kind, value `Fun` boundaries,
variables and residual returned-function structure. Keep application/nominal
structure wherever substitution can expose a function or logical aggregate.
For a closed, exact, non-callable subtree, a constant kind/Shape leaf can replace
source syntax. This is an optimization, never a restriction to closed programs.
Keep evaluation state separate. Such a leaf is noninvertible: nominal identity
checks and coercion endpoint selection must receive their own demanded facts,
not inspect it as a source type. Do not compute a projection merely to immediately
query the original type again.

The adapter operations remain `piApply`, capture-avoiding substitution, kind and
representation queries, and the existing concrete alias/PAP resolver. Native and
CBD adapters supply these facts through `CoreUnitDirectory.Sources`, under the
selected module owner. Nominal facts retain quantified kinds, needed logical
views and ordered constructor alternative binder categories/kinds. Retain an
axiom endpoint structure only when a representation-relevant projection consumes
it; foreign nominal/provenance checks remain at their existing boundary.

Persist a callable projection in a leading binding extension beside the existing
host signature. Add a prefix-only fact read using the existing symbol index and
cursor; stop before the expression and keep the full-body cache cold. The reader
must still validate binding identity and module ownership. Define a canonical
extension order or reject duplicates in a prefix loop; index offsets continue to
point at the first prefix byte. Use existing **inline** Shape encoding for these
facts: ordinary expression Shape backreferences can point into prior bodies and
would defeat an independent prefix read. Put module-owned nominal facts in the
existing header-extension mechanism. Update codec capability/reserved-bit checks
with the new encoding. Do not add a linker, index or generic schema framework.

New readers continue accepting older CBD for its existing execution facts. If a
native consumer requires a fact the selected older artifact erased, report that
specific missing fact and require build-time republication or an independently
selected genuine `.hi`. Never fetch a same-module companion or run a helper in
execution. Reuse available retained interfaces when republishing; a metadata
change does not itself justify recompiling unchanged GHC libraries.

This contract specifies fact recovery. Aggregate destinations through abstract
roots remain a separate runtime change preserving ordinary returns, tail handoff
and resumable continuation ownership. A constant layout on every root is not the
replacement contract, and simply deleting its checks is insufficient.

## 4. Plan of attack

Complete the dependent recovery/transport steps in this order. Independent codec
coverage and publication-immutability repairs identified by the audit may proceed
in parallel: they need no decision about the projected schema. Each step preserves
the general contract; none establishes
a permanent “only these examples work” subset. Validation accompanies the owning
change. No implementation edits or new fixture work were made in this design task.

### 1. Resolve execution-fact flow at the existing lowering boundary

Addresses A2–A4/A13; R2/R4–R8/R13. Work in `CoreHiModule`, `CoreHiTypes`,
`CoreUnitDirectory.Sources` and the existing representation/call consumers.
For each lowering query, identify its source: local binder kind, expression or
lambda-result rep, constructor layout, explicit application information, enclosing
result context, or actual runtime closure contract. Keep local type scratch data
where it answers the query; remove the premise that every dependency exposes a
source `Ty`.

Resolve the abstract-result example in A3 before selecting a new representation
or serializer. Hand-trace the imported body and caller, including a case on the
returned aggregate, through argument/result destinations. A dynamic argument
check is not a proof of result recovery. If existing facts are insufficient,
record the precise missing execution fact and its consumer. Do not answer that
question by embedding a complete `.hi` inside CBD.

The fact-source table above completes the first bounded trace. Next resolve the
remaining general questions: logical shape through nominal/type arguments,
existential binder classification and dependent kinds, and abstract-result
instantiation. Quantified kinds settle concrete physical storage; reuse the
existing alias/PAP result resolver for facts it already retains. Specify only the information each
consumer needs. Trace aggregate destinations through ordinary call/return,
tail handoff and resumable continuations before changing the fixed-root checks.
The demonstrated nominal and abstract-result gaps warrant kind/shape and call
projections. Define them at the existing lexical substitution and representation
boundaries; do not import full GHC declarations or add an eager body-analysis
engine. Include existential binder category and dependent kind only where a
demanded alternative's representation query consumes them.

**Done when:** every required query at global application, case, constructor and
join lowering has an identified information source, including recursive aliases,
PAP suffixes and tail results. Any unresolved query is explicit and bounded; no
schema extension is justified merely by the absence of source signatures.

### 2. Make dependency recovery independent of the source format

Addresses A2/A3/A7/A8/A13; R2/R5–R8/R10/R13. Use the existing owner and binding/metadata
lookup. Change native lowering to consume the execution facts identified in step
1, supplying contextual result facts where necessary. Decode native types only
for native facts that need them; consume CBD's existing facts directly. Keep one
module owner and one shared admission/linkage path. Freeze directory collections
when published so all consumers see the same ownership graph.

Use the existing genuine runtime-support dependencies in their selected formats.
Do not build an all-native support-library closure to work around the resolver,
and do not synthesize missing ordinary function bodies. Unresolved but harmless
optimization information may stay conservative; required storage/ABI information
must be established at its consuming boundary.

**Done when:** dependencies work in both format directions, including calls,
constructors, returned closures and the result flow from step 1, without paired
artifacts or source signatures reconstructed from guessed types. The shared
runtime and FFI owners continue to consume the same contracts.

### 3. Reduce type work to its execution consumers and complete decoding

Addresses A4/A5/A10–A12; R3–R7/R9/R11. Keep the existing binder/substitution algorithms
and representation calculations. Separate representation queries from semantic
source-type comparisons, removing the cast-source equality check and other
checks only after tracing their actual consumers. Keep structural file checks
and the nominal/provenance checks required by public runtime boundaries.

Compare decoder coverage with the pinned serialization definitions, including
character literals, ticks and compiler-provided implicit forms. Discard or skip
optimizer/debug-only semantic data where the encoding permits it. Treat
mandatory expansion/erasure as shared lowering semantics, not per-symbol shims.
Apply the existing `THC.Wired` laws consistently to direct and first-class uses,
including `runRW#` and the exact unsafe-equality case. Remove the unused class,
pattern and optimizer-unfolding retention identified in A11. Restore owning
bounds diagnostics for structural references without adding a semantic linter.
Resolve the specific runtime reachability questions in A10 before changing any
runtime guard; a special opt-in path is not an ordinary loader failure.

**Done when:** required native syntax has a general lowering rule, all retained
semantic type operations have an execution consumer, and ordinary Core loading
does not perform a second source-type validation pass. Runtime limits that remain
must be identified by an actually reachable legal form, not a fixture whitelist.

### 4. Separate indexing/admission from materializing demanded bodies

Addresses A6/A7/A11; R9/R11/R13. Reuse the native reader's byte/section ownership.
Index binding extents and record startup facts without retaining the full body
object graph. If framing requires a sequential walk, make that an indexing walk.
Decode/lower requested bindings once per context and release transient type
structures when they are no longer needed. Preserve complete inventories where
existing foreign/admission rules actually consume them.

Keep immutable publication explicit for both file formats. Freeze in-memory
metadata; distinguish native byte copies from CBD's content-addressed read-only
mappings and their external-mutation contract. Do not introduce a global mutable
type or module cache.

**Done when:** publication and a one-binding load do not materialize unrelated
retained bodies, startup obligations are unchanged, and context teardown releases
its readers. Measure allocation/retention and elapsed loading for the same
ordinary module/entry before and after; do not use internal root counts as a
semantic test contract.

### 5. Connect normal Cabal artifacts and retire runtime conversion

Addresses A1/A9; R1/R2/R10–R12. Extend existing Cabal plan/registration discovery to
publish the genuine selected `.hi` paths, hashes, compiler/way/target identity and
native component inputs. Cover project components and installed dependencies;
retain pinned-library acquisition when installed Core is absent. CBD remains an
optional selected bundle, with existing cache evidence reused.

Implement native verification through shared decoded-record/admission consumers,
keeping raw artifact integrity distinct from complete execution auditing. Once
normal execution uses native decoding, remove `CoreInterfaceSource.convert`, its
runtime process permission and temporary-CBD ownership path. Keep explicit CBD
export/acquisition tools where the user chooses bundling; they are not runtime
`.hi` dependencies.

**Done when:** an ordinary retained-Core Cabal build can be selected and executed
without CBD production or a GHC process at runtime, mixed dependencies preserve
unit identities and declared linkage, and verification does not silently skip
native modules. Missing Core gets a direct actionable diagnostic.

### 6. Qualify the contract and publish the actual coverage

Addresses R3/R7/R14 and the unverified portions of A3/A8/A10. Extend the nearest
existing suites only after the preceding production changes identify the
behavior to check. Use a compact set of actual GHC-produced modules exercising
binder substitution/erasure, aggregate and zero-width transport, abstract tail
results, recursion/sharing, effects and mixed dependency directions. Expected
observable results come from native GHC or an independent model.

Run both backends and handoff modes for affected execution paths. Include
negative artifact/ownership/ABI checks at their owning boundaries. Derive fixture
inputs and outputs from the production workflow; keep every input in the existing
build graph, and reuse unchanged prepared artifacts. No new harness, all-library
rebuild or full suite is required merely to validate this design document.

**Done when:** each requirement has source and appropriately scoped behavioral
evidence, unresolved limits are stated honestly, and load/build/verification cost
is measured on comparable inputs. Publish a cohesive integration batch with its
actual feature claims; successful preparation or skipped checks are not execution
coverage.

## Deferred deliberately

Debugger-grade source types, source presentation and full coercion proof retention
are optional future work. A3/A13 establish missing nominal and abstract-result
relations; their general projections and encoding are still undecided. A full GHC declaration
payload, a new linker and arbitrary layout specialization are not selected by
this plan. They must not be introduced as prerequisites without a demonstrated
execution need.
