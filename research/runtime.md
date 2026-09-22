# THC lazy JVM runtime sketch

Research date: 2026-09-22. This is an architecture proposal, not an implemented runtime or a claim of GHC compatibility. It accompanies the Core-first compiler-boundary proposal; the runtime consumes a typed, representation-explicit lazy IR. GHC STG provides a reference for operational invariants without requiring THC to use STG as its interchange format.

## Recommendation

Build an ordinary lazy Haskell evaluator with immutable function/constructor metadata, heap closures, updatable thunks, typed application packets, and an explicit guest continuation stack. Let GHC perform the Haskell frontend and optimization work. Lower the resulting program to Truffle roots and local control-flow loops, with runtime operations for entering closures, applying functions, and resuming continuations. A value that is not yet evaluated is a thunk; there are no neutral terms, normalization, or `NeutralException` semantics.

The first implementation should favor an inspectable, stack-safe state machine over recursive Java evaluation. Truffle can optimize well-shaped hot regions, but a generic dispatch loop is not automatically a competitive Haskell compiler. Preserve enough static information to optimize known calls, primitive representation paths, constructor layouts, and local loops later.

Use the locally verified Cadenza dependency line, Graal/Truffle **25.3.4.1**, as the experiment's consistent baseline. The public `vm-25.3.4.1` tag resolves to [`7b025988a922a73286d1326e1eddc1ca39d3f569`][graal-pin]. The official [25.3 release notes][graal-release] confirm matching component versions. This report inspects GHC 9.10.3 at [`3f4d7d38b9661435bdde981451ac50c4335ed090`][ghc-pin], matching the compiler-boundary research, and Eta at [`97ee2251bbc52294efbf60fa4342ce6f52c0d25c`][eta-pin] (2019-07-16). Eta is historical source evidence for JVM design choices, not a current drop-in RTS.

## Objects and the runtime contract

A compact initial object model is:

| Runtime object | Required information and behavior |
|---|---|
| `Function` | Immutable code descriptor, environment, logical entry arity, argument/result representation signature. Already in WHNF. |
| `PAP` | Base function, captured argument packet, remaining logical arity. Already in WHNF. Flatten PAP-on-PAP application instead of indefinitely nesting wrappers. |
| `Constructor` | Immutable constructor descriptor and payload. Descriptor includes type identity, constructor identity/tag and field representations. Already in WHNF; fields may remain unevaluated. |
| `UpdatableThunk` | Code/environment plus a state cell: unevaluated, evaluating with owner, evaluated indirection, or memoized synchronous failure. |
| `SingleEntryThunk` | Code/environment with no success update, when the compiler proves single entry. A debug mode can diagnose a violated proof. |
| `Indirection` | A result reference followed iteratively; can be encoded in thunk state instead of as an additional heap object. |
| Primitive runtime values | Typed references for arrays, mutable variables, foreign memory, stable handles and eventually threads/transactions; primitive scalars remain unboxed where possible. |

An applied expression delayed by the compiler is an updatable application thunk, not a PAP. A PAP lacks arguments; a delayed saturated application has enough arguments but has not been entered. GHC distinguishes these as PAP versus AP and also has AP_STACK for suspended computation; THC needs these semantic distinctions without copying C memory layouts. GHC's [closure definitions][ghc-closures] and Eta's [Function][eta-function], [PAP][eta-pap] and [PAPSlow][eta-papslow] show the distinction concretely.

Keep constructor and code descriptors immutable and shared. Constructor payloads can initially use a small tagged record with reference and primitive slot arrays; later specialize common layouts or use generated classes/Truffle static objects. Do not require one Java class per source constructor in the first loader. Nullary constructors can be canonical context-owned objects. Constructor tags are meaningful within their declared type; a lone integer tag is not a globally unique constructor identifier.

