# GHC Core exporter, prototype schema 1

`./compiler/build.sh` builds a dynamically loaded plugin against **GHC 9.14.1** using only packages shipped with GHC. `./compiler/export.sh examples/THC/Fixtures.hs` compiles the Haskell modules with `-O2 -dcore-lint` and exports `build/core/THC.Prim.json` and `build/core/THC.Fixtures.json`. GHC still produces native object/interface files in `build/ghc`; this is useful for checking that the same source is valid GHC Haskell. The THC runtime evaluates the exported expression trees.

The plugin appends `CoreDoPluginPass` to `installCoreToDos`. This observes the **optimized Core pipeline's final `ModGuts`, before Tidy/CorePrep/STG**. The ordinary GHC optimization passes run first. This is executable tree export directly from the GHC API; the runtime never parses a Core pretty dump.

This is a deliberately version-pinned experiment, **not** a lossless, stable, general-purpose Core interchange format. It proves the boundary with a controlled executable subset. `sourceCore` retains the module's readable pre-erasure Core for inspection, and binder metadata retains types, demand, strictness, CPR, arity, call arity, occurrence/one-shot information, join arity and inline pragmas. Module rewrite rules are retained as readable metadata. These strings are evidence for the architecture experiment, not a promise that an optimizer can round-trip arbitrary GHC objects. Full structured coercions, rules, unfoldings, dependencies, debug spans and representation-polymorphic lowering are subsequent work.

## Executable schema

A module object carries `schema`, `ghc`, `module`, `unit`, `boundary`, `bindings`, `constructors`, and top-level `groups`. Top-level and local let bindings have `{id,name,type,lifted,arity,info,expr}`. Top-level exported/external IDs use `unit:Module.occ`; private/local IDs additionally retain the GHC unique and a module namespace. A unique is not stable across recompilations. GHC units are part of identities so equal module names in different packages remain distinct.

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

`lifted` is inferred from GHC's type levity. An unresolved levity is JSON null and must be rejected by the runtime if reachable. Lifted arguments preserve lazy evaluation. Unlifted arguments require evaluation before entering the callee. Constructor records carry representation arity, GHC tag, type, and kind (`boxed`, `unboxed-tuple`, `unboxed-sum`, `newtype`); presence in this export does not imply runtime support. Constructor records also carry parallel `strictFields` and `fieldLifted` arrays for the **worker representation slots**, from `dataConRepStrictness` and `dataConRepArgTys` respectively. These are not source-field strictness annotations: unpacking may change the slots. A strict lifted field carries a Core worker call-by-value obligation that argument levity alone does not enforce. The runtime must force strict lifted worker fields to WHNF at saturated construction, while retaining the original logical fields for cases and partial applications. Unresolved field levity remains an explicit capability error.

