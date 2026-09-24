# Compiled thunk retention

The boxed-array and scalar-floating native suites exposed the same first-call
compiled-code retirement on GraalVM 25.3.4.1. The boxed-array host target retired
on `boxedSTRecursive(Long.MAX_VALUE)`; `floatComparisons(-16777216)` retired its
guest target, making the following row fail its compiled-entry counter check.
Both paths had already been exercised in the interpreter.

HotSpot compilation logs identified `Force.execute` at the return from
`executeOne` and at its state-2 cached-thunk exit. The parsed Graal graphs
identified the precise speculation as `IntrinsifyFrameAccessor`, with
`RuntimeConstraint` deoptimizations on the retry loop's exits. This was not a
floating comparison, boxed-array operation, or missing warmup branch.

The suspended-child path unnecessarily materialized the caller frame before
calling `resumeChain`. That helper passed the frame into `executeOne`, which
never used it. Merging this materialized frame with the ordinary return paths
prevented the compiler from preserving frame-access assumptions. Removing the
unused helper arguments and materialization restores those assumptions. The
continuation still owns its captured callee frame; ownership, waiting, failure
publication, and resumption behavior are unchanged.

`CompiledThunkRetentionTest` makes each failure the first invocation after one
explicit compilation and checks the host, original guest target, active direct
target identities, and compiled-entry count before and after every native row.
It covers both AST and bytecode, pre/post boxed-array Core, and Float/Double NaN
branches. The original suite assertions remain unchanged. No settling calls,
recompilation, disabled speculation, or reduced retention requirements are used.

Run it alongside `BoxedArrayTest`, `FloatingPrimitiveTest`,
`ResumableThunkProofTest`, `ThreadedThunkTest`, and `ThunkRetentionTest` in both
handoff modes, after preparing the existing boxed-array and floating fixtures.
