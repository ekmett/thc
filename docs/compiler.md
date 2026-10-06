# GHC Core exporter

The root `thc.cabal` builds `THC.Plugin` as the `thc` library against **GHC 9.14.1**. `cabal build` builds it alongside the driver; `bin/build-compiler.sh` remains a convenience wrapper for fixture scripts. `./bin/export-core.sh t/fixtures/core/Fixtures.hs` compiles the Haskell modules with `-O2 -g -dcore-lint` and exports `build/core/THC.Prim.Test.cbd` and `build/core/Fixtures.cbd`. GHC still produces native object/interface files in `build/ghc`; this is useful for checking that the same source is valid GHC Haskell. The compiler writes the existing typed CBD format directly from its module value, with no intermediate Core JSON file. CBD is the plugin's executable output. The runtime also accepts a [supported subset of retained `.hi` Core](core-package-manifest.md#native-interface-modules) directly.

The default source-fixture export appends `CoreDoPluginPass` to `installCoreToDos`, observing the **optimized Core pipeline's final `ModGuts`, before Tidy/CorePrep/STG**. With `post-tidy`, the plugin instead uses `latePlugin` to export **after Tidy and before CorePrep**; package manifests require that boundary. The ordinary GHC Core optimization passes run first in both paths. This is executable tree export directly from the GHC API; the runtime never parses a Core pretty dump.

This is a deliberately version-pinned experiment, **not** a lossless, stable, general-purpose Core interchange format. It implements a checked executable subset. Default exports retain executable trees, binder types and arity, representation and calling-contract facts, join arity, and typed foreign metadata. Source locations and contents remain controlled independently by `source-notes`.

For explicit inspection, add `-fplugin-opt=THC.Plugin:pretty-diagnostics`. This
writes an additional JSON inspection of the emitted CBD; it is absent by default
and cannot be used as executable input. `thc-compact decode Module.cbd Module.json`
provides the same semantic inspection offline. Original display names/source
positions are available through its `name` and `source` commands. Successful
`thc-interface` exports return raw CBD; inventory/error replies remain JSON control
messages. Full structured coercions, rules and unfoldings,
and general representation-polymorphic lowering remain outside the contract.
Dependency acquisition and linking have their own implemented
[package-manifest contract](core-package-manifest.md).

## Choosing an execution backend

Use GHC string annotations to choose AST or bytecode execution for a module or a
top-level function/value:

```haskell
{-# ANN module ("thc:backend=ast" :: String) #-}
{-# ANN calculate ("thc:backend=bytecode" :: String) #-}
```

A declaration's choice takes precedence over its module's choice. Unannotated
modules use the runtime's requested backend, which defaults to bytecode. The
choice is made when a binding is first lowered; calls across backends share the
same closures and evaluated CAFs.

Annotations choose execution roots, not GHC optimization behavior. An annotation
on a declaration does not follow an inlined copy or a generated worker; a module
annotation covers the remaining roots in that module. Backend annotations on
types and conflicting choices are rejected. The AST-only native code cache
rejects reachable bytecode selections.

Automatic loop vectorization remains a global compiler option. It cannot be selected
with a declaration or module annotation. The launcher defaults it off; set
`JAVA_OPTS=-Djdk.graal.VectorizeLoops=true` to enable it for the JVM.

## Executable schema

Both export boundaries precede CorePrep's mandatory primitive saturation.
For genuine `maskAsyncExceptions#`, `unmaskAsyncExceptions#`, and
`maskUninterruptible#` IDs, the exporter uses GHC's typed, capture-avoiding
`etaExpand` to supply missing value arguments before erasing types. A mask
applied only to its action becomes an ordinary `State#` lambda containing an
exactly saturated primitive call; a type-instantiated bare mask gets both
parameters. Its supplied action remains lazy until the state token arrives.
The lambda, binder and result proofs come from the resulting Core types, using
the existing schema. Saturated primitive calls and their runtime contract are
unchanged. Original GHC definitions remain untouched, and
native GHC compilation is unchanged. This bounded lowering does not implement
general primop partial applications or establish support for full `bracket`.
`cabal run exe:thc-fixtures --offline -- mask-functions` prepares the native and
pre/post Core evidence consumed by `thc.runtime.MaskFunctionNativeTest`.

