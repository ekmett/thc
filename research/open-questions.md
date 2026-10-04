# Open design questions

## Complete useful program closures

The goal is ordinary Cabal applications whose original dependency closures,
including errors and shutdown, execute without diagnostic traps. Package
acquisition uses GHC 9.14.1 post-Tidy, unit-qualified Core; the
[manifest contract](../docs/core-package-manifest.md) separates executable bodies,
identity and foreign requirements.

Which missing original bodies or runtime services close the next useful consumer?
Choose an application entry, audit its reachable closure, and distinguish absent
Core from an unsupported operation or incompatible native product. Keep Setup,
preprocessors and Template Haskell's host execution separate from guest roots.
Native object availability does not supply a missing Haskell body or make a
GHC-RTS-dependent library compatible with THC.

The next increment should preserve the consumer's ordinary Haskell and close its
specific dependencies, including failure paths. A symbol-name shortcut for a
library algorithm would avoid, rather than answer, the compatibility question.

## Cross-module optimization without invalid proofs

Where can whole-program information remove a meaningful call, force or allocation
that GHC's existing optimization has left behind? More retained metadata is not
itself an optimization. Start from the exact [Core evidence](../docs/core-evidence.md)
available after Tidy and identify a concrete transformation and workload.

Changing calls, duplicating bindings or combining modules can invalidate demand,
occurrence and one-shot information. Determine which facts remain valid and which
must be recomputed before using them. A demand to evaluate an argument is not
proof that the incoming expression is already evaluated. Logical arity must also
survive erasure of zero-width State/coercion payloads; lifted and unlifted
references cannot share an indiscriminate forcing path.

Can a bounded specialization improve a real consumer without excessive code
growth or loss of lazy sharing? Test recursive initialization, partial and excess
application, strict failures and lazy fields alongside the intended fast path.

## Resumable effects and bounded-stack execution

Can deep non-tail evaluation inside a transaction gain bounded-stack behavior
while preserving retry, exception and thunk-update semantics? The existing
[STM](../docs/stm.md) and [async](../docs/async-exceptions.md) contracts delimit the
supported paths. A tail-transfer loop alone does not answer the non-tail question.

Which additional foreign transports can honor interruptibility and callbacks
under the same logical guest identity? Specify the cancellation point, resource
owner and completion state before admitting a declaration. Work completed before
delivery must resume from its saved result, not be replayed; clearing an
interrupted thunk back to its original code is not generally safe. Preserve
masked-uninterruptible behavior, waiter notification and ownership transfer.
Carrier-thread interruption is transport plumbing, not the guest exception model.

## Residual calls and allocation

Which costs dominate representative supported workloads: residual call packets,
constructor storage, thunk forcing, or compiler decisions? The
[call ABI](../docs/call-boundaries.md) and [graph tools](../docs/graph-inspection.md)
provide concrete places to investigate; source-level carrier types do not prove
allocation removal.

A known target may still be declined by the inliner. Inspect profile frequencies,
benefit estimates and graph-size cost before raising budgets; extra budget cannot
repair a negative estimate caused by duplicated profiles. Reusable packets need
the [handoff lifetime rules](../docs/handoff-slabs.md), including recursive entry
and retained frames. Cached boxed headers can create object merges on changing
inputs; a partial-evaluation-constant guard may run before useful constants emerge.
Check both paths at the relevant compiler phase.

Does the candidate improve elapsed time against native GHC without excessive
compile cost or code growth? Fewer allocations alone does not establish that.
Keep graph capture separate from timing, reject unstable measurement windows,
and retain first-installed-call checks rather than warming away a failure.

## Eventlog primops through Java Flight Recorder

The original `traceEvent#`, `traceMarker#` and `traceBinaryEvent#` now share the
context-local `THC.Trace` sinks and JFR provider. They start off; enabling tracing
invalidates the initial disabled Graal/Truffle assumption, and disabling or
re-enabling applies to subsequent emissions. The user-tracing flag reflects sink
selection. JFR retains exact payload bytes and context identity without starting
or reconfiguring recordings. See [the tracing contract](../docs/hints-and-tracing.md).

- [ ] Inspect compiler graphs and measure the disabled path's cost before claiming
  that compiled tracing overhead has disappeared. Keep graph capture separate
  from timing and compare the same workload with tracing enabled and disabled.
  Track this performance qualification in [#1058](https://github.com/ekmett/thc/issues/1058).

## Native Image beyond pure interpretation

The [selected-Core cache](../docs/native-code-cache.md) compiles synchronous AST
code ahead of execution and loads it in fresh native processes with guest
compilation disabled, including typed tuple, sum and vector calls, typed boxed
fields and constructor partial applications. How should async continuations,
foreign execution and the full executable lifecycle extend this support while
keeping contexts, CAFs and resource owners local to each load?

Foreign execution needs image-side support for callbacks and resource cleanup;
JVM coverage alone does not establish it. The separate
[pure-image recipe](../docs/native-image-feasibility.md) still needs successful
first installed guest calls to support runtime compilation.
