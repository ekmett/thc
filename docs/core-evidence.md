# Core evidence and local joins

THC uses GHC 9.14.1's optimized Core directly. The exporter carries structured evidence about runtime representation, already evaluated values, requested call-by-value entry contracts, caller-side demand permissions, and local join points. Both backends consume the same evidence parser and join validator. Printed demand signatures remain diagnostic text: they do not authorize skipping evaluation.

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

Aggregate records additionally retain [recursive logical components and alternatives](aggregate-layout.md), independently of their physical register vector. GHC's representation view exposes aggregate newtype aliases without promoting scalar newtypes to data/closure proofs. Unresolved aggregate runtime representations stay `null`; lazy lifted children stay unevaluated. These records drive [bounded tuple-result lowering](tuple-results.md); unsupported aggregate arguments, joins, captures, sums and unresolved layouts still reject.

A successful case establishes WHNF for its binder and original scrutinee variable within the alternatives. Pattern fields gain the same fact only when they are unlifted or the saturated constructor worker requires them to be strict. Worker strictness marks must align exactly with worker fields, including coercions. Lazy lifted fields remain lazy. Lexical facts propagate by GHC variable identity, so shadowing does not leak them into another binding.

The runtime also accounts for its own storage. GHC may certify a constructor expression as WHNF while THC stores that expression in a CAF or recursive update thunk. Such storage loses the evaluated flag until forced; recursive captures retain their indirection identity. Lifted function and join formals remain conservative unless their entry contract has been enforced. Diagnostic substitutions also lose the unavailable value's original proofs. A type proof must never bypass a thunk introduced by lowering.

Exact evaluated `long` captures use a fixed primitive `StaticShape` field without an object field or a primitive/object tag. Unknown captures retain the existing adaptive representation and recursive cells. Case binders, pattern fields, lets and result paths preserve usable primitive evidence through their respective backend lowering. This does not change the object-based inter-root call ABI or unbox arbitrary Haskell heap objects.

The separate application `knownWhnf` and `safeToEvaluate` certificates still govern construction of lifted arguments. `exprOkForSpecEval` excludes all enclosing recursive groups, following GHC's guarded-recursion rule. A false speculation certificate overrides the older WHNF fallback. Representation evidence does not turn an arbitrary lifted computation into a strict argument.

## Call-by-value entry contracts

A binding and its leading flattened lambda metadata can carry `entryStrict: [false, true]`. A true position requests a calling convention: that argument must denote a direct WHNF value when the body starts. It is not a claim that the original argument expression was already evaluated. The exporter leaves `rep.evaluated` unchanged.

GHC 9.14.1 normally leaves current-module CBV marks empty until Tidy, just after THC's main export point. For eligible worker and join definitions, the exporter calls the pinned compiler's own `GHC.Core.Tidy.tidyCbvInfoTop` selection logic. This checks worker/join eligibility, strict-and-used demand, supported register representations, dead-end exclusions, join prefixes and arity trimming. It does not interpret the printed demand strings or promote every strict function. `entryStrictSource` distinguishes `ghc-tidy-proposal`, actual existing `ghc-id` marks, and `none`; `info.cbvEligible` and `info.cbvMarks` retain eligibility and the original marks separately.

The pre-Tidy call deliberately supplies an empty native boot-export exclusion set. Its result proposes a THC-internal ABI, which THC must enforce on every entry route; it does not alter the native GHC ABI or promise that a boot-exported native function uses the same convention. Post-Tidy and imported-interface exports use existing Id marks without deriving new ones. The plugin never rewrites the executable Core tree or changes GHC's native compilation.

Positions align with retained value/coercion parameters. GHC's `isId` includes coercion variables and equals `not . isTyVar`, so erased type parameters consume no mark while retained zero-width coercions do. The lambda array is padded with false through the full flattened lambda. A join returning a function can mark only its original join prefix, leaving the returned lambda suffix unmarked. A rare binding without a leading lambda does not lend its contract to an unrelated nested lambda; runtime lowering keeps that case conservative.

Each guest target records an immutable copy of its entry marks and argument offset. Shared direct and indirect application enforce marked arguments after saturation, including supplied PAP prefixes, the saturated portion of overapplication, and normal host entry. Creating or observing a partial application remains lazy. A known saturated call can lower a marked operand directly, avoiding the suspension that a lazy operand would need. Dynamic calls enforce the same obligation at entry. Bytecode self-loop transfers and local joins enforce it when moving arguments; ancestor tail reentries receive packets already checked by the call machinery. Only then may the body treat marked lifted formals as evaluated.

