# Residual call boundaries

Truffle 25.3.4.1 has no public primitive-argument or primitive-result `CallTarget` entry. Typed locals and bytecode operations can eliminate boxes inside a compiled region; a residual call still uses `Object[] → Object`. This is a source/graph audit, not a new timing result.

## The pinned ABI

These are the signatures in `org.graalvm.truffle:truffle-api:25.3.4.1`:

```java
Object CallTarget.call(Object... arguments);
Object DirectCallNode.call(Object... arguments);
Object IndirectCallNode.call(CallTarget target, Object... arguments);
Object RootNode.execute(VirtualFrame frame);
Object BytecodeRootNode.execute(VirtualFrame frame);
```

The optimized runtime preserves the `Object[]` argument ABI and reference-result ABI through `OptimizedCallTarget.callDirect(Node, Object[])`, `doInvoke(Object[])`, `callBoundary(Object[])` and `profiledPERoot(Object[])`. `callInlined(Node, Object[])` also takes an array; its benefit comes from merging the callee into the compilation region, where allocations may disappear. Argument and return profiles refine reference types without changing these entry signatures.

The bundled GraalVM **25.3.4.1+1.1** `lib/src.zip`, under `jdk.graal.compiler/jdk/graal/compiler/truffle/`, makes the compiler side explicit:

- `KnownTruffleTypes.java:177–183` resolves the methods above with `Object_Array` arguments.
- `PartialEvaluator.java:456–457` selects `OptimizedCallTarget_profiledPERoot` as the compilation root.
- `TruffleTierContext.java:152–158` describes finalizing calls that were not inlined from `callDirect` down to the call boundary.

THC already uses a [Java bridge](../src/main/java/thc/runtime/Calls.java) to avoid Kotlin's defensive vararg copy. Its [saturated dispatcher](../src/main/kotlin/thc/runtime/Application.kt) constructs the header/prefix/argument packet before `DirectCallerNode` calls that bridge.

Cadenza uses the same convention: [`dispatch.kt:63–66`](https://github.com/ekmett/cadenza/blob/e2b66e241527cde5d29014af1e4f83d9f2402f88/src/cadenza/jit/dispatch.kt#L63) and [`CallUtils.java:10–17`](https://github.com/ekmett/cadenza/blob/e2b66e241527cde5d29014af1e4f83d9f2402f88/src/cadenza/jit/CallUtils.java#L10). Its [`frame_assembly.kt`](https://github.com/ekmett/cadenza/blob/e2b66e241527cde5d29014af1e4f83d9f2402f88/src/cadenza/frame/frame_assembly.kt#L85) generates typed payload fields and constructors, not a primitive call entry; the builder's `execute` remains `todo` at lines 192–193. It is not an active call optimization missing from THC.

## A typed packet would still need an array

A fresh immutable carrier with primitive fields could combine several `Long` boxes into one payload object, passed inside an outer `Object[]`. That is a supported representation change, with fewer objects possible when a call has several primitive arguments. It does not eliminate the outer array or the reference result ABI.

The frozen typed-frames-v4 `THC.MapWorkload` Core has these relevant worker signatures:

| Captured root | Value parameters |
| --- | --- |
| Lookup, `lambda def, ww, ds1` | `Int`, `Int#`, `Map Int Int` |
| Fold, `lambda ww, ds` | `Int#`, `Map Int Int` |
| Insert, `lambda sc, x, ds1` | `Int#`, `Int`, `Map Int Int` |
| Adjust, `lambda ww, ds2` | `Int#`, `Map Int Int` |
| Worker, `lambda ww` | `Int#` |

Each has one primitive formal. A typed carrier replaces one box with one carrier, leaving the allocation count unchanged and adding a field indirection; any byte-size or speed benefit needs measurement. The Core file is `work/typed-frames-v4/map/12-THC.MapWorkload.json`, SHA-256 `71d2e304b0a434a8bbf29970532b8c4fdca0216f30c21df9d97784159b1fffa7`; the [published runtime/corpus manifest](../bench/results/entry-contracts/graphs/candidate/runtime-manifest.json) records its provenance.

## Packet reuse needs a lifetime contract

An array local to one caller activation avoids shared-global reentry corruption, but can still remain visible through a retained callee frame. The pinned class files establish the distinction:

- `FrameWithoutBoxing.<init>(FrameDescriptor, Object[])` stores the supplied array directly at bytecode offset 136; `getArguments()` returns that reference. `materialize()` marks the descriptor and returns `this` at offsets 10–11, without copying arguments.
- `OptimizedCallTarget.callInlined` passes its original array to `createFrame`. `profiledPERoot` first calls `injectArgumentsProfile`.
- With compiled code and a valid argument profile, `castArgumentsImpl` creates another `Object[]` and casts/copies the elements. That copy may itself virtualize. Interpreter and absent/invalid-profile paths return the original array instead.
- `OptimizedFrameInstance.getFrameFrom(..., MATERIALIZE)` exposes a materialized frame; it supplies no caller-side packet-copy notification.

Thus a retained frame from one iteration can observe later overwrites of a reused outgoing packet. This does **not** mean all frames escape, or that entry always copies arguments. It means correctness cannot rely on the conditional profile copy. Reuse requires a supported retention/snapshot protocol; clearing slots after return does not supply one.

For exact pinned class identification, SHA-256 of `FrameWithoutBoxing.class` is `ecb9022348bb9d84433188344306136822a024da1d7414e16b118e0da7f50c79`; `OptimizedCallTarget.class` is `077aa9bf0f53669d597dce6beffb772d522d3e515756d0da828662c20e33e28a`. They are from the 25.3.4.1 API/runtime artifacts, inspected without launching a JVM.

## Unmeasured candidate: inline a trivial case arm

The [actual fold SSA](../bench/results/entry-contracts/graphs/candidate/fold-packet-ssa.json), compilation 3236, shows primitive accumulator phi 5289 boxed at 5466, then packet commit 5467 producing `[zero header, boxed accumulator, child 5184]`. Array 5468 reaches residual call target 5355 inside the native loop. The header box folds later; the accumulator box and packet survive. The child reaches the packet without a preceding child-constructor test. These IDs identify only this capture.

The corresponding Core worker `$wgo13_s14JW` starts with a case whose `Tip` arm returns its primitive accumulator unchanged. Its `Bin` arm performs genuine non-tail left recursion; right recursion already becomes a local backedge. A bounded transformation for an **exact known callee with a trivial leading-case arm** could test `Tip` in the caller before constructing the packet. The trivial arm would return the primitive directly; the other arm would retain the existing call and fresh packet.

This must preserve entry forcing, evaluation order, constructor identity, errors and source/debug attribution. PAPs, overapplication and unknown targets should initially retain ordinary dispatch. It adds a branch to the nontrivial path, so the graph opportunity is not a speedup claim. The first check would be a graph with no packet, box or call on the trivial arm, followed by semantic tests and separate allocation/steady-state measurements. A generic partial-inlining rule is preferable to a Map-specific intrinsic; converting non-tail recursion into an explicit continuation stack is a larger, separate change.