Erasing GHC's genuine `lazy` identity retains representation evidence only when
GHC `eqType` confirms the original and lowered expression have the same type.
This preserves exact `MutVar#`/`State#` proofs inside lazy returned IO actions,
such as the original encoding module's `mkGlobal`. It introduces no evaluation
or strictness override and does not certify unary-class representation erasure.
The existing `MutVarAudit` native suite exercises public IORef actions returned
through `unsafePerformIO`, including an overwritten bottom payload.

A module object carries `schema`, `ghc`, `module`, `unit`, `boundary`, `bindings`, `constructors`, and top-level `groups`. Top-level and local let bindings have `{id,name,type,lifted,arity,info,rep,expr}`. Top-level exported/external IDs use `unit:Module.occ`; private/local IDs additionally retain the GHC unique and a module namespace. A unique is not stable across recompilations. GHC units are part of identities so equal module names in different packages remain distinct.

Expressions are tagged arrays:

- `['var', id]`
- `['lit', kind, valueAsString]`
- `['prim', primopOccurrenceName]`, e.g. `+#`
- `['con', constructorId, representationArity]`
- `['app', function, [argument...], [liftedBoolean...], knownWhnfBoolean, safeToEvaluateBoolean]`
- `['lam', [{id,name,type,lifted,coercion,info}...], body]`
- `['let', recursiveBoolean, [binding...], body]`
- `['case', scrutinee, binderId, [alternative...]]`
- `['void']`
- `['unsupported', diagnostic]`

A case alternative is `[kind, discriminator, [binderIds...], body]`, with kind `default`/`data`/`lit`. Default discriminators are null, data discriminators are constructor IDs, literal discriminators are `[literalKind,valueAsString]`. Empty cases remain empty; the runtime must force their scrutinee rather than invent a value.

Representation evidence is an optional final object on each expression. Its zero-based position is `var[2]`, `lit[3]`, `app[6]`, `lam[3]`, `let[4]`, `case[4]`, `con[3]`, `prim[2]`, or `void[1]`. An absent object requires conservative inference. All binder and binding records also carry `rep`. A representation record is:

```json
{"primReps": ["IntRep"], "kind": "long", "evaluated": true}
```

`primReps` comes from `typePrimRep_maybe`, using the same canonical GHC names as constructor `fieldReps`; unresolved representations are null. `kind` is `long`, `float`, `double`, `vector`, `address`, `void`, `data`, `closure`, `object`, or `unknown`. `long` requires exactly one supported signed/unsigned integer register; it does not mean every unlifted type. `float`, `double` and `address` require their corresponding primitive register. A `VecRep` adds `vector: {lanes, element}` evidence. Boxed `data` requires GHC's `isBoxedDataTyCon`, which excludes newtypes, type families, unary class representations and abstract types. Function types prove `closure`; other boxed types remain `object`. No classifier parses printed type strings. The category describes the value after evaluation and does not itself permit eager evaluation or skipping a force.

Unboxed tuples and sums keep `kind: "unknown"` and separate logical shape evidence: `aggregate: "unboxed-tuple"` with ordered `components`, or `aggregate: "unboxed-sum"` with ordered `alternatives` and physical slot projections. Each component has its own representation record. This distinguishes a zero-width tuple from a scalar `State#` token, and a singleton aggregate from a scalar register. An unresolved aggregate shape has null components or alternatives; its physical register count alone does not justify a layout. See [aggregate layout evidence](aggregate-layout.md) for the supported uses and rejection boundaries.

`evaluated` is positive WHNF evidence, independent of demand/strictness. GHC's `exprIsHNF (Var v)` recognizes already evaluated unfoldings and definitely unlifted binders. Ordinary lifted lambda parameters remain unevaluated even when their demand signature says they will be forced. A case's metadata includes a complete `binder` record marked evaluated. Each alternative appends `{binders: [fullBinderRecords...]}` at index 4, in exactly the order of its existing binder IDs. Within that branch, lexical WHNF facts also cover the original scrutinee variable (through casts/ticks) and fields marked strict in the saturated constructor worker's representation layout. These facts reach both binder records and variable uses. Lazy lifted pattern fields remain lazy. Strictness marks are applied only when they align exactly with the retained worker slots, including zero-width coercions. Rewritten wired subtrees retain conservative unknown type evidence.