Native GHC additionally requires an evaluated pointer to be properly tagged. THC's corresponding contract carries the direct WHNF object; it does not tag JVM references. The optimization can eliminate repeated thunk resolution where the calling convention is known. It does not remove every constructor/layout check, specialize arbitrary polymorphic arguments, or eliminate the object ABI at residual call boundaries. PAP laziness, failures and sharing retain their existing semantics.

## Caller-side demand permissions

Application metadata can carry `callDemand: {arity: N, strictArgs: [...]}`. The
exporter obtains these marks from GHC's original structured `DmdSig`, using its
own saturation threshold and strict-and-used predicate. Coercions keep their
positions; PAP prefixes, absent arguments, surplus arguments and `lazy`
barriers remain conservative. A marked argument may be evaluated when this
application executes. It does not strengthen the callee's entry convention or
claim that its formal parameter was already evaluated.

Both backends validate this optional evidence. Lowering is opt-in with
`-Dthc.callDemands=true`, read once per program; the default remains off because
of the measured inlining regression. [The compiler protocol](../compiler/README.md)
describes the exact rules, and [the demand experiment](demand-probe.md) records
the results. Genuine GHC fixtures check precise-exception branch/scrutinee
weakening as well as strict pure calls. Unsupported IO in those audit fixtures
is inspected as metadata, never executed by THC.

## Physical recursive cells

Whether a local slot can contain a `RecCell` is a separate lowering fact from both type and WHNF. Ordinary parameters, pattern fields, nonrecursive lets and captures of published values do not need recursive-cell resolution. A successful case or force can establish WHNF without changing the physical identity of an older recursive capture.

While a recursive group initializes, all RHS closures capture the group's cells by identity. After every RHS is initialized, publication replaces the current frame's group slots with their values. The let body and closures created afterward use those published values directly; closures created during initialization retain their original cell-bearing captures. Successful forcing updates the appropriate mutable binding link or cell contents while preserving aliases. Publication never replaces a cell early just because another RHS forced it.

Both backends preserve this distinction in their local/capture metadata. Bytecode omits `ReadCellIfNeeded` on proven ordinary values. A proven primitive capture uses the direct primitive path only when the physical source cannot still be a recursive cell. This removes avoidable indirection checks without turning recursive knots into copied values or weakening lazy sharing.

## Precise reference storage

Constructor metadata also carries `fieldTypes`, aligned with `fieldReps`, `strictFields` and `fieldLifted`. Each record uses the same structured kind and evaluatedness proof as an expression. Its type comes from the constructor worker's actual field type; its evaluated flag requires a strict worker field or an unlifted representation. The runtime checks that these records agree with the existing storage and evaluation obligations before using them.

An evaluated data field can be a final Java `DataValue` field, and an evaluated function field can be a final `Closure` field. A lazy field of either Haskell type still uses `Object`, because it can contain a thunk. A strict polymorphic field also stays `Object`: WHNF alone does not identify its carrier. Thus `Map`'s strict left and right children can have concrete reference fields while its polymorphic key and value retain their general representation. The existing primitive size field stays `long`.

Closure environments apply the same rule to proven evaluated data, function and managed-address captures. Precise reference captures need neither an adaptive primitive arm nor a tag. Captures that can hold a recursive cell remain generic even if forcing has established WHNF for the cell's contents. Older exports without these proofs retain the previous storage layout.

This uses Truffle's supported `StaticShape` property types. With class-owned
layouts enabled, an exclusively owned generated class identifies its constructor.
Shared carrier classes retain an explicit layout field and comparison; Truffle's
array strategy can share one class between shapes.

The same reference proof is restored at function entry and after self, ancestor
and local-join transfers. A checked `DataValue`, `Closure` or `LiteralAddress`
cast gives Graal a concrete reference type before the value reaches its local
slot. This matters when the value arrived through the generic call packet. The
cast permits generated subclasses and rejects null; it does not assert an exact
generated storage class. Lazy formals and cell-bearing captures still take the
generic path. Primitive `long` restoration remains separate.

## Owned static storage

Class-owned constructor layouts are enabled by default
(`-Dthc.classOwnedLayouts=true`). Each layout reserves its generated carrier class
before publishing values. An exclusive carrier uses the fieldless `DataValue`
base and obtains its cold layout metadata through a `ClassValue`; hot paths use
an expected-class check. If a carrier is already shared, construction switches
to `LayoutDataValue` with an explicit owner field. See the
[class-owned layout measurements](../bench/results/class-owned-layouts/).