An `Int` constructor containing an `Int#` stores an unboxed integer, while a list node normally stores references to element and tail closures. Do not force every constructor field or argument simply because a Java expression computes it. The typed lower IR must say whether an operand is a reference, an already unboxed scalar, or an expression that must be suspended. GHC's [STG syntax][ghc-stg] uses atom arguments precisely because suspended expressions have already become closures. Strict fields and worker/wrapper transformations should be reflected in the imported representation and explicit evaluation order.

All mutation belongs to runtime state objects or a documented initialization phase. Never mark an updatable thunk's result `@CompilationFinal` merely because it usually changes once. Immutable descriptors are good partial-evaluation constants; independently changing heap cells are not. Context-owned CAF tables also prevent one embedding context's evaluation results and effects leaking into another.

## Enter, case, and application

`Enter` evaluates a lifted closure to WHNF. It iteratively follows indirections, returns functions/PAPs/constructors unchanged, or enters a thunk under an update continuation. It does not recursively normalize constructor fields. `Case` enters its lifted scrutinee, binds the resulting value, selects the correct constructor/literal/default branch, and binds fields without independently forcing lazy fields. An unboxed case consumes its specified scalar or return packet directly.

`seq` demands WHNF, so a function or PAP passes without running its body. A constructor containing bottom also passes. A bottom-valued thunk does not. Missing code or an unsupported primitive is a loader/runtime error with a symbol and source location, never a neutral value.

Application should have one semantic implementation and specialized fast paths:

1. Enter the operator to a callable value. Do not enter ordinary lifted arguments.
2. For a PAP, combine its saved arguments with the new packet and recover the base function/signature.
3. If fewer logical arguments are supplied than required, allocate a PAP and return it in WHNF.
4. If exactly enough are supplied, execute that entry with its environment and typed argument packet.
5. If too many are supplied, push an `ApplyRest` continuation holding the excess, execute the saturated prefix, enter the intermediate lifted result, and continue application. If a particular entry convention already guarantees WHNF, that `Enter` is cheap. An arbitrary result cannot simply be cast to a function.

Known saturated calls bypass generic arity dispatch after importer validation. Unknown calls use a small call-site cache keyed by code descriptor/entry signature, then a generic fallback. Never key the cache by the entire closure: many environments share one entry body. GHC's [Apply.cmm][ghc-apply] is the semantic reference, while Eta's [Function.java][eta-function] demonstrates exact/under/overapplication and its [Thunk.java][eta-thunk] demonstrates forcing an operator before applying it.

A zero physical-slot argument is not necessarily no argument. In post-unarise STG, GHC deliberately retains void arguments in `StgApp` and `StgOpApp` to compare application arity, while dropping them from saturated constructor applications and constructor alternatives. That rule is explicit in [GHC.Stg.Syntax][ghc-stg]. THC should document separately:

- erased type parameters and type applications from Core;
- logical argument positions used by its application convention, including zero-width coercion/value arguments;
- physical argument/result slots, including flattening of multi-component representations.

Coercion proofs need no stored payload, but coercion lambdas must not simply disappear like type lambdas. GHC treats them as value lambdas and passes zero-width `coercionToken#` arguments: erasing the lambda can turn a function in WHNF into bottom and change `seq`. Preserve that saturation boundary in the lower IR, as described by GHC's [coercion-token note][ghc-coercion-tokens].

Those counts coincide for simple lifted examples and diverge for real GHC output. Copying an `Object[]` and taking its length is not a sufficient ABI.

## Sharing, recursion, and thunk updates

Allocate recursive groups in two phases: reserve every closure or environment cell in the group, then fill references and publish the group only after initialization. This supports mutually recursive functions and cyclic data such as `ones = 1 : ones`. Use a construction-only mutable environment or indirection cells; do not pretend an incompletely linked recursive environment is immutable to the optimizer.

A top-level function is an immutable code/environment object. A top-level non-function computation is normally a CAF with a shared context-owned update cell. Module loading allocates and links it without evaluating its body. Reachability and unloading can initially be simple: a loaded context owns its CAF roots until context close. Later reduce retention when measurements justify it. After a thunk updates, clear obsolete environment references where safe so the old captured graph can be collected; Eta's [Thunk.java][eta-thunk] explicitly provides clearing for this reason.