Lambda metadata also includes `resultRep`, the representation of the body after all exported value/coercion lambdas have been flattened. Case metadata uses `resultRep` for the original typed case's result, independently of the enclosing expression's `rep`. Unary-class erasure or a cast may change the latter without changing the retained case operation. In particular an erased `C:IP` wrapper still has an unknown expression certificate; the intrinsic case certificate does not certify that wrapper's ABI. Uncertified contexts retain unknown intrinsic certificates. Join bindings carry `joinValueArity` and `joinResultRep` in addition to the original diagnostic `info.joinArity`. GHC join arity counts type binders; `joinValueArity` counts only the non-type binders in that exact original prefix, retaining coercions. `joinResultRep` describes the remaining RHS after that prefix, which can itself be a lambda. A join returning a function must therefore retain the remaining lambdas rather than consume every flattened parameter. These fields follow GHC 9.14.1's `Note [Invariants on join points]` and `collectNBinders`; join arity is not `idArity`.

Bindings and their leading flattened lambda metadata also carry `entryStrict: [Boolean...]` and `entryStrictSource`. These are **requested entry obligations**, separate from `rep.evaluated`: a true position must contain an evaluated value before the function body starts. Positions count retained value/coercion arguments, erase type arguments, and are padded false through the full flattened lambda. For a join, only its original join prefix can acquire obligations; returned lambda arguments remain false. A binding without a leading lambda retains only the contract prefix on the binding record; the exporter does not attach it to an unrelated nested lambda.

`info.cbvEligible` records GHC worker/join eligibility; `info.cbvMarks` preserves `idCbvMarks_maybe` as a Boolean array or null. Current-module CBV marks are normally empty before Tidy. At that boundary, the exporter calls the pinned compiler's own `GHC.Core.Tidy.tidyCbvInfoTop emptyNameSet` selection logic on eligible definitions and records resulting requirements as `ghc-tidy-proposal`. The empty boot-export exclusion set intentionally requests a THC-internal convention, not a claim about native boot-file ABI permissions. Actual nonempty Id marks, including imported or post-Tidy marks, have source `ghc-id`. Interface unfoldings and post-Tidy exports do not derive new proposals; no contract has source `none`. The Core tree and GHC's native compilation are unchanged.

GHC's selection handles worker/join eligibility, exact join prefixes, supported single-register arguments, strict-and-used demands, dead-end exclusions, and arity trimming. We neither parse demand strings nor treat general strict demand as proof of prior evaluation. The runtime must enforce these obligations on **every saturated entry route**, including indirect calls, PAP prefixes, overapplication, host entry and local joins, before strengthening formal WHNF facts. Creating or observing an undersaturated function must not force its captured arguments. Native GHC also requires properly tagged references; this export asks THC for the analogous direct WHNF value and does not imply pointer tagging on JVM references. See the pinned [CBV invariants](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/compiler/GHC/Types/Id/Info.hs) and [mark selection](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/compiler/GHC/Core/Tidy.hs).

Application metadata separately carries optional `callDemand: {arity: N, strictArgs: [Boolean...]}`. This permits evaluating marked arguments when this particular application executes. `N` is the original GHC `DmdSig` argument count, obtained with `splitDmdSig`; it is independent of `idArity`, lambda count and entry obligations. `strictArgs` has exactly one position per retained argument, including coercions. Below `N` supplied positions all marks are false. Otherwise only GHC `isStrUsedDmd` demands can produce true marks; coercion positions, overapplication suffixes and arguments headed by `lazy` (also under casts/ticks) remain false. In particular, bottom demand is strict but absent and never becomes a true mark. Divergence does not add marks. No demand string is parsed.

The exporter computes the certificate from the original typed application before erasing type arguments or rewriting wired identities. Only a direct `Var` head is accepted; a head behind a cast/tick gets no certificate, conservatively respecting application boundaries. Rewritten wired subtrees also receive none. Missing metadata preserves the lazy path; malformed present certificates are errors. When caller-demand lowering is enabled, a marked position uses the runtime's existing strict argument path without allocating a fresh argument thunk. The permission stays inside evaluation of this application: it does not force enclosing lazy bindings or constructor fields, widen `entryStrict`, strengthen a formal's `rep.evaluated`, or narrow a polymorphic argument's representation. See GHC's [demand-signature threshold and strict-used distinction](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/compiler/GHC/Types/Demand.hs) and [CorePrep application preparation](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/compiler/GHC/CoreToStg/Prep.hs).

