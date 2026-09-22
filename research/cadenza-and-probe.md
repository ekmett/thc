# Cadenza reuse audit and local GHC experiment

Historical research, recorded 2026-09-22 before the THC implementation. This report contains source inspection and a native GHC boundary experiment. See the [current build and status](../README.md) and [Map report](../docs/map-example.md) for subsequent implementation and measurements.

## Workspace and provenance

Commands were run from the THC checkout root. A separate Cadenza checkout was inspected read-only at commit `e2b66e241527cde5d29014af1e4f83d9f2402f88`; `git status --short` was empty before inspection.

Cadenza's actual build pins Kotlin 2.4.20, Graal/Truffle 25.3.4.1, a Java 25 toolchain, and Gradle 9.7.1. These were read from `build.gradle.kts` and `gradle/wrapper/gradle-wrapper.properties`, not inferred from the task description. Its default launch flags include `-Xss32m`; a large host stack is not a proof of stack safety.

The existing Cadenza license file says `UPL-1.0 AND BSD-3-Clause`. If code is copied later, carry the applicable notices with it. No Cadenza implementation code was copied in this investigation.

## Reuse decisions

| Inspected source | Verified behavior | THC decision |
|---|---|---|
| `src/cadenza/jit/dispatch.kt` | Bounded direct-call caches, limit 3; exact, under- and overapplication; indirect fallback; generic overapplication is an iterative loop rather than an unbounded nested cache. | Reuse the dispatch architecture, test its cache saturation, and give the THC variant an explicit argument/representation descriptor. |
| `src/cadenza/data/Closure.kt` | A closure holds an environment, target, remaining arity and PAP array. Partial application copies the captured prefix plus new arguments. PAP construction is marked `TruffleBoundary`. | Separate code metadata, function closure, PAP and thunk identities; preserve lazy values in PAPs. Re-measure the boundary: an escaping allocation is not automatically grounds for excluding its construction from PE. |
| `src/cadenza/jit/code.kt` | `App.executeRands` calls `executeAny` on every operand before dispatch. `executeAny` handles neutral exceptions; it does not implement call-by-need. | Replace operand lowering with atom reads/thunk allocation. Do not transplant this application evaluator as Haskell semantics. |
| `src/cadenza/jit/dispatch.kt` | Overapplication calls the first function, then casts the result to `Closure` except for a neutral-value branch. | Enter the intermediate result to function WHNF before applying the suffix. It may be a thunk returning a function. Remove all neutral handling. |
| `src/cadenza/jit/tail_calls.kt`, `jit/nodes.kt` | Self-tail `LoopNode`, general trampoline, and a bloom-mask scheme that bounds chains of tail calls. Non-tail calls use ordinary host nesting. | Reuse loop/call-node ideas. Add an explicit guest continuation design for pending cases, updates, exceptions and deep non-tail recursion. |
| `src/cadenza/frame/capture_layout.kt` | Constant `StaticShape` layouts and final capture properties; primitive plus object arms accommodate Cadenza's Nat/Bool, neutrals and indirections. | Use GHC runtime-representation metadata to choose THC fields. A lifted `Int` that might be a thunk needs a reference unless evaluation is proved. Cadenza Nat is not Haskell machine `Int`. |
| `test/dispatch.kt` | Tests exceed three argument counts and currying shapes. | Adapt the pattern, adding lazy retained arguments, zero-width arguments, mixed primitive/reference PAPs, and force-to-function overapplication. |

The target cache key may identify code, but an environment belongs to each closure instance. Never cache an instance's captured values merely because several closures share a `RootCallTarget`. Keep mutable thunk state out of `CompilationFinal` fields. Cadenza closure structural equality is not an implementation of Haskell function equality, stable names, or closure identity.

THC has no open symbolic terms and no normalization operation. Remove `NeutralException`, `NeutralValue`, residual ASTs, and the neutral alternatives. Real Haskell exceptions, interpreter-internal tail-transfer mechanisms, and Truffle deoptimization are separate concerns; removing normalization does not remove those obligations.

## What the existing measurements establish