For milestone zero, use one guest evaluator and eager blackholing: record the currently evaluating guest thread before evaluating an updatable thunk, push `Update(thunk)`, and on successful lifted return publish an indirection. Reentering the same active thunk signals the runtime's nontermination condition, rather than recursing indefinitely on the Java stack. On synchronous guest failure, update to a raising closure or stored guest failure so subsequent demands reproduce the failure instead of repeating evaluation. GHC's [raise closure in Exception.cmm][ghc-exception] and Eta's [thunk exception handling][eta-thunk] provide concrete precedents.

Keep update flags from the compiler when available. [GHC.Stg.Syntax][ghc-stg] distinguishes `ReEntrant`, `Updatable`, and `SingleEntry`: reentrant closures are not updated or blackholed, updatable ones are shared, and single-entry ones need no result update. Do not treat every zero-argument closure as a memoizing thunk or every heap object as callable.

Later parallel evaluation requires atomic state transitions with acquire/release publication, guest owner identity, waiters, and wakeups. Java monitors or VarHandles can implement these mechanisms, but holding a monitor while running arbitrary Haskell code is the wrong abstraction. Claim, release the lock, evaluate, then publish/wake. A different guest thread encountering a blackhole waits; it is not necessarily a recursive loop. A same-thread recursion check is not a complete deadlock detector. Eager blackholing is a reasonable first strategy; reproducing GHC's lazy-blackholing optimization is not a prerequisite for ordinary pure Haskell semantics. [GHC Updates.h][ghc-updates] and [Eta CAF.java][eta-caf] show the actual ownership/publication machinery that a concurrent implementation must account for.

## Typed ABI and unboxed values

Choose and record a target word size in the module format; the first JVM target should be explicitly 64-bit. For this target, `Int#`/`Word#` fit Java `long`, `Float#`/`Double#` fit `float`/`double`, and narrower machine primitives use explicit masks/sign extension. Keep unsigned operations distinct from signed ones. Follow each GHC primop's contract for overflow, division, shifts, conversion, NaNs and signed zero instead of substituting whichever Java operator looks similar.

Represent references and primitive payloads in separate lanes or fixed typed fields. Eta's [ArgumentStack][eta-argstack] and [StgContext][eta-context] illustrate mixed primitive/reference argument and result storage. THC should not copy Eta's mapping of machine integers blindly: the chosen target and GHC `PrimRep` determine the width.

An unboxed tuple is multiple return components, not a lazy boxed tuple constructor. An unboxed sum has a discriminant and a representation-defined payload layout; if the frontend has already flattened it, preserve that layout. A single logical value can require multiple slots. `State#` and `Void#` have no physical payload. `Addr#` needs an explicit foreign-address policy, not a Java object reference reinterpreted as an address. Distinguish lifted and unlifted references so `Enter` is not applied indiscriminately. GHC's [representation utilities][ghc-reptype] and [primitive definitions][ghc-primops] are the authority.

For the first ABI, use `ArgPacket(signature, refs, scalarLanes)` and `ReturnPacket(resultSignature, refs, scalarLanes)` or equivalent typed machine registers. A caller with pending work must spill live return/argument state before a nested entry can overwrite it. Return-packet allocation is an implementation cost to measure, not a semantic necessity: typed result slots and specialized roots can remove it later.

Truffle's `CallTarget` interface accepts Java object arguments and returns an object. That does not prohibit primitive frame slots or scalar replacement after inlining, but it does not guarantee boxing will disappear. A universal `Object[]` path is acceptable as an initial correctness bridge, not evidence of low allocation. Guest references must remain ordinary JVM references visible to the Java collector; do not encode movable JVM addresses into `long` slots.

## Stack safety: more than tail calls

