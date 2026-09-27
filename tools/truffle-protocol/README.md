# Explicit runtime protocol support

THC builds distinct Truffle artifacts from the published 25.3.4.1 source
archives. It never replaces shared Maven cache files. The build checks pinned
source and binary hashes and preserves the upstream notices.

## Bytecode protocol branches

`./gradlew protocolProcessorJar` rebuilds the processor's Java sources, including
its bundled ANTLR runtime, with `unprofiled-if-then.patch`. The pinned binary
supplies only its service registration and expression grammar, which the source
archive omits. Compiled classes are packaged without Gradle's private bookkeeping.

`beginUnprofiledIfThen` has ordinary IfThen control-flow, operand and stack rules,
but its distinct instruction does not count or speculate on branch outcomes.
Only asynchronous runtime protocol polling selects it. Normal guest IfThen
branches retain their existing behavior. This prevents the first real mailbox
arrival from invalidating a target solely because earlier calls received no
request; it does not execute or seed either arm.

The new operation is registered after legacy custom and compatibility operations
so serialized builder event IDs retain their meaning. Instruction kinds are
appended; generated opcode numbers are separate from serialized events.
`./gradlew testProtocolProcessor` checks legacy stock-produced serialized input,
new round trips, ordinary profiling, both protocol directions, clones, source
reparsing, stack rules and invalid operands.

## Materializable root declaration

`./gradlew materializableApiJar` compiles the patched `RootNode`, `NodeAccessor`
and `ContinuationRootNode` sources. The distinct API jar retains every unrelated upstream
class, resource, service, module descriptor and multi-release entry byte-for-byte.
`verifyMaterializableApiSelection` checks that preservation and the runtime
classpath; THC also hard-links the additive policy-version method so stock API
selection fails immediately instead of silently ignoring the override.

A root can declare that its frame may materialize before the call target is
published. The default is false. The declaration uses the existing compiler
materialization flag through the API's frame accessor, including initial and
cloned root setup. It does not materialize a dummy frame, execute guest code,
write profile fields from THC, or change the compiler algorithm. THC bytecode
roots opt in when async or delimited continuation execution is enabled.

The existing `FrameDescriptor.copy()` behavior remains unchanged: a copied
descriptor starts without the observed flag, and a receiving declared root
reapplies its own policy. Reusing a descriptor marked by another root is
conservative. The declaration also selects the existing parent-frame OSR path.

`./gradlew testMaterializableApi` compiles one control binary, then runs it against
the actual stock and declared API jars. It checks pre-execution declaration,
first installed capture, shared/copied descriptors, public split targets and
actual OSR frame transfer. Ordinary first materialization does not universally
require deoptimization; the stock/default controls compare behavior without
inventing that expectation.

These materialization changes preserve continuation token identity and are
independent of the completion declaration below. Declaring materialization can reduce frame
virtualization; no ordinary-allocation or native-image performance claim follows
from the focused JVM controls.

## Polymorphic root completion

A resumable root can return its ordinary scalar/tuple result or a saved
continuation. `requiresUnprofiledReturn()` declares that completion contract
before target publication. It defaults to false; THC selects it only for AST
function roots and bytecode roots with async or delimited execution enabled.
Generated continuation roots inherit their actual source root's declaration,
including clones. No returned value or continuation token is wrapped or replaced.

The pinned runtime samples the declaration in its real-root target constructor,
before adoption or callbacks, and uses its existing polymorphic return state.
Argument and exception profiling remain unchanged. Explicit AOT preparation
retains its normal argument signature and initialization behavior while respecting
the declared completion contract. This does not call AOT preparation implicitly,
execute a dummy guest call, train a branch, or change compiler algorithms.

`./gradlew protocolRuntimeJar` rebuilds `OptimizedCallTarget` and its nested
classes into a distinct runtime jar. `verifyProtocolRuntimeSelection` verifies
pinned inputs, exact artifact selection, and byte-for-byte preservation of every
unrelated upstream entry, including notices, module and multi-release resources.
THC links an additive runtime version method so selecting stock runtime cannot
silently ignore the contract. The original dependency metadata is preserved
explicitly when replacing Maven binaries with file artifacts.

`./gradlew testReturnPolicy testReturnContinuations` checks the same handwritten
control binary against stock and declared jars, with exact first installed entry
counts, unchanged argument class/arity guards, explicit AOT, public splits, and
genuine generated nested yields and serialization. The actual SAFE foreign-call
regression runs both backends and handoff modes with three ordinary calls, then
requires the first captured completion to retain installed code and resume without
replaying the foreign effect. These checks do not warm up a capture transition.

The declaration adds no per-call result allocation. Resumable roots forgo exact
return-class speculation; nonresumable roots retain it. Frame virtualization may
change for materializable roots, so these correctness checks make no throughput
or ordinary-allocation performance claim. Native-image execution of the changed
artifacts requires its own validation; JVM AOT preparation is not that evidence.

## Graph-budget recovery lifecycle

The pinned API/runtime pair also provides an opt-in recovery hook for a real
graph-size bailout. `RootNode.getGraphBudgetGeneration()` supplies the logical
boundary generation captured when a compilation task is submitted. After the
existing block-compilation fallback, `prepareGraphBudgetRetry(failedGeneration)`
may acknowledge a strictly newer generation only when a real structural
compilation boundary has changed. The default returns the failed generation and
preserves ordinary failure handling. This is not a profile hint or permission to
retry an unchanged graph. A language implementation must have a finite extraction
plan and use normal thread-safe AST replacement/invalidation protocols.

The callback runs on the compiler thread. It must not access a current guest
context, execute guest code, initialize a context-bound root, train a profile, or
submit/wait for compilation. Explicit synchronous compilation can submit the
changed root only after the failed task completes. Background compilation leaves
the changed root eligible for a later request; it does not submit another task
inside the callback. A successful recovery does not mark the caller permanently
failed or universally uninlinable. Non-budget failures and exhausted plans retain
the existing policy. Clones sample their current generation on each new task.

`./gradlew testGraphBudgetRecovery` exercises a handwritten Java model with an
actual oversized caller body, a structural replacement by a call to an extracted
side root, completed-task recompilation, and immediate first compiled execution.
It checks exact results, effects, target retention, clones, exhausted plans,
unrelated failure handling, and background submission. The test prepares its
candidate side root in the owning context before compilation and duplicates that
candidate body deliberately. Only the edge introduced after the actual bailout
has an inlining veto. The test's smaller graph budget makes the model bounded;
production graph limits are unchanged.

No THC function root currently overrides this hook. These changes establish the
compilation lifecycle, not automatic case-arm selection, demand-only candidate
allocation, or a complete application graph-budget policy. In particular, a
callee inlining veto alone cannot reduce an oversized caller's own partial
evaluation graph: its body must actually be made smaller. The model has no
continuation or Bloom protocol of its own; existing typed side-root tests cover
those separate runtime contracts.