Caller-demand lowering is **opt-in** with `-Dthc.callDemands=true`; the default is false. Each AST or bytecode program reads this property once when it is constructed. The exporter always emits structured evidence, and the runtime validates present certificates even when their lowering is disabled. This switch does not affect constructor strictness, entry contracts, WHNF evidence or speculation. The dedicated demand tests enable it locally and restore the previous property afterward.

After building the distribution and exported fixtures, opt in for a run with:

```sh
JAVA_OPTS='-Dthc.callDemands=true -Dpolyglot.compiler.InliningPolicy=Default' ./bin/run.sh build/core/THC.Prim.Test.cbd,build/core/Fixtures.cbd main:Fixtures.sumLoop 10000 --compile
```

`JAVA_OPTS` is read by the installed application launcher. Passing `-Dthc.callDemands=true` only to Gradle does not forward it to the test or application JVM. For benchmark comparisons, hold the inlining policy fixed explicitly on both sides and vary only `thc.callDemands`; retain the same Core and runtime. The policy selection in the example is a comparison control, not a requirement for caller-demand semantics. See [the current demand contract](demand-probe.md).

`lifted` is inferred from GHC's type levity. An unresolved levity is JSON null. It may still have a known boxed pointer representation; genuinely unknown runtime layouts reject. Lifted arguments preserve lazy evaluation. Unlifted arguments require evaluation before entering the callee. Constructor records carry representation arity, GHC tag, type, and kind (`boxed`, `unboxed-tuple`, `unboxed-sum`, `newtype`); presence in this export does not imply runtime support.