Use an explicit guest execution state containing the current code/label, environment, typed argument/result registers, and a heap-resident continuation stack. Initially, each inter-closure transfer returns a status to an outer dispatch loop. Each code root executes a bounded region until it returns a result, requests entry/application of another closure, raises, or blocks. The Java stack depth therefore does not grow with guest call depth.

The minimum continuation variants are `Case` (branch target and live locals), `ApplyRest` (excess arguments), `Update` (shared thunk), and `ReturnToHost`. Later add `Catch`, masking restoration, transaction, foreign-call, and suspended-evaluation frames. Frames contain copied live values and code/label identities, not a captured Java stack. Do not retain a `VirtualFrame`: its [API expressly requires materialization before escape][graal-virtualframe]. Prefer explicit compact guest frames over materializing every Truffle frame, because their liveness and resumption contracts stay under THC's control.

A tail call replaces the current code and arguments without pushing a continuation. A local join point becomes a branch with simultaneous assignment of arguments. Self-recursive and suitable mutually recursive local joins become a `LoopNode` over a fixed code region, allowing Truffle loop profiling and OSR. An escaping closure remains a closure; a let-no-escape/join binding need not allocate one. The [LoopNode API][graal-loop] and [OSR guide][graal-osr] explain the available loop machinery; they do not supply Haskell control-flow semantics.

Non-tail lazy forcing needs the guest stack even when all tail calls are trampolined. For example, evaluating a long `foldl (+) 0 xs` chain must preserve pending arithmetic and update work; recursively calling Java `force` from `force` overflows. A deep tree's pending `case` branches cause the same problem. Replacing only tail calls with `ControlFlowException` is insufficient. Catching `StackOverflowError` and restarting a Haskell computation is unsound once effects or partially performed updates exist.

This conservative machine creates a performance tension: a single megamorphic global dispatcher can hide call-site identity and prevent useful cross-function partial evaluation. Address that deliberately, rather than asserting the trampoline is free:

- Lower local joins and known loops into one root so the hot path stays in a statically visible region.
- Keep original call-site IDs/signatures in transfer descriptors for profiling and specialization.
- Cache known continuation/entry targets and avoid allocating a transfer object on every step; an execution-state tag plus typed registers suffices.
- Add fused hot regions or bounded direct-call fast paths only after the baseline works. Any bounded host-call path must fall back at an explicit continuation boundary before stack exhaustion. It may not unwind arbitrary Java frames and replay effects.

`DirectCallNode` supports inlining and call-site-sensitive cloning, while `IndirectCallNode` is the dynamic fallback; their [pinned][graal-direct] [APIs][graal-indirect] make that distinction explicit. A machine that returns to a generic dispatcher at every guest call may sacrifice those benefits. Measure this as an architectural risk in the first experiment. It is a better initial failure mode than claiming general stack safety from a tail-only trampoline. Eta's [Stg.java][eta-stg] is useful evidence for counted tail bouncing, while its [update and trampoline bookkeeping][eta-context] shows that updates must survive a bounce; it is not proof that all non-tail Java recursion is stack safe.

## Haskell libraries versus runtime services

Compile ordinary Haskell as ordinary Haskell: `map`, `foldr`, typeclass dictionaries, `Maybe`, custom data structures, monad combinators, exception wrappers and most library control flow should arrive as code and closures. Do not invent Java implementations keyed by familiar names such as `Prelude.map` or `IO.bind`. A constructor worker or wired-in type descriptor is metadata; an arithmetic primop or foreign call is a runtime boundary.

The loader should maintain an explicit registry of supported primops, foreign imports and runtime symbols, each with a representation signature and effect classification. It must reject an unresolved reachable dependency before execution, with the importing symbol and missing service. Support should expand from a measured reachable dependency manifest. Passing one example does not imply its whole package, much less Hackage, is supported.

The hard service families are:

