# THC input boundary: optimized Core, CorePrep and STG

Research date: 2026-09-22. This is a design study, not a claim that THC currently implements a compiler.

The user's hypothesis is credible: **rich optimized Core is a better place to retain JVM-specific optimization freedom than final STG**. That does not imply starting from desugared Core, reimplementing GHC's optimizer, or that an STG backend cannot perform well. Most high-value GHC transformations have already happened by the end of the Core optimizer and survive as transformed code in every later representation. The useful question is which remaining choices THC wants to own, and whether their benefits exceed the semantic and maintenance cost.

Recommended design: capture rich optimized Core before Tidy as the canonical source artifact and main JVM lowering input, capture final STG from the same compilation as an operational reference, and compare both through one small THC execution IR. Tooling simplicity may favor an STG-based first runtime smoke test; that is an optional bootstrap route, not a requirement to defer the Core lowering. A bounded Core-to-THC lowering and the STG reference should be measured on the *same programs and runtime*. This is a staged experiment, not a commitment to maintain two optimizing compilers. Preserve the Core view from day one so a convenient early STG test does not irreversibly determine the production boundary. CorePrep is a useful intermediate control and implementation reference, but a less compelling permanent interchange format.

## Evidence and version scope

The GHC 9.10.3 release tag resolves to commit `3f4d7d38b9661435bdde981451ac50c4335ed090`; GHC 9.14.1 resolves to `902339d332fb4ce2b3c87dcac1ee6495d41ad886`. Both were verified with `git ls-remote` against GHC's official GitHub mirror. Selected complete source files were downloaded and inspected under `work/ghc-9.10.3/compiler` and `work/ghc-9.14.1/compiler`. 9.10.3 aligns with the existing whole-program STG exporter's documented invocation; 9.14.1 is the locally available released compiler. This is **not** a claim that the exporter supports 9.14.1.

Links below pin source commits rather than relying on changing `master` or `latest` pages. The relevant pipeline ordering and plugin interfaces were checked in both versions. An example of real internal evolution: 9.10.3's STG pipeline invokes `inferTags`; 9.14.1 invokes `enforceEpt`. An adapter must be versioned even when its exported conceptual schema is unchanged. [9.10.3 STG pipeline](https://github.com/ghc/ghc/blob/3f4d7d38b9661435bdde981451ac50c4335ed090/compiler/GHC/Stg/Pipeline.hs), [9.14.1 STG pipeline](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/compiler/GHC/Stg/Pipeline.hs).

## Name the boundary precisely

The relevant order is:

```text
parse / rename / typecheck / desugar
  -> Core-to-Core pipeline selected by optimization flags
  -> [A: final optimized ModGuts, before Tidy]
  -> Tidy and interface construction
  -> [B: tidied Core / CgGuts; latePlugin runs here]
  -> CorePrep
  -> [C: prepared Core]
  -> CoreToStg
  -> [D: initial STG]
  -> unarise; optional STG CSE and lambda lifting
  -> free-variable annotation and evaluation/tag invariant enforcement
  -> [E: code-generation STG]
  -> Cmm or another backend
```

This ordering is implemented in `hscGenHardCode`, `myCoreToStg` and the STG pipeline configuration. The last STG stage still precedes native stack layout, register allocation and instruction selection. Those native backend optimizations are not inherited by a JVM backend. [GHC 9.14.1 driver](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/compiler/GHC/Driver/Main.hs), [STG pass configuration](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/compiler/GHC/Driver/Config/Stg/Pipeline.hs).