Read `bench/results/2026-09-22/neutral-tracing.md` and its frozen result description at the above Cadenza commit. It reports the retained runtime passing 382 tests and a 10,000-case semantic soak. Those suites were not rerun for this read-only research task.

The archived value-returning neutral experiment reports 16.3–27.7x gains on symbolic microbenchmarks, but its ordinary Fibonacci control was 80% slower with 91% more allocation. Three compiler-visible control-flow-exception variants encountered AArch64 compilation/code-installation failures. These are local measurements of other language/runtime designs; they predict neither a THC speedup nor a THC slowdown. They justify validating ordinary execution, allocation, cache histories, compiler failures and generated code size alongside attractive microbenchmarks.

## Actual GHC 9.14.1 probe

Installed tools observed: GHC 9.14.1 and Cabal 3.16.0.0. GHC package version also reports 9.14.1. Source and generated outputs are in `work/probe-9.14.1/`. The deliberately small `BoundaryProbe.hs` covers a higher-order application, lazy constructor field, shared computation, fused list pipeline, integer worker, local recursive join and unboxed tuple.

Original invocation from the THC checkout root (the local scratch source and generated outputs are not part of the published checkout; see the [exporter instructions](../compiler/README.md) for runnable current examples):

```sh
ghc -O2 -fforce-recomp -dcore-lint -dstg-lint \
  -ddump-simpl -ddump-prep -ddump-stg-from-core -ddump-stg-final \
  -ddump-to-file -fwrite-if-simplified-core \
  -c work/probe-9.14.1/BoundaryProbe.hs \
  -odir work/probe-9.14.1 -hidir work/probe-9.14.1
```

This command actually ran successfully. Both lint options were enabled; the compiler produced no diagnostics. `-ddump-simpl` labels this final dump **Tidy Core**, so it is not a probe of the richer pre-Tidy `ModGuts`. No claim is made that these small dumps cover every Core or STG feature.

Observed comparisons:

1. `fusion` has already lost the `map` and `filter` list intermediates in Tidy Core. Its worker traverses the input spine and carries an `Int#` accumulator. That shape survives into STG. Importing STG does not forfeit the fusion already performed by GHC.
2. Tidy Core represents `unboxed x = (# x, +# x 1# #)` with a nested primitive expression. CorePrep binds the primitive result through a case; STG uses the corresponding atomic operand. A JVM importer from Core must implement equivalent strict primitive evaluation; atomicization is more than flattening syntax indiscriminately.
3. `$wslow` contains a Core `joinrec` and jump. STG contains a `let-no-escape` and a `\\j` entry. Tail control-flow intent survives, even though its representation changes.
4. `$wshared` has a lazy binding used twice in an unboxed tuple. Final STG explicitly marks its closure `\\u []` and both fields reference the same binder. THC must allocate one updateable thunk here, not evaluate twice and not eagerly evaluate while constructing the pair.
5. Final STG's printed binders retain types, arity, strictness, occurrence and join information in this GHC build. It is wrong to say that STG contains no useful type or demand metadata. An external serialization can discard more than the in-memory IR; audit them separately.
6. CorePrep's output changes many executable Core unfoldings into `OtherCon` summaries. It does not erase every `IdInfo` field: strictness and arity remain visible.
7. `choose b = if b then (+) else const` becomes a three-argument worker/wrapper. The worker evaluates the final argument only on the addition branch. Source lambda count, type-arrow count and optimized entry arity are distinct notions.
8. `lazyWitness = const 42 (error ...)` becomes an `I# 42#` constant. It is useful optimization evidence, but is **not** an adequate runtime test that unused arguments remain lazy: the compiler has eliminated the dangerous operand.
9. GHC accepts `-fwrite-if-simplified-core`. `ghc --show-iface` shows an `extra decls:` section in the resulting interface. This is evidence of an available version-specific full-Core mechanism, not a tested portable interchange reader.

Saved files include `.dump-simpl`, `.dump-prep`, `.dump-stg-from-core`, `.dump-stg-final`, `.hi`, `.o`, and `build.log`. The generated object is native GHC code, not JVM code. There was no THC performance result at this research stage.