| Family | Required work beyond ordinary compiled Haskell |
|---|---|
| Arithmetic and `Integer` | Primitive arithmetic, exact conversions, overflow/failure behavior. `ghc-bignum` needs its actual backend/limb contract; a blanket Java `BigInteger` substitution is not an ABI. |
| `IO` and `ST` | Preserve effect sequencing when zero-width state tokens disappear; mutation, host entry, error translation, resource lifetimes and external services. The action body remains guest code. |
| Mutable references and arrays | Array element laziness, typed loads/stores, mutability/aliasing, unsafe freeze/thaw semantics, atomic operations, size/index behavior. |
| Exceptions | Guest exception payloads, catch continuations, synchronous update behavior, masking and async delivery later. Internal Java bugs and VM errors must not accidentally become Haskell exceptions. |
| FFI and addresses | Symbol resolution, native calling convention, marshalling, safe/unsafe/interruptible behavior, callbacks, stable pointers and keep-alive obligations. |
| Weak references/finalizers | GHC weak-key/value/finalizer semantics, not simply one Java `WeakReference` per guest object; stable identity and finalizer scheduling. |
| Concurrency/STM | Guest scheduling, blocking/wakeup, MVars, TVars, transaction logs and retry; Java synchronization alone does not implement these contracts. |
| Newer runtime features | Pinned GHC primops include continuation operations such as `prompt#`/`control0#`; unsupported operations require explicit rejection until implemented. |

GHC's [primop table][ghc-primops] distinguishes read/write effects and raising operations and describes array pinning, `keepAlive#`, weak pointers, stable handles, exceptions and STM. Its [IO module][ghc-io] documents the relationship between library wrappers, primitive types and RTS behavior. Erasing the state token is safe only if the lowered control flow preserves the sequencing those operations require. Do not mark a mutating or raising operation as pure in a Truffle specialization.

For arrays, Java primitive/reference arrays are a reasonable heap representation where their contract matches. Native pinned byte arrays require stable external storage and lifetime management; a Java array is not automatically nonmoving native memory. Passing a copied array is valid only when the required aliasing and mutation semantics allow it. A foreign binding layer (for example an explicit FFM/NFI adapter) is useful plumbing, but importing a native Haskell library still requires its complete RTS and representation expectations to match. Do not promise that loading a GHC object file makes it callable inside THC.

Synchronous failure can be carried as an explicit machine outcome. Java exceptions can be an implementation mechanism at a controlled boundary, but distinguish guest raises, internal transfers, foreign failures, cancellation and JVM errors. GHC's [Exception.cmm][ghc-exception] makes the update-list behavior concrete. `error`, `throw`, and `throwIO` are not all equivalent eager Java throws: compiled wrapper strictness and pure-versus-ordered exception semantics matter.

## Concurrency, STM, and asynchronous exceptions

Treat these as a later RTS milestone, not a few extra builtins. A guest thread needs a continuation stack, masking state, pending asynchronous-exception queue, blocked reason and thread identity. A Java thread or virtual thread can be the carrier, but cannot replace guest semantics. GHC `throwTo`, interruptible waits, bound foreign calls and shared thunk ownership do not follow automatically from `Thread.interrupt()`.

Truffle contexts are single-threaded unless the language explicitly allows multithread access and performs initialization; see [TruffleLanguage][graal-language]. Its [safepoint API][graal-safepoint] requires regular polling and a blocking protocol. Integrate those with guest scheduler polls at loop backedges, entry boundaries and blocking operations. A Truffle safepoint is an opportunity to service guest events; it is not itself Haskell masking or async-exception delivery.

Asynchronous cancellation of a thunk evaluator must **not** permanently memoize the delivered asynchronous exception in that shared thunk. Another thread may legitimately demand the same computation later. Nor may the runtime simply reset every interrupted thunk to its original code and restart: effects already performed inside evaluation must not be replayed. GHC's [RaiseAsync.c][ghc-async] captures active continuation segments into AP_STACK closures when unwinding update frames so suspended evaluation can resume. THC's explicit guest stack should support the analogous suspended state and owner transfer. Implement exact masking/handler restoration and notification of blackhole waiters with it. A guest synchronous exception has a different update policy.