On the pinned JVM, StaticShape's field strategy generates a storage subclass and
factory with ASM and defines them through runtime class loaders. Each declared
property becomes a real primitive or reference field. A shared `StaticProperty`
records its type, shape and JVM field offset; typed accessors use typed Unsafe
loads/stores, and constant descriptors let Graal fold the metadata. The field
strategy adds no backing arrays or shape pointer to each storage object beyond
fields requested by the language and its base class. JVM alignment still decides
the actual object size.

StaticShape shares class loaders but does not intern equal field layouts. A
reusable handoff design must intern physical representation vectors itself;
mutable handoff fields must also be registered as non-final. Existing immutable
constructor/capture properties use final fields and must be initialized once
before escape. [Truffle's Static Object Model guide](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/StaticObjectModel/)
describes that contract. Other storage strategies may share classes, so physical
layout and nominal constructor identity remain distinct concepts.

`-Dthc.staticShapeUnchecked=true` is an opt-in experiment that disables Truffle's storage-class and shape checks for THC-owned constructor and capture layouts. It defaults to false; checked storage remains the default. `engine.ForceStaticObjectSafetyChecks=true` overrides the experiment and restores Truffle's checks.

Each layout owns a private allocation key and a private generated factory. Allocation passes that key to the storage superclass, which checks its identity before entering `ValidatedStorage` and ultimately `Object.<init>`. A failed check therefore cannot produce a finalizable object that a subclass could resurrect. The compiled constructors were inspected: validation precedes superclass initialization and the final layout assignment, with no alternate constructor bypass. The key is neither exposed nor retained in each value, and the common base adds no fields or finalizer.

Operations on existing storage retain explicit owning-layout and field-index checks. This identity test is essential for array-based storage, where different layouts can share a generated Java class. Fresh initialization uses only the owning factory's storage. Precise reference writes still perform `StaticProperty`'s assignability check independently of the storage-check flag; primitive and zero-width field validation also remain. Captured aliases and recursive cells keep their existing identities and representations.

[StaticShapeSafetyTest](../src/test/kotlin/thc/runtime/StaticShapeSafetyTest.kt) covers checked and unchecked field-based and array-based storage, shared carrier classes, forged constructor/subclass/factory keys, wrong layouts and indices, invalid field values, and the engine override. This establishes the ownership and access invariants; the experimental flag itself makes no performance guarantee.

`-Dthc.constructorClassIdentity=true` separately experiments with constructor
matching through the generated Java class. It defaults to false. A class can
stand for a constructor only while it has exactly one owning layout and that
layout's factory always produces the same class. Both facts have invalidatable
Truffle assumptions. Registering a second owner or observing another factory
class invalidates the relevant assumptions before publishing a value; matching
then falls back to the owning-layout test. Layouts register even when the option
is off, so changing the option or creating another context cannot hide owners.
The global `ClassValue` registry holds opaque tokens without retaining layouts
or contexts, and exposes no operation that can reset ownership history.

This lets existing exact-class profiles eliminate a redundant constructor
test when the proof holds. It does not put a tag in JVM references. In
particular, array-based storage can share a carrier class between constructors
and must retain the layout comparison. [ConstructorClassIdentityTest](../src/test/kotlin/thc/runtime/ConstructorClassIdentityTest.kt)
checks both storage strategies, compiled-code invalidation, mixed option modes,
cross-context registration and cold case alternatives on both backends.

## Join points

A join binding exports `joinValueArity` and `joinResultRep`. GHC's original join arity counts type lambdas, so it cannot be used directly after erasure. The exporter takes that exact original lambda prefix, removes only type binders, and retains value/coercion parameters. `joinResultRep` describes what remains after the prefix.

This matters when a join returns a function. If a flattened RHS has two value lambdas but the join prefix contains one, the second lambda remains a returned closure. A zero-argument join consumes no lambda prefix and returns its whole RHS. The runtime never guesses adjusted arity from `idArity` or the diagnostic `info.joinArity`.

The shared validator requires every reference to a local join to be exactly saturated in tail position within its lexical region. It rejects escaping joins, partial/overapplication, use beneath a returned lambda, and calls with pending work inside the same region. Recursive and nonrecursive scopes differ; ordinary binders shadow join names. Mixed ordinary/join binding groups are currently unsupported rather than guessed.

Validated joins stay inside the current guest root. The AST uses a lexical loop region with an internal control-flow transfer; bytecode uses local branches and a loop backedge. Both evaluate operands into temporaries before replacing parameters, preserving swaps and mutually recursive transfers. A join region may itself appear within a larger non-tail expression: leaving that region resumes the outer continuation. Join transfers do not allocate closures or ordinary application packets.

A transfer has no returning value. Its result therefore cannot weaken the WHNF
proof of the branches that actually leave a join region. An actual lazy return
still prevents that proof. Raising an exception or entering an unsupported-path
trap is handled the same way: neither has a normal return. This result-path fact
does not authorize speculative evaluation of the expression.

## Checks and limits

[RepresentationAudit.hs](../compiler/test-fixtures/RepresentationAudit.hs) exercises actual optimized GHC Core: integer/address carriers, boxed/newtype/family distinctions, strict versus lazy fields, empty unboxed tuples, recursive joins, erased type parameters, and function-returning joins. [check-representation-metadata.py](../scripts/check-representation-metadata.py) checks those export facts. [check-speculation-metadata.py](../scripts/check-speculation-metadata.py) separately checks safe arithmetic, rejected failing computations and recursive dictionary guards.

[CoreProofJoinTest](../src/test/kotlin/thc/runtime/CoreProofJoinTest.kt) and [BytecodeCoreProofTest](../src/test/kotlin/thc/BytecodeCoreProofTest.kt) cover primitive captures, wide values, recursive/mutual transfers, parallel moves, outer continuations, malformed joins and introduced-thunk forcing. [RealCoreJoinTest](../src/test/kotlin/thc/RealCoreJoinTest.kt) executes genuine exported joins on both backends before and after compilation, including cold branches and full-width values. The fixture preparation script generates its input with source notes enabled regardless of the ordinary export setting; source attribution itself is described separately in [debug-locations.md](debug-locations.md).

[CbvAudit.hs](../compiler/test-fixtures/CbvAudit.hs), [CbvJoinAudit.hs](../compiler/test-fixtures/CbvJoinAudit.hs) and [CbvCoercionAudit.hs](../compiler/test-fixtures/CbvCoercionAudit.hs) exercise real workers, ordinary strict-function exclusion, type-erased join prefixes, returned lambda suffixes and a retained coercion before a marked boxed argument. [check-cbv-metadata.py](../scripts/check-cbv-metadata.py) checks those contracts and compares the proposals with actual post-Tidy GHC Id marks. [RealCoreEntryContractTest](../src/test/kotlin/thc/RealCoreEntryContractTest.kt) covers their runtime entry points on both backends before and after compilation, including cold branches and full-width values. [EntryContractTest](../src/test/kotlin/thc/runtime/EntryContractTest.kt) separately checks lazy PAP prefixes, indirect entry, overapplication, sharing and malformed metadata; [BindingCellMetadataTest](../src/test/kotlin/thc/runtime/BindingCellMetadataTest.kt) checks ordinary values, recursive publication, escaping captures and fresh invocations.

A corpus comparison must keep existing representation, WHNF, speculation, constructor strictness and join certificates, not merely compare expression tags. The strict [metadata-only comparison](../scripts/compare-cbv-export.py) retains all old fields; rebuilt plugins can change GHC uniques, in which case [lexical alpha comparison](../scripts/compare-executable-core.py) checks executable trees and their runtime certificates without relying on printed Core text. Entry contracts and constructor field types are included by default; comparing against an older export requires the explicit `--ignore-entry-contracts` or `--ignore-field-types` option for the newly added certificate. The frozen Map comparison using only `--ignore-entry-contracts` covers all 52 syntactically reachable globals and yields the same canonical SHA-256 before and after the new entry metadata: `c9530532240552bdde7203d16da97ccb6f240f67b30405aff0076a070c5dba38`. This establishes unchanged exported workload computation, not the speed or correctness of its new runtime lowering.

These are bounded contracts for the supported Core subset. They do not add arbitrary unboxed aggregates, floating/vector carriers, representation-polymorphic operations, or small/big `Integer` and `Natural` layouts. They also do not make every demand signature a calling-convention guarantee. New uses of evidence must preserve the distinction between the GHC value, THC's storage of that value, and the point where evaluation has actually occurred.

The [compiled graph check](source-note-graphs/README.md) also guards the intended
result specialization. The AST uses direct comparisons for the constant result
kind: Kotlin's enum-switch mapping array survived partial evaluation and kept
irrelevant typed body paths alive in the first implementation. The final direct
comparisons collapse those paths; the separate [measurement](debug-locations.md#graph-check-and-typed-dispatch-repair)
records the effect with source notes enabled throughout.