Unboxed sum constructor records add `sumArity`, the number of constructors in their GHC family; their ordinary `arity` is still the one logical payload. Sum representation records add `tagSlot: 0` and `alternativeSlots`, the GHC-derived zero-based payload projection into the enclosing physical `primReps`. Unknown projections remain null. These fields do not enable sum execution; see [sum layout evidence](aggregate-layout.md#sum-storage-projections).

Constructor records also carry parallel `strictFields` and `fieldLifted` arrays for the **worker representation slots**, from `dataConRepStrictness` and `dataConRepArgTys` respectively. These are not source-field strictness annotations: unpacking may change the slots. A strict lifted field carries a Core worker call-by-value obligation that argument levity alone does not enforce. The runtime must force strict lifted worker fields to WHNF at saturated construction, while retaining the original logical fields for cases and partial applications. Unresolved field levity remains an explicit capability error.

The optional fifth application element is GHC 9.14.1's `exprIsHNF` result for the **original complete Core application**, before erasing type and coercion information. `true` certifies that the application is already in weak head normal form, allowing the runtime to construct its value directly instead of wrapping that application in a thunk. GHC recognizes constructor applications and partial applications when their required argument evaluation is safe; it accounts for unlifted arguments and saturated constructor workers' strict fields. Lazy constructor fields remain lazy. `false` means no certificate, not that evaluation necessarily diverges. This does not override levity checks, constructor support checks, or strict-field evaluation obligations. See [GHC's canonical analysis](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/compiler/GHC/Core/Utils.hs). An absent fifth element is treated conservatively as `false`.

The optional sixth application element uses GHC's `exprOkForSpecEval` on the original Core application. It certifies that evaluation reaches WHNF promptly without an observable effect or exception, allowing direct evaluation of lifted arguments and nonrecursive let RHSs even when they contain safe primitive redexes, such as `Box (n -# 1#)`. The predicate excludes all IDs in every enclosing recursive group while traversing that group's RHSs, following CorePrep's `Note [Speculative evaluation]`; blindly using `exprOkForSpeculation` could turn a terminating recursive dictionary into an infinite evaluation. Recursive and global binding initialization still requires the runtime's separate lazy handling. The sixth flag is authoritative when present, so a recursive-scope exclusion cannot be overridden by the WHNF flag. When the sixth flag is absent, consumers may use the fifth; both absent means conservative lazy evaluation. Lazy lifted fields and arguments remain lazy; potentially failing unlifted computations such as division by zero do not receive the speculation certificate. Ordinary unknown or saturated function calls remain conservative.

Constructor records also include `fieldReps`, aligned with the same worker slots. Each slot is GHC's actual list of `PrimRep`s, obtained through `typePrimRep_maybe` on the representation argument type and encoded with the canonical GHC 9.14.1 constructor names (`Show PrimRep`). This is type analysis, not parsing printed type text. An empty list means zero-width; a singleton occupies one register; multiple entries describe a multi-register representation. Register count alone does not distinguish scalar and aggregate logical fields. Supported [tuple and binary-sum fields](aggregate-heap-fields.md) require the matching logical shape in `fieldTypes` and lower to typed physical properties; their constructor workers support partial application on the AST backend and require direct saturation on the bytecode backend. An unresolved runtime representation is JSON `null`, requiring rejection when reachable. For example, `Box Int#` has `[["IntRep"]]`, while `Cons Box List` has `[["BoxedRep (Just Lifted)"], ["BoxedRep (Just Lifted)"]]`. A void slot has `[]`; a constructor with no fields has an empty outer `fieldReps` list. `fieldLifted` and `strictFields` remain independent metadata: pointer representation does not establish evaluation state or constructor strictness. Unsupported primitive representations and aggregate shapes must remain errors rather than defaulting to reference storage.

Constructor `fieldTypes` optionally carries a representation record per worker slot, aligned with `fieldReps`. It applies the same type classifier to the actual worker argument type; `evaluated` is true only for an unlifted slot or an exactly aligned strict-worker-field obligation. The runtime may use this to register a more precise reference field class only after it enforces that obligation. For example, ordinary Map `Bin` has `long`, `object`, `object`, `data`, `data` fields: the size is primitive, polymorphic key/value references remain generic, and strict child Maps can use the data-value class. A lazy field of a known data or function type must still accept a thunk. Newtype and family fields retain the conservative `object` category unless their actual worker type has already exposed another representation. Missing `fieldTypes` preserves generic reference storage; a type category alone never certifies WHNF.

Exactly saturated `tagToEnum#` applications retain an `enumFamily` object in their expression metadata before type arguments erase: `{typeConstructor, constructors}` contains the full type-constructor identity and ordered original nullary constructor identities. The bounded concrete enum subset requires `isEnumerationTyCon`, zero type parameters, and zero representation fields; data-family instances are excluded. Each constructor repeats its family descriptor, and dependency collection includes the whole family even when imported constructors are otherwise unused. This is nominal proof, separate from the lifted reference result representation. Missing/contradictory descriptors and bare primitive values remain unsupported; see [the runtime contract](tag-to-enum.md).

Data-constructor **workers** become constructor expressions. Constructor wrappers remain ordinary variable references and require actual compiled definitions.

Type arguments and type lambdas erase. A type-only application becomes its function. Coercion arguments and coercion lambda binders retain a zero-width `void` slot so Core's value arity conventions remain explicit. Casts and ticks erase from the executable subset and remain visible in the optional `sourceCore` diagnostic. The source-note metadata below retains source attribution without executable tick wrappers or instrumentation events. Primitive literals retain kind, with integral/character codepoint values in decimal, byte strings in hexadecimal, floating values in decimal. `LitLabel` preserves its exact symbol as `function-addr` or `data-addr`, according to GHC's `FunctionOrData`; it does not certify a function ABI or identify a providing library. Unsupported literal kinds stay explicit, and exporting a label does not admit it for execution.

`LitRubbish` exports as `["lit","rubbish",null,metadata]`. The applied type's
representation metadata carries its scalar, vector, tuple or sum shape. It is
an evaluated absent filler with unspecified payload. See
[the runtime contract](rubbish-literals.md).

## Optional source attribution

`bin/export-core.sh ...` enables GHC `-g` and the plugin's `source-notes` option by default. Set `THC_SOURCE_NOTES=false` to omit both. The Map and boot drivers use the same default and record the effective setting in provenance, so ordinary CI Map runs include source attribution. Executable schema 1 remains compatible with older exports without source metadata.

Enabled modules append `sourceFiles` records `{id,path,content}` and `sourceSpans` records `{id,file,startLine,startColumn,endLine,endColumn,charIndex,charLength,label}`. File IDs are GHC's original path strings. Span IDs are opaque deterministic strings, including the file, coordinates and label; consumers must not parse them. A module merge must reject conflicting records under an existing ID. `content` contains the actual UTF-8 source decoded as text, or null if unavailable. Installed interface notes and `LINE` pragmas can legitimately name unavailable files.

Coordinates retain GHC's original one-based, tab-expanded columns and exclusive end. `charIndex` and `charLength` are exact UTF-16 code-unit offsets into the embedded content, matching Truffle's indexing. The exporter scans real character boundaries, with eight-column tab stops and two UTF-16 units for supplementary Unicode characters. Both offsets are null when either coordinate cannot be resolved; they are never guessed from line/column values. Consumers must retain raw coordinates when content is missing or a remapped span falls outside it.

Existing expression metadata adds `source`, the innermost span ID, and `sourceNotes`, all enclosing notes in outer-to-inner order. Binder and binding records can also carry a `source` from GHC's `nameSrcSpan`, providing a fallback location for roots. Source ticks do not introduce expression wrappers, change lambda/join shape, strengthen representation certificates, or affect forcing. Other ticks still erase. GHC may move or copy notes during optimization: the records provide attribution, not exact Haskell stepping or an executed-event log.

The CMake target `fixture-source-core` generates source-enabled `RepresentationAudit` and `SourceNotes` fixtures in `build/source-core`, even when ordinary exports omit notes. `bin/check-source-metadata.py --fixture build/source-core/SourceNotes.cbd build/source-core/RepresentationAudit.cbd` checks table references, nested provenance, Unicode/tab offsets and unavailable content independently of the runtime. `bin/compare-executable-core.py BEFORE AFTER` compares executable trees after lexical alpha renaming while retaining runtime proof and constructor metadata; it ignores source metadata and diagnostic pretty text. It reports structural differences rather than claiming general equivalence between different optimized trees.

## Scope and checks

The runtime selects a root and checks the reachable definitions; it must fail closed for unknown reachable external names, primops, constructor representations or expression/literal forms. Unreachable GHC-generated Typeable metadata remains in the module. Exporting a module is not a claim that the runtime can execute every binding in it.

`bin/export-core.sh` uses `--make` so imported **source modules** are exported too, and accepts normal additional GHC flags. This script does not recover complete executable Core from installed `.hi` files. The separate [`THC.Interface` API and selected-compiler helper](ghc-core.md) do so when the selected installation retains complete Core. The project driver's `--installed-core required` provider acquires that dependency closure; its default pinned-source provider is a separate choice. Foreign products still need their own verified provenance and admission, and acquisition does not establish runtime support for every binding.

Environment overrides: `GHC` and `GHC_PKG` select the compiler and package-manager executables (both must report 9.14.1); `THC_CORE_OUT` and `THC_GHC_OUT` choose export and object directories. `THC_SOURCE_NOTES=false` opts out of the default source metadata and `-g` for source and boot exports. The exporter discovers the plugin's unit ID and local package database from Cabal's build metadata. Original boot-library exports load that compiled library directly to avoid importing plugin interfaces into the GHC unit being rebuilt. No global GHC package registration is needed.

## Dependency export

`closure=ENTRY` follows lexical term references from a selected root. It uses
complete source definitions from the compilation and GHC's available Core/DFun
interface unfoldings for external definitions. Missing interface bodies are
reported explicitly. Use [complete installed Core](ghc-core.md) for package
acquisition; `bin/export-map.sh` provides a separate diagnostic workload.

`THC.Wired` performs GHC's canonical late erasure of unary-class constructors/selectors, `lazy`/`noinline`/`nospec`, `runRW#`, and the zero-width real-world state token; ordinary class selectors use GHC's own generator. This is compiler representation lowering, not a replacement library algorithm. Both serialization and dependency discovery apply the same lowering. Rewritten subtrees receive conservative evaluation certificates because representation-erased unary templates are no longer typed Core; they are never inserted back into GHC's optimized program or Core lint.

## Fresh-checkout build

Use GHC/ghc-pkg 9.14.1, Python 3.12 or later, and GraalVM 25.3.4.1 with JDK 25. Set `JAVA_HOME` explicitly; scripts never borrow another checkout's JDK. `bin/try.sh` builds the exporter, generates the real fixture and CString exports required by the JVM tests, creates the native oracle, then runs tests and assembles the application. Gradle downloads dependencies normally; append `--offline` only after populating the cache. `make` uses the project's `.gradle-user-home` cache unless `GRADLE_USER_HOME` is set; direct `./gradlew` calls use Gradle's normal environment and defaults.

`bin/try-map.sh` is also self-contained: it prepares the same test inputs, exports the Map bundle, checks capability gaps, and compares native/guest results. The current known gaps require the explicit diagnostic invocation `THC_DIAGNOSTIC_UNSUPPORTED=true bin/try-map.sh`; the default strict invocation intentionally stops at the audit. CI runs both entrypoints on Linux and macOS, with pinned compiler/runtime versions and the compatibility audit retained as an artifact.