For STM, implement versioned TVars or equivalent transactional state, read/write logs, validation, commit, rollback, `retry` wait sets and the alternative semantics of `orElse`/`catchRetry#`. A lock around a Haskell action does not implement retry, rollback or atomic compositional choice. GHC's [STM.c][ghc-stm] and Eta's [STM.java][eta-stm] are implementation references. General continuation capture, STM and async suspension share machinery but require distinct semantics.

Milestone zero can honestly omit guest concurrency, async exceptions, STM, callbacks and general FFI. Reject their reachable primitives. Host cancellation can terminate/dispose the whole initial context; exposing that as recoverable Haskell `throwTo` would overclaim support.

## Graal optimization and the HotSpot fallback

On matching GraalVM 25.3.4.1, Truffle partial evaluation can specialize the interpreter with respect to immutable guest code, inline cached direct calls, eliminate intermediate allocations when they do not escape, and optimize primitive loops. GHC has already removed much high-level abstraction, so preserving its worker representations is particularly valuable. Keep parsing/loading, symbol lookup and large native-service helpers outside hot partial-evaluation paths, while leaving ordinary arithmetic and closure access visible. Use compilation logs, inlining logs, allocation measurements and graphs to check what actually happened; the [pinned optimization guide][graal-opt] describes these tools.

Do not conflate Graal as a Java host JIT with Truffle guest compilation. The [host-compilation document][graal-host] distinguishes optimizing the Java interpreter from partially evaluating guest code. Normal HotSpot C1/C2 can optimize the Java implementation and hot paths, but it does not perform Truffle's guest partial evaluation. The guest semantics should remain correct there, with potentially much lower throughput.

There is a version-sensitive deployment constraint: beginning with Polyglot 25.1, the optimizing Truffle runtime is supported only on GraalVM 25.1 or later; plain OpenJDK/Oracle JDK use the fallback runtime, and adding the old `jargraal` compiler artifact is no longer a supported way to enable optimization for that line. The [25.1 release notes][graal-runtime-policy] also introduce `Engine.supportsCompilation()`. For this 25.3.4.1 experiment, assert/report that capability and demonstrate a guest root actually compiling. Keep API/runtime/compiler dependencies matched. A standard-JDK fallback test proves portability/correctness, not optimized guest execution. THC's Java-25 bytecode target is separate from the Truffle fallback runtime's own minimum-JDK compatibility.

Expected favorable cases are hot primitive loops, monomorphic constructor/closure access, and known saturated calls with removable temporary objects. Expected difficult cases are allocation-heavy lazy graphs, polymorphic higher-order calls, repeated PAP construction, huge imported functions, non-tail forcing with many live continuations, heavy synchronization, and cold short-lived programs. Java GC manages memory, but closure size, array indirection, write barriers and retained CAFs still matter. No numeric claim relative to native GHC is warranted before measurement. New Graal loop vectorization is not evidence that arbitrary lazy Haskell automatically vectorizes.

## Concrete milestone and evidence

**M0: compiled Haskell kernel invoked from Java.** Compile actual GHC-accepted source through the chosen exporter, load its generated IR, and invoke an exported entry through a Java harness. Use a small module set with explicit imports and no unnecessary Prelude dependencies. Include user-defined data types, recursion, polymorphism and dictionaries; use a deliberately small supported primitive set. The Java harness may inspect a returned scalar/constructor without requiring `print` or full `base` IO. It must not implement the Haskell functions being tested.

M0 requires the object/application/update machine, typed scalar and lifted-result ABI, recursive linking/CAFs, local loops and a fully explicit continuation fallback. It includes load-time validation and informative rejection of unimplemented services. A small set of synchronous raise tests is useful if the exception payload/handler subset is explicitly defined; otherwise make it a named next increment rather than claiming exception compatibility.

The same fixtures should run through native GHC as a differential reference, with optimization both disabled and enabled where the export path permits. Include:

