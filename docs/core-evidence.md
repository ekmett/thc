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
| `long` | One supported integral register; narrow values use JVM `int`, machine/64-bit values use `long`. |
| `float` | Exactly `FloatRep`, with a JVM `float` carrier. |
| `double` | Exactly `DoubleRep`, with a JVM `double` carrier. |
| `vector` | One `VecRep`, with exact lane-count/element metadata and a fixed-species JDK Vector API value. |
| `address` | Exactly `AddrRep`, carried by a checked `ManagedAddress` reference, not an arbitrary host pointer. |
| `void` | No payload registers, including retained coercion tokens. |
| `data` | A single boxed representation with an actual boxed data type constructor. |
| `closure` | A single boxed representation with a function type after type abstraction erases. |
| `object` | A boxed carrier without the more specific data/function evidence. |
| `unknown` | No scalar carrier classification; exact aggregates use separate logical evidence below. Otherwise conservative lowering applies. |

Newtypes, type families and unary class representations do not establish a data-object layout. Unboxed tuples and sums keep `kind: "unknown"`, including the empty unboxed tuple, while their separate aggregate metadata can establish an executable layout. An empty register list alone does not make an aggregate a scalar void token. The shared parser checks exact carriers for positive primitive/reference proofs. Absent metadata supplies no positive evidence; unsupported operations and unresolved required representations fail explicitly.

Non-unary class selectors use GHC's `mkDictSelRhs` template with its dictionary,
case, fields and result certificates only when GHC `eqType` confirms the original
and lowered types agree. Unary selector/constructor erasure remains uncertified.

The six narrow literal forms (`int8`/`word8`, `int16`/`word16`, and
`int32`/`word32`) intrinsically establish their exact signed or unsigned
representation. Shared expression lookup recovers that proof for absent or
unconstrained metadata before strict primitive validation, just as literal
lowering does. Contradictory or malformed retained proofs and invalid literal
ranges still reject. Aggregate records additionally retain [recursive logical components and alternatives](aggregate-layout.md), independently of their physical register vector. GHC's representation view exposes aggregate newtype aliases without promoting scalar newtypes to data/closure proofs. Unresolved aggregate runtime representations stay `null`; lazy lifted children stay unevaluated. Supported tuple and sum layouts cover guest inputs, results, PAP prefixes, owned closure/thunk captures, local joins and saturated boxed-constructor fields. The [boundary map](aggregate-layout.md) links their distinct transport and storage contracts; empty tuples preserve logical arity without payload fields. Nonrecursive unlifted aggregate lets use typed frame locals; recursive/lifted aggregate lets, global aggregate storage and unresolved layouts still reject. The [Core host ABI](site/embedding.md#load-a-core-entry) transports supported aggregates as logical arrays. Exact vectors have a separate [transport contract](simd-families.md), including owned captures and boxed constructor fields.

A successful case establishes WHNF for its binder and original scrutinee variable within the alternatives. Pattern fields gain the same fact only when they are unlifted or the saturated constructor worker requires them to be strict. Worker strictness marks must align exactly with worker fields, including coercions. Lazy lifted fields remain lazy. Lexical facts propagate by GHC variable identity, so shadowing does not leak them into another binding.

An ordinary function returns its lifted result without forcing it. Neither a call nor a return establishes WHNF: `id x` can return the exact thunk passed as `x`. A `case` evaluates its scrutinee before inspection, including a default-only case, but does not thereby evaluate the selected alternative's result. Both backends preserve this distinction in their result proofs.

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
`-Dthc.callDemands=true`, read once per program; the default is off.
[Caller-demand evaluation](demand-probe.md) describes the option and its
limits; [the compiler protocol](compiler.md) specifies the metadata.

## Physical recursive cells

Whether a local slot can contain a `RecCell` is a separate lowering fact from both type and WHNF. Ordinary parameters, pattern fields, nonrecursive lets and captures of published values do not need recursive-cell resolution. A successful case or force can establish WHNF without changing the physical identity of an older recursive capture.

While a recursive group initializes, all RHS closures capture the group's cells by identity. After every RHS is initialized, publication replaces the current frame's group slots with their values. The let body and closures created afterward use those published values directly; closures created during initialization retain their original cell-bearing captures. Successful forcing updates the appropriate mutable binding link or cell contents while preserving aliases. Publication never replaces a cell early just because another RHS forced it.