The optional fifth application element is GHC 9.14.1's `exprIsHNF` result for the **original complete Core application**, before erasing type and coercion information. `true` certifies that the application is already in weak head normal form, allowing the runtime to construct its value directly instead of wrapping that application in a thunk. GHC recognizes constructor applications and partial applications when their required argument evaluation is safe; it accounts for unlifted arguments and saturated constructor workers' strict fields. Lazy constructor fields remain lazy. `false` means no certificate, not that evaluation necessarily diverges. This does not override levity checks, constructor support checks, or strict-field evaluation obligations. See [GHC's canonical analysis](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/compiler/GHC/Core/Utils.hs). The first four elements are unchanged; older schema-1 exports without the fifth element must be treated conservatively as `false`.

The optional sixth application element uses GHC's `exprOkForSpecEval` on the original Core application. It certifies that evaluation reaches WHNF promptly without an observable effect or exception, allowing direct evaluation of lifted arguments and nonrecursive let RHSs even when they contain safe primitive redexes, such as `Box (n -# 1#)`. The predicate excludes all IDs in every enclosing recursive group while traversing that group's RHSs, following CorePrep's `Note [Speculative evaluation]`; blindly using `exprOkForSpeculation` could turn a terminating recursive dictionary into an infinite evaluation. Recursive and global binding initialization still requires the runtime's separate lazy handling. The sixth flag is authoritative when present, so a recursive-scope exclusion cannot be overridden by the WHNF flag. Consumers may use the fifth flag only for older exports without the sixth; both absent means conservative lazy evaluation. Lazy lifted fields and arguments remain lazy; potentially failing unlifted computations such as division by zero do not receive the speculation certificate. Ordinary unknown or saturated function calls remain conservative.

Constructor records also include `fieldReps`, aligned with the same worker slots. Each slot is GHC's actual list of `PrimRep`s, obtained through `typePrimRep_maybe` on the representation argument type and encoded with the canonical GHC 9.14.1 constructor names (`Show PrimRep`). This is type analysis, not parsing printed type text. An empty list means zero-width; a singleton describes one runtime value; multiple entries describe a multi-register representation that the current runtime must reject explicitly. An unresolved runtime representation is JSON `null`, also requiring rejection when reachable. For example, `Box Int#` has `[["IntRep"]]`, while `Cons Box List` has `[["BoxedRep (Just Lifted)"], ["BoxedRep (Just Lifted)"]]`. A void slot has `[]`; a constructor with no fields has an empty outer `fieldReps` list. `fieldLifted` and `strictFields` remain independent metadata: pointer representation does not establish evaluation state or constructor strictness. Unsupported primitive representations must remain errors rather than defaulting to reference storage.

Data-constructor **workers** become constructor expressions. Constructor wrappers remain ordinary variable references and require actual compiled definitions.

Type arguments and type lambdas erase. A type-only application becomes its function. Coercion arguments and coercion lambda binders retain a zero-width `void` slot so Core's value arity conventions remain explicit. Casts and ticks erase from the executable subset and remain visible in `sourceCore`; ticks are not an instrumentation contract in this prototype. Primitive literals retain kind, with integral/character codepoint values in decimal, byte strings in hexadecimal, floating values in decimal. Unsupported literal kinds stay explicit.

## Scope and checks

The runtime selects a root and checks the reachable definitions; it must fail closed for unknown reachable external names, primops, constructor representations or expression/literal forms. Unreachable GHC-generated Typeable metadata remains in the module. Exporting a module is not a claim that the runtime can execute every binding in it.

`export.sh` uses `--make` so imported **source modules** are exported too, and accepts normal additional GHC flags. It does not recover full executable optimized Core for preinstalled library packages from `.hi` files. Boot-library source compilation and package closure extraction are still required for general Haskell programs.

Environment overrides: `GHC` and `GHC_PKG` select the compiler and package-manager executables (both must report 9.14.1); `THC_CORE_OUT` and `THC_GHC_OUT` choose export and object directories. The plugin uses a local package database in `build/compiler/package.conf.d`; it does not modify the user's global GHC package database.

The compiler-only speculation regression runs as part of `scripts/try.sh`. To run it without building the JVM runtime:

```sh
compiler/build.sh
compiler/export.sh compiler/test-fixtures/SpeculationAudit.hs
python3 scripts/check-speculation-metadata.py
```

The check inspects actual optimized Core: safe subtraction and constant nonzero division, rejected division by zero inside a lazy argument, a PAP/lazy field containing bottom, and a saturated recursive DFun call excluded by its enclosing group. This fixture tests exporter analysis; its dictionaries and division primops do not expand runtime support.

## Ordinary Map dependency export

`compiler/export-map.sh` produces `build/map/modules.txt` and `build/map/provenance.json` for `THC.MapWorkload.mapAggregate`. It verifies the SHA256 of the upstream containers-0.8 archive and every vendored file, then compiles the unmodified package sources together with the ordinary workload in one coherent `main` home unit. This exports the complete source bodies of the modules GHC builds; no container operation is a JVM builtin.

The plugin option `closure=mapAggregate` traverses all lexical term references from that root, following complete source definitions recorded during the same compilation. For external definitions it uses GHC's actual `maybeUnfoldingTemplate` on Core/DFun unfoldings. Their `origin` and `originModule` are recorded. Missing interface bodies are reported explicitly; interfaces are not assumed to contain complete packages.

`compiler/export-boot.py` adds the original GHC 9.14.1 `CString` and `Err` source modules under their actual wired `ghc-internal` unit. A private interface overlay points to installed dynamic interfaces; source outputs replace only files in the private build directory. Installed packages are not changed. The `post-tidy` plugin option uses GHC's `latePlugin`/`CgGuts` boundary after Tidy and before CorePrep, explicitly recorded in each module. This retains the real generated private names and implicit bindings needed for package identity; the ordinary application and containers exports remain before Tidy.

A compiler-only interface root exposes the actual installed non-boot exception-construction unfolding, including its real exception/backtrace dependencies. The resulting bundle deliberately records a **bounded compatibility frontier**, not a complete boot library: missing bodies and unsupported operations remain visible in `scripts/audit-core.py --entry mapAggregate --module-list build/map/modules.txt`. GHC 9.14's error construction reaches state, mutable references and backtrace machinery. A successful diagnostic run on inputs that avoid those paths does not establish their support.

`Thc.Wired` performs GHC's canonical late erasure of unary-class constructors/selectors, `lazy`/`noinline`/`nospec`, `runRW#`, and the zero-width real-world state token; ordinary class selectors use GHC's own generator. This is compiler representation lowering, not a replacement library algorithm. Both serialization and dependency discovery apply the same lowering. Rewritten subtrees receive conservative evaluation certificates because representation-erased unary templates are no longer typed Core; they are never inserted back into GHC's optimized program or Core lint.

The Map driver also writes `build/map/reproduction-provenance.json`. It hashes the current bundle, source inputs, exporter implementation, explicit compiler flag recipes, and the installed dynamic interfaces that own the executable unfoldings included in the bundle. Interface coverage is stated explicitly: this is not a complete archive of every transitive interface GHC may consult for types or rewrite rules. It records the selected GHC/package-manager paths, versions, package metadata and GHC build configuration. Both tools are selected consistently through `GHC`/`GHC_PKG` and pinned to 9.14.1.

To supplement an existing measured bundle without recompiling or rewriting its Core JSON, run `python3 compiler/reproduction-provenance.py`. The supplement distinguishes this recording mode from a fresh export; hashes identify the files present at recording time and do not claim that the compilation was replayed. The driver emits the same supplement automatically after a fresh export. GHC uniques may change across recompilations, so this is an input/provenance record, not a promise of byte-identical JSON.

## Fresh-checkout build

Use GHC/ghc-pkg 9.14.1, Python 3.12 or later, and GraalVM 25.3.4.1 with JDK 25. Set `JAVA_HOME` explicitly; scripts never borrow another checkout's JDK. `scripts/try.sh` builds the exporter, generates the real fixture and CString exports required by the JVM tests, creates the native oracle, then runs tests and assembles the application. Gradle downloads dependencies normally; append `--offline` only after populating the cache. `THC_GRADLE_USER_HOME` overrides `GRADLE_USER_HOME`, which otherwise defaults to the project's `.gradle-user-home`.

`scripts/try-map.sh` is also self-contained: it prepares the same test inputs, exports the Map bundle, checks capability gaps, and compares native/guest results. The current known gaps require the explicit diagnostic invocation `THC_DIAGNOSTIC_UNSUPPORTED=true scripts/try-map.sh`; the default strict invocation intentionally stops at the audit. CI runs both entrypoints on Linux and macOS, with pinned compiler/runtime versions and the compatibility audit retained as an artifact.

CI uses the upstream [GraalVM setup action](https://github.com/graalvm/setup-graalvm) with its exact GraalVM `version` and separate `java-version` inputs, and [Haskell setup](https://github.com/haskell-actions/setup) with `ghc-version: 9.14.1`. It verifies the installed release metadata before testing.