- Laziness: `const 1 undefined`; a constructor with an unused divergent field; an unused CAF; `seq` of a function/PAP and of bottom.
- Sharing: force the same updatable thunk twice and use runtime entry counters to prove one body execution. Do not introduce `unsafePerformIO` only to observe sharing in the initial pure subset.
- Cycles: a productive cyclic list prefix, mutually recursive functions, and an actual self-recursive thunk.
- Application: exact, under, repeated PAP application and overapplication whose intermediate result needs entering; mixed primitive/reference arguments and a zero-width logical argument.
- Representation: unboxed tuple return, constructor with an unboxed field, signed/unsigned boundary cases, and unboxed sums if their lower-IR support is claimed.
- Stack behavior: a very long tail loop, mutual tail calls, long indirection chain, and deep non-tail forcing under a small Java stack. Check guest continuation growth, not merely completion under a large `-Xss`.
- Runtime mode: matching GraalVM with an observed guest compilation and standard HotSpot fallback with the same result; record warmup, steady-state throughput and allocation separately.

**M1: conventional `Main` with a narrowly ported library slice.** Compile genuine Haskell implementations of the reachable `base` code for a program using `getArgs`/`print`, and implement the resulting finite primitive/foreign/runtime service manifest. Character encoding, handles, exception behavior, argument access, integer formatting and initialization are concrete dependencies to discover, not assumed available because the source says `print`. Keep unsupported dependencies as explicit errors. This is the first credible conventional-program demonstration; it is still not unrestricted GHC IO, FFI, concurrency or package support.

The experiment should decide whether Truffle's specialization can recover enough performance from the explicit-stack design before expanding the RTS surface. Correct lazy semantics and a small compiled program are feasible; building the operational compatibility needed for broad GHC libraries is the dominant long-term engineering risk.

## Pinned primary references