Both backends preserve this distinction in their local/capture metadata. Bytecode omits `ReadCellIfNeeded` on proven ordinary values. A proven primitive capture uses the direct primitive path only when the physical source cannot still be a recursive cell. This removes avoidable indirection checks without turning recursive knots into copied values or weakening lazy sharing.

## Precise reference storage

Constructor metadata also carries `fieldTypes`, aligned with `fieldReps`, `strictFields` and `fieldLifted`. Each record uses the same structured kind and evaluatedness proof as an expression. Its type comes from the constructor worker's actual field type; its evaluated flag requires a strict worker field or an unlifted representation. The runtime checks that these records agree with the existing storage and evaluation obligations before using them.

An evaluated data field can be a final Java `DataValue` field, and an evaluated function field can be a final `Closure` field. A lazy field of either Haskell type still uses `Object`, because it can contain a thunk. A strict polymorphic field also stays `Object`: WHNF alone does not identify its carrier. Thus `Map`'s strict left and right children can have concrete reference fields while its polymorphic key and value retain their general representation. The existing primitive size field stays `long`.

A constructor `AddrRep` field uses a final `ManagedAddress` property, including
older records that retain `fieldReps` but omit `fieldTypes`. Retained exact field
types must identify an evaluated, unlifted address. Allocation rejects numeric,
null and foreign carriers. Literal addresses retain immutable GHC string bytes;
mutable addresses retain the original managed byte-array backing. Both use checked
offsets and remain distinct from native pointers. Lazy neighboring fields
remain untouched. An exact evaluated `AddrRep` leaf is also accepted in an
unboxed tuple. Its physical result and typed-input field is a managed reference;
tuple construction and consumption reject null, numeric, and foreign carriers.
[Sum layouts](sum-results.md) also retain managed addresses as traced fields;
native projection is a separate foreign-boundary operation.

Closure environments apply the same rule to proven evaluated data, function and managed-address captures. Precise reference captures need neither an adaptive primitive arm nor a tag. Captures that can hold a recursive cell remain generic even if forcing has established WHNF for the cell's contents. Without those proofs, storage stays generic.

This uses Truffle's supported `StaticShape` property types. With class-owned
layouts enabled, an exclusively owned generated class identifies its constructor.
Shared carrier classes retain an explicit layout field and comparison; Truffle's
array strategy can share one class between shapes.

The same reference proof is restored at function entry and after self, ancestor
and local-join transfers. A checked `DataValue`, `Closure` or `ManagedAddress`
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
to `LayoutDataValue` with an explicit owner field.

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

Each layout owns a private allocation key and a private generated factory. Allocation passes that key to the storage superclass, which checks its identity before entering `ValidatedStorage` and ultimately `Object.<init>`. A failed check therefore cannot produce a finalizable object that a subclass could resurrect. The key is neither exposed nor retained in each value, and the common base adds no fields or finalizer.

Operations on existing storage retain explicit owning-layout and field-index checks. This identity test is essential for array-based storage, where different layouts can share a generated Java class. Fresh initialization uses only the owning factory's storage. Precise reference writes still perform `StaticProperty`'s assignability check independently of the storage-check flag; primitive and zero-width field validation also remain. Captured aliases and recursive cells keep their existing identities and representations.

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
and must retain the layout comparison.

## Join points

A join binding exports `joinValueArity` and `joinResultRep`. GHC's original join arity counts type lambdas, so it cannot be used directly after erasure. The exporter takes that exact original lambda prefix, removes only type binders, and retains value/coercion parameters. `joinResultRep` describes what remains after the prefix.

This matters when a join returns a function. If a flattened RHS has two value lambdas but the join prefix contains one, the second lambda remains a returned closure. A zero-argument join consumes no lambda prefix and returns its whole RHS. The runtime never guesses adjusted arity from `idArity` or the diagnostic `info.joinArity`.

The shared validator requires every reference to a local join to be exactly saturated in tail position within its lexical region. It rejects escaping joins, partial/overapplication, use beneath a returned lambda, and calls with pending work inside the same region. Recursive and nonrecursive scopes differ; ordinary binders shadow join names. Mixed ordinary/join binding groups are currently unsupported rather than guessed.