“Optimized Core” must distinguish A from B. In particular, `compileToCoreSimplified` invokes both `hscSimplify` and `hscTidy`; its convenient `CoreModule` result is **not** a snapshot of the richest final optimizer state. `CoreModule` also contains fewer fields than `ModGuts`. [GHC API implementation](https://github.com/ghc/ghc/blob/3f4d7d38b9661435bdde981451ac50c4335ed090/compiler/GHC.hs#L1272-L1332).

## What survives, what changes

The table describes *in-memory GHC values*. An external exporter can discard much more; its schema must be audited independently.

| Property | A: optimized Core before Tidy | C: CorePrep output | E: code-generation STG |
|---|---|---|---|
| Types and representation | Full System FC expressions: type applications/abstractions, casts, coercions, binder types and multiplicities; runtime reps can be computed | Still Core syntax and types, with code-generation invariants imposed | `Id`, `DataCon`, `Type`, `PrimRep` information remains, including selected result types. Type applications/casts no longer form an FC term; tuple/sum unarisation changes the argument structure |
| Strictness and demand | `DmdSig`, binder `Demand`, CPR, occurrence, call-arity and one-shot information where analyses provide them | Retains useful strictness/demand/evaluatedness information, but Tidy has already rebuilt and reduced IdInfo; CorePrep uses it to choose evaluation/binding structure | Demand information may remain on Ids, and its consequences are explicit in evaluation and closure choices. It is wrong to assume every rich Core annotation survives intact |
| Arity | `idArity`, call arity, lambda structure and join arity are distinct | Top-level final arity is respected by eta expansion; join arity receives special handling | Formal argument lists and call structure give operational arity; zero-width arguments and tuple expansion complicate counting |
| Join points | Explicit `JoinId` with saturated-tail-call invariants and join arity | Preserved and adjusted for code generation | `StgLetNoEscape` exposes non-escaping control flow, rather than requiring general heap functions |
| Specialization | Specialized bodies, RULES, unfoldings and inline guidance can be captured | Specialized bodies remain; full unfoldings are trimmed and specialization rules removed | Specialized code remains. Lost rules and original polymorphic structure are not recovered merely by retaining binder types |
| Laziness / closures | Implicit allocation and evaluation decisions still require careful lowering | ANF bindings and cases make many decisions explicit, but no STG update flags/free-variable closure record yet | Closure/function/constructor RHS forms, update flags, free variables, saturated constructor/primop applications are explicit |
| Unboxed aggregates | Tuple and sum types/expressions retain their grouping | Grouping largely remains in typed Core | Unarisation flattens tuple/sum binders and chooses shared payload slots for sums |

The sources supporting these distinctions are [Core syntax and join invariants](https://github.com/ghc/ghc/blob/3f4d7d38b9661435bdde981451ac50c4335ed090/compiler/GHC/Core.hs), [IdInfo definition](https://github.com/ghc/ghc/blob/3f4d7d38b9661435bdde981451ac50c4335ed090/compiler/GHC/Types/Id/Info.hs), [STG syntax](https://github.com/ghc/ghc/blob/3f4d7d38b9661435bdde981451ac50c4335ed090/compiler/GHC/Stg/Syntax.hs), and [unarisation](https://github.com/ghc/ghc/blob/3f4d7d38b9661435bdde981451ac50c4335ed090/compiler/GHC/Stg/Unarise.hs).

### Tidy is already an information boundary

`tidyTopIdInfo` reconstructs top-level IdInfo. Internal names retain arity, demand signature, CPR and minimal evaluatedness; externally visible names retain selected interface-facing information. Top-level rules are extracted separately. For nested let binders, `tidyLetBndr` preserves arity, demand, occurrence information, inline guidance and an unfolding, but removes the demand signature's free-variable environment. Lambda binders use a smaller set including one-shot and evaluatedness information. Call-arity is not restored by these constructors. Thus a `latePlugin` snapshot is useful but not equivalent to a final `CoreDoPluginPass` snapshot. [Top-level Tidy](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/compiler/GHC/Iface/Tidy.hs#L1247-L1283), [nested Tidy](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/compiler/GHC/Core/Tidy.hs#L281-L365).

CorePrep then trims full unfoldings and removes specialization rules; it does **not** erase all IdInfo. Its ANF conversion uses cases for strict arguments and bindings for nontrivial lazy arguments, saturates constructors/primops, moves value lambdas to binding RHSs, and lowers special forms such as `runRW#`. The prepared program still carries types/coercions until CoreToStg. [CorePrep overview and binder cloning](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/compiler/GHC/CoreToStg/Prep.hs).

Do not treat exported usage annotations as timeless facts. GHC's `IdInfo` contract distinguishes definition properties from contextual information such as demand, occurrence, one-shot and call-arity. THC transformations that duplicate bindings, change calls or merge modules must invalidate or recompute affected information. A “one-shot” annotation is not a blanket license to discard sharing. [IdInfo contract](https://github.com/ghc/ghc/blob/3f4d7d38b9661435bdde981451ac50c4335ed090/compiler/GHC/Types/Id/Info.hs#L404-L445).

## How much GHC optimization survives?

The Core pass pipeline includes phased simplification/RULE firing, specialization, floating, call-arity, demand analysis, worker-wrapper/CPR, CSE, and optional higher optimization passes such as SpecConstr and case liberation. It finishes with another demand analysis when required, specifically to repair at-most-once information after transformations. Which passes run depends on flags; “take Core” is not an optimization configuration. [Core pass ordering](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/compiler/GHC/Core/Opt/Pipeline.hs).

**Fusion is not generally a reason to reject STG.** If GHC has fused a producer and consumer before A, the fused loop survives in C and E. Neither A nor E magically recovers missed source-level fusion. A has an advantage when later whole-program specialization or THC rewrites expose *new* opportunities and relevant bodies/rules are retained; exploiting that requires real transformations and renewed analyses.

**Worker-wrapper and specialization are already concrete at A.** Unboxed workers, dictionary elimination and specialized recursive bodies remain available in STG. Rich Core additionally makes type-directed rewrites easier to validate and can retain rules and unfolding guidance. It does not provide a pristine pre-worker-wrapper version automatically. If JVM measurements eventually justify different worker-wrapper, inlining or full-laziness choices, take an additional controlled earlier snapshot or change GHC flags/passes. That is a second experiment, not a benefit one should silently attribute to end-of-pipeline Core.

**Case and closure decisions span boundaries.** Core simplification has already performed many case transformations. CorePrep exposes evaluation order and thunk-producing bindings; CoreToStg makes closure/update choices; later STG passes can remove repeated constructors or lift lambdas. An STG importer still owns physical closure classes, fields, direct calls, frame slots and trampolines. It is not forced to imitate native heap offsets or Cmm stack frames.

These last three paragraphs are design deductions from the inspected ordering, not performance measurements.

## What the JVM gains from choosing earlier

The following are concrete hypotheses to measure, not guaranteed wins:

1. **Preserve unboxed product/sum structure until the JVM calling convention is selected.** A or C can choose scalar arguments, a reusable return carrier, specialized result shapes, or allocation only at escape points. E supplies an already flattened logical argument sequence, and restoring grouping may require metadata. A flattened STG sequence is still usable; it does not require boxing everything.
2. **Place forcing and thunk allocation around Truffle optimization boundaries.** A can combine expressions into one AST region before ANF decisions become opaque. THC could keep strict computations in typed frame slots and create heap thunks only for genuinely delayed escaping expressions. A C/E importer can optimize this too, but must recognize and undo extra scaffolding.
3. **Retain representation/type structure for specialization.** A can specialize JVM entry points or object fields while checking the original FC types. It cannot assume every polymorphic function can be monomorphized or ignore `unsafeCoerce`, representation polymorphism, existential packages or code growth.
4. **Replace native cost assumptions selectively.** Native-oriented lambda lifting trades closure allocation for extra arguments. On the JVM, primitive/object calling conventions, `Object[]` packing, inlining budgets and safepoints may change that tradeoff. STG lambda lifting is an optional pass, so disabling it is an experiment available even to an STG backend.
5. **Treat already-evaluated arguments as an explicit contract.** GHC has worker/join call-by-value marks and evaluation/tagging invariants. THC needs the evaluated/direct-value property, not native pointer-tag bits. Translate the contract to object/primitive entry conventions and enforce it at calls. This information exists beyond Core and must not be dismissed as irrelevant native machinery. [Worker/join contracts](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/compiler/GHC/Types/Id/Info.hs), [9.10.3 tag inference rationale](https://github.com/ghc/ghc/blob/3f4d7d38b9661435bdde981451ac50c4335ed090/compiler/GHC/Stg/InferTags.hs).

The counterweight is substantial: at A THC must implement correct erasure, saturation, ANF/evaluation placement, closure conversion, arity, join lowering, constructor workers, representation lowering and special primitive handling. STG packages many of those obligations into explicit syntax. More retained information does not automatically mean less work or faster machine code.

## Semantic obligations an early boundary does not remove

THC must preserve lazy bindings and sharing. A lazy `let x = expensive in (x,x)` normally creates one delayed shared computation, not two eager calculations and not two independent thunks. A strict case evaluates its scrutinee to the appropriate weak-head or unboxed result and binds fields without recursively normalizing them. Recursive groups require cyclic environments or delayed linking before entry. A function value is already a value; entering a thunk that returns a function does not apply its arguments. All candidate boundaries need exact/under/overapplication and update semantics in the runtime.

STG's `ReEntrant`, `Updatable` and `SingleEntry` flags distinguish function entry, shared thunk evaluation and at-most-once thunk evaluation. In GHC, CoreToStg derives single-entry choices from demand information and maps joins to let-no-escape. THC can adopt these as explicit IR facts, or derive equivalent facts from A with verification. [CoreToStg conversion](https://github.com/ghc/ghc/blob/3f4d7d38b9661435bdde981451ac50c4335ed090/compiler/GHC/CoreToStg.hs).

Two particularly easy mistakes deserve dedicated tests:

- **Coercion erasure is not “remove every coercion lambda and argument.”** GHC drops type arguments, but keeps coercion arguments as zero-width `coercionToken#` values because they affect saturation and `seq`. Removing a coercion lambda can turn a function value into bottom. Separate semantic arity from physical JVM slot count. [Coercion-token note](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/compiler/GHC/CoreToStg.hs#L204-L224).
- **Unlifted is not synonymous with primitive numeric.** Unlifted arrays are references that must not be entered as thunks. GHC's sum unarisation deliberately distinguishes lifted and unlifted pointer slots for this reason. A JVM representation can use references for both while retaining the semantic distinction. [Unarise's lifted/unlifted slot invariant](https://github.com/ghc/ghc/blob/3f4d7d38b9661435bdde981451ac50c4335ed090/compiler/GHC/Stg/Unarise.hs).

Core effects remain encoded by state-token flow and primop/foreign-call semantics. Erasing a zero-width `State#` payload does not authorize reordering effectful operations. This must be represented explicitly in the THC execution IR, whichever input is selected.

## Existing APIs versus proposed tooling

| Mechanism | What exists now | THC work still required |
|---|---|---|
| `Plugin.installCoreToDos` | A plugin can append a `CoreDoPluginPass` to receive final `ModGuts` before Tidy | Versioned rich export schema; ensure the capture really follows all intended passes/plugins; capture module-level metadata and external references |
| `Plugin.latePlugin` | Since 9.10.1, a hook receives `CgGuts` after interface creation and late cost centres, before CorePrep | Useful B snapshot; cannot reconstruct lost pre-Tidy metadata |
| `corePrepPgm`, `coreToStg`, `stg2stg` | GHC library functions available to a version-matched API driver | Driver/configuration plumbing, diagnostics and stage-specific serialization; no claimed stable external ABI |
| `Hooks.stgToCmmHook` | Receives code-generation STG and related metadata on the native Cmm path | Capture or replace that backend path; a driver/phase hook may be preferable for JVM linking and build-product management |
| `compileToCoreSimplified` | Convenient source-to-tidied-Core API | Not sufficient alone for richest metadata or transitive library body extraction |
| `-fwrite-if-simplified-core` | Stores all bindings of each compiled module in its interface, using existing unfolding bodies when available | Rebuild missing packages, hydrate with matching GHC, collect module/link metadata, supplement lost rich information |

The plugin API explicitly cautions that it can change. Use a small Haskell extraction adapter for each supported GHC series and translate into THC's own schema; do not expose GHC's internal binary representation as THC's long-term JVM file format. [Plugin definitions](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/compiler/GHC/Driver/Plugins.hs#L136-L171), [hook definitions](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/compiler/GHC/Driver/Hooks.hs), [Core export flag documentation](https://downloads.haskell.org/ghc/9.14.1/docs/users_guide/phases.html#ghc-flag-fwrite-if-simplified-core).

### Whole-program Core is feasible, but ordinary interfaces are insufficient

GHC already has `WholeCoreBindings`. The 9.14.1 source labels its payload as serialized **tidied** Core; interface syntax serializes a selected IdInfo vocabulary rather than the entire optimizer environment. The writer takes `core_prog` and converts each top binding to interface syntax. This makes the flag a real candidate for a bodies cache and import path, not proof of a rich pre-Tidy export. [WholeCoreBindings](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/compiler/GHC/Unit/Module/WholeCoreBindings.hs), [interface writer](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/compiler/GHC/Iface/Make.hs#L312-L330), [interface information fields](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/compiler/GHC/Iface/Syntax.hs#L452-L470).

A plugin sees modules compiled with it; it does not recover missing bodies from already installed native libraries. Rebuild the relevant boot libraries and packages with consistent export flags or a plugin. Start with dependency closure rooted at `main`, but also retain exports needed by foreign callbacks, static pointers and other runtime roots. Keep package/unit identity, module identity, symbols, imports, constructor/tycon definitions, primop and foreign-call identities, target word size, GHC version, optimization flags, and source/library build hashes. Native C stubs and RTS labels are not Haskell bodies and require explicit runtime/FFI handling. Template Haskell can initially run in the host GHC build; that is distinct from running target Haskell on the JVM.

The **proposed** rich artifact adds a pre-Tidy snapshot, contextual metadata, normalized representations and stable per-module IDs. Preserve a mapping to tidied exported symbols: names and identities change during Tidy, and CorePrep clones local binders. Correlating stages by printed variable names or assuming every local binding has a permanent one-to-one match is unsound. A reference STG snapshot is a separate stage product, with provenance mappings where they can be tracked.

## Decision and experiments

| Candidate | Strongest reason to use it | Principal cost | Recommended role |
|---|---|---|---|
| Rich optimized Core A | Retains types, rules and analysis plus freedom over JVM lowering | Largest lowering/analysis correctness burden; still inherits upstream optimization choices | Canonical research artifact and serious long-term compiler candidate |
| Tidied Core B | Existing late plugin/fat-interface support; still typed | Some rich information already gone | Practical bodies import/cache and comparison control |
| CorePrep C | Typed ANF makes a direct evaluator/backend easier | Earlier allocation/evaluation choices and trimmed metadata, without all STG closure facts | Diagnostic checkpoint and possible transitional importer |
| Initial STG D | Explicit lazy machine constructs before late STG decisions | Needs unboxed aggregate handling and later analysis choices | Optional control if native-oriented late passes prove harmful |
| Final STG E | Most operational obligations already expressed; existing whole-program tooling | Lower information content; native-oriented policies and exporter omissions require care | First executable reference and a viable production choice if experiments favor it |

Do not decide by line count, the age of an IR, or a speculative claim that Graal will erase all allocation. Hold the runtime and program corpus fixed and compare:

1. GHC's emitted semantic structure: lost/retained bodies, rules, arities, joins, update modes, product/sum grouping and primitive requirements.
2. THC generated structure: thunk and PAP creation, constructor allocations, primitive boxing, direct versus generic calls, tail transfers, and AST size.
3. Actual execution: interpreter startup, warmup/compilation time, steady-state throughput, allocation/GC and resident memory, code size and stack safety.
4. Engineering results: export reproducibility, library coverage, adapter maintenance and semantic failures.

The smallest useful boundary experiment uses a program with a polymorphic higher-order helper across a module boundary, a strict numeric loop, a retained lazy list or tree, one shared thunk, a recursive join loop and dynamic application. Include a case where fusion succeeds and a deliberately non-inlined producer where real thunks survive, so a fully fused arithmetic benchmark cannot hide laziness overhead. Compare A-derived and E-derived THC code for those same computations, and compare against the same pinned native GHC with equivalent inputs and optimization settings. A supported primitive-only first run is valuable; it does not establish compatibility with arbitrary `base` IO.

**Decision gate:** keep E as the production boundary if A cannot show a repeatable allocation/boxing/calling-convention benefit that matters on representative programs. Choose A when a small, comprehensible JVM lowering exploits retained structure materially, without needing to recreate most of GHC. If measurements identify one specific lost fact, add that fact to a sidecar rather than reflexively implementing a second full optimizer. Capturing both views now keeps this decision empirical.