[ghc-pin]: https://github.com/ghc/ghc/commit/3f4d7d38b9661435bdde981451ac50c4335ed090
[ghc-coercion-tokens]: https://github.com/ghc/ghc/blob/3f4d7d38b9661435bdde981451ac50c4335ed090/compiler/GHC/CoreToStg.hs#L206-L221
[ghc-stg]: https://github.com/ghc/ghc/blob/3f4d7d38b9661435bdde981451ac50c4335ed090/compiler/GHC/Stg/Syntax.hs
[ghc-closures]: https://github.com/ghc/ghc/blob/3f4d7d38b9661435bdde981451ac50c4335ed090/rts/include/rts/storage/Closures.h
[ghc-apply]: https://github.com/ghc/ghc/blob/3f4d7d38b9661435bdde981451ac50c4335ed090/rts/Apply.cmm
[ghc-updates]: https://github.com/ghc/ghc/blob/3f4d7d38b9661435bdde981451ac50c4335ed090/rts/Updates.h
[ghc-reptype]: https://github.com/ghc/ghc/blob/3f4d7d38b9661435bdde981451ac50c4335ed090/compiler/GHC/Types/RepType.hs
[ghc-primops]: https://github.com/ghc/ghc/blob/3f4d7d38b9661435bdde981451ac50c4335ed090/compiler/GHC/Builtin/primops.txt.pp
[ghc-exception]: https://github.com/ghc/ghc/blob/3f4d7d38b9661435bdde981451ac50c4335ed090/rts/Exception.cmm
[ghc-async]: https://github.com/ghc/ghc/blob/3f4d7d38b9661435bdde981451ac50c4335ed090/rts/RaiseAsync.c
[ghc-stm]: https://github.com/ghc/ghc/blob/3f4d7d38b9661435bdde981451ac50c4335ed090/rts/STM.c
[ghc-io]: https://github.com/ghc/ghc/blob/3f4d7d38b9661435bdde981451ac50c4335ed090/libraries/ghc-internal/src/GHC/Internal/IO.hs
[eta-pin]: https://github.com/typelead/eta/commit/97ee2251bbc52294efbf60fa4342ce6f52c0d25c
[eta-function]: https://github.com/typelead/eta/blob/97ee2251bbc52294efbf60fa4342ce6f52c0d25c/rts/src/main/java/eta/runtime/apply/Function.java
[eta-pap]: https://github.com/typelead/eta/blob/97ee2251bbc52294efbf60fa4342ce6f52c0d25c/rts/src/main/java/eta/runtime/apply/PAP.java
[eta-papslow]: https://github.com/typelead/eta/blob/97ee2251bbc52294efbf60fa4342ce6f52c0d25c/rts/src/main/java/eta/runtime/apply/PAPSlow.java
[eta-thunk]: https://github.com/typelead/eta/blob/97ee2251bbc52294efbf60fa4342ce6f52c0d25c/rts/src/main/java/eta/runtime/thunk/Thunk.java
[eta-caf]: https://github.com/typelead/eta/blob/97ee2251bbc52294efbf60fa4342ce6f52c0d25c/rts/src/main/java/eta/runtime/thunk/CAF.java
[eta-context]: https://github.com/typelead/eta/blob/97ee2251bbc52294efbf60fa4342ce6f52c0d25c/rts/src/main/java/eta/runtime/stg/StgContext.java
[eta-argstack]: https://github.com/typelead/eta/blob/97ee2251bbc52294efbf60fa4342ce6f52c0d25c/rts/src/main/java/eta/runtime/stg/ArgumentStack.java
[eta-stg]: https://github.com/typelead/eta/blob/97ee2251bbc52294efbf60fa4342ce6f52c0d25c/rts/src/main/java/eta/runtime/stg/Stg.java
[eta-stm]: https://github.com/typelead/eta/blob/97ee2251bbc52294efbf60fa4342ce6f52c0d25c/rts/src/main/java/eta/runtime/stm/STM.java
[graal-pin]: https://github.com/oracle/graal/commit/7b025988a922a73286d1326e1eddc1ca39d3f569
[graal-release]: https://www.graalvm.org/release-notes/25.3/
[graal-runtime-policy]: https://www.graalvm.org/release-notes/25.1/
[graal-opt]: https://github.com/oracle/graal/blob/7b025988a922a73286d1326e1eddc1ca39d3f569/truffle/docs/Optimizing.md
[graal-host]: https://github.com/oracle/graal/blob/7b025988a922a73286d1326e1eddc1ca39d3f569/truffle/docs/HostCompilation.md
[graal-osr]: https://github.com/oracle/graal/blob/7b025988a922a73286d1326e1eddc1ca39d3f569/truffle/docs/OnStackReplacement.md
[graal-loop]: https://github.com/oracle/graal/blob/7b025988a922a73286d1326e1eddc1ca39d3f569/truffle/src/com.oracle.truffle.api/src/com/oracle/truffle/api/nodes/LoopNode.java
[graal-direct]: https://github.com/oracle/graal/blob/7b025988a922a73286d1326e1eddc1ca39d3f569/truffle/src/com.oracle.truffle.api/src/com/oracle/truffle/api/nodes/DirectCallNode.java
[graal-indirect]: https://github.com/oracle/graal/blob/7b025988a922a73286d1326e1eddc1ca39d3f569/truffle/src/com.oracle.truffle.api/src/com/oracle/truffle/api/nodes/IndirectCallNode.java
[graal-virtualframe]: https://github.com/oracle/graal/blob/7b025988a922a73286d1326e1eddc1ca39d3f569/truffle/src/com.oracle.truffle.api/src/com/oracle/truffle/api/frame/VirtualFrame.java
[graal-language]: https://github.com/oracle/graal/blob/7b025988a922a73286d1326e1eddc1ca39d3f569/truffle/src/com.oracle.truffle.api/src/com/oracle/truffle/api/TruffleLanguage.java
[graal-safepoint]: https://github.com/oracle/graal/blob/7b025988a922a73286d1326e1eddc1ca39d3f569/truffle/src/com.oracle.truffle.api/src/com/oracle/truffle/api/TruffleSafepoint.java