GHC permits an outer-join jump inside a `runRW#` continuation because CorePrep
beta-reduces that continuation. The exporter retains a direct one-parameter
`State#` lambda applied to the literal zero-width `realWorld#` token. Shared
runtime lowering and join validation reduce exactly that form to a state case,
preserving the original binder, source metadata and tail context. It introduces
no closure boundary and does not inline arbitrary lambdas or discard a
state-producing argument. Ordinary captured joins and non-tail transfers remain
rejected. Ordinary validated joins stay inside the current guest root. Nonrecursive groups use acyclic dispatch: the AST catches one lexical transfer and bytecode emits forward branches without a selector or loop. Recursive groups retain a local loop and backedges. Both evaluate operands into temporaries before replacing parameters, preserving swaps and mutually recursive transfers. A join region may itself appear within a larger non-tail expression: leaving that region resumes the outer continuation. Join transfers do not allocate closures or ordinary application packets.

Bytecode budget recovery can prepare a separate target for a substantial closed
nonrecursive join body, using the existing finite expression-region protocol.
The original transfer binds and demands its arguments before that target captures
the selected locals. Bodies referencing an ambient join stay in their activation;
prepared sides do not recursively outline themselves. Existing nested case or
join plans take precedence over an optional whole-body side, so its eligibility
cannot suppress normal local-capacity partitioning. Selection retains the
original result destination, masks and captured values. Fresh-entry recovery does
not move, restart or migrate saved activations; their frame and bytecode PC remain
the resume point. This eligibility rule does not guarantee that either resulting
compiler graph fits the budget.

Exact unboxed tuple and sum join results use typed locals in the same activation. The AST writes flattened result leaves or sum tag/payload slots into region slots and copies them to the enclosing destination; bytecode writes each returning branch directly into that destination. Nested logical tuples, singleton tuples and empty tuples retain their exact shape. No aggregate carrier, pool loan, or Truffle call boundary is introduced by the join. Lifted payloads remain lazy; copying reference slots does not force them. Scalar void components keep logical positions without payload slots, and their expressions still execute. Exact tuple and sum join inputs use parallel typed frame moves, including their tag/payload fields. Zero-width operands still execute before transfer. Local joins read enclosing tuple and sum captures through their existing typed slots. Logical alternatives and tuple nesting remain distinct from physical width; unresolved layouts remain rejected. Exact vector join arguments/results and captures follow the separate [vector transport contract](simd-families.md). See [tuple joins](tuple-joins.md), [empty tuple joins](empty-tuple-joins.md), [sum inputs](sum-inputs.md) and [sum results](sum-results.md) for their capabilities and evidence. The logical shape must agree across the join annotation, its retained RHS lambda result, body, applications and enclosing region.

A transfer has no returning value. Its result therefore cannot weaken the WHNF
proof of the branches that actually leave a join region. An actual lazy return
still prevents that proof. Raising an exception or entering an unsupported-path
trap is handled the same way: neither has a normal return. This result-path fact
does not authorize speculative evaluation of the expression.

## Lifted boxed Array# storage

The five saturated operations `newArray#`, `readArray#`, `writeArray#`,
`unsafeFreezeArray#`, and `indexArray#` support boxed elements at either known levity on both
backends. A managed JVM `Object[]` is the array's actual storage; `byte[]`
remains the distinct numeric ByteArray# carrier. Initial values and writes
preserve the same guest reference or thunk. A read loads that reference at the
sequenced operation, so subsequent writes cannot change an earlier snapshot;
lifted elements remain lazy, while unlifted elements retain their evaluated carrier.

Exact GHC 9.14.1 proofs distinguish `(# State# s, a #)` from the singleton
`(# a #)` returned by `indexArray#`. State is logically present but occupies no
payload slot. Array# and MutableArray# are unlifted boxed references; ordinary
Array/STArray wrappers are lifted data. Saturated tuple operations write one
object directly into caller-frame locals, without a tuple carrier or result
slab. State evaluation precedes effects/publication, managed Long bounds are
checked before narrowing, and unsafe freeze preserves storage identity. Array
contents are mutable and never marked compilation-final.

The proof schema records representation, not nominal storage type:
Array#/ByteArray#/MutVar# can all have `BoxedRep (Just Unlifted)`. Contradictory
liftedness or logical layouts fail validation; a supplied byte[] masquerading
as Array# additionally fails the runtime Object[] carrier guard. Unknown element layouts and first-class/partial primitives remain unsupported.
Copying, freeze/thaw slices, [small arrays](small-arrays.md) and
[boxed atomic operations](boxed-cas.md) have separate contracts.
