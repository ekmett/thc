# Delimited continuations

`newPromptTag#`, `prompt#`, and `control0#` follow the pinned GHC 9.14.1
signatures. Prompt identities are opaque and context-owned. `control0#` removes
the nearest matching prompt and gives its handler a reusable continuation of
the saved suffix; it does not restart the action. Intervening nonmatching
prompts belong to that suffix. The continuation can escape its original prompt.

Each invocation copies the saved control-local frame graph. Guest heap objects,
including MutVars and closure captures, remain shared. Saved frames and owned
tuple values do not retain reusable handoff loans. Captured exception handlers
receive exceptions from the replacement IO action. Mask return frames restore
the mask captured inside the segment, rebasing its outer return to the resumer's
ambient mask. With no mask return frame, the replacement action inherits that
ambient mask instead.

Both backends resume actual executable suffixes. Bytecode uses cloned Truffle
continuation frames and their continuation roots. AST nodes retain explicit
remaining case/result steps. The internal capture exception is an unwinding
transport, not a substitute for saved continuation state. This multi-shot image
does not reuse the existing one-shot asynchronous continuation owners. Saved AST
scope recipes are immutable; each invocation instantiates its own pending steps,
so consuming one invocation does not change a later invocation's nested scopes.

## Supported behavior and limits

With `asyncExceptions: false`, direct `killThread#` self-delivery reaches both
live and saved `catch#` frames, including delivery from a replacement action or
a resumed suffix. The handler acknowledges the original request without forcing
its payload; mask return frames and handler exit restore their proper scopes.
Repeated invocations copy the frame graph, not the one-shot delivery request.
An uncaught request propagates unchanged for an outer handler.

With async enabled, a live or saved catch also handles an external delivery
reported by its interrupted action. The child retains its one-shot parked
continuation; the current invocation unwinds the original async request through
its cloned suffix. Only the reached handler acknowledges it, after checking the
current logical target and claimed state. The payload stays lazy. Non-delivery scheduling cuts and AST stack spills in a replacement action or
saved suffix use a fresh one-shot call owner for that invocation. The existing
iterative driver completes its saved callers before the image advances; neither
the owner nor its mutable completion state is copied into the reusable image.
An async delivery reached while draining still unwinds the original request to
its handler.
Saved local-join transfers retain their region around an interrupted body, so
later lexical jumps and scalar/tuple result completion remain owned by that
region. Transferred roots detach their tuple results after scheduling completes,
before another saved step can execute guest code or acquire a result loan.

Strict scalar-returning workers preserve their pending case caller, including
its result destination. Capturing a resumed segment again freezes the mask
return's already-rebased prior state: if another mask frame becomes outermost,
the inner return must not revert to the first capture's ambient state.

Saved function and lexical-join owners also handle tail/self/join transfers
without discarding the remaining caller suffix or restarting the original action. A resumed dense-ABI function detaches its old result destination
before reentering its body.

Overapplications retain the not-yet-consumed arguments and resume the existing
dispatcher at that logical offset, after the suspended callee has returned its
function. AST typed calls preserve flattened operand fields and the final
callee's result descriptor, including tail bounces and independently prepared
compatible layouts. Direct and generic calls use the same saved-completion
protocol. The captured tuple consumer runs once; bytecode receives an owned
result at its saved call site. Neither path reruns the callee prefix.

Fresh `control0#` inside an internally parked AST invocation translates its
remaining AST callers into reusable frame steps, innermost first. The consumed
child edges and their one-shot owners are not captured. Mask, annotation and
cleanup scopes remain part of the suffix; a matching prompt inside an existing
image still separates the captured suffix from its once-only outer continuation.
An interrupted operand records its pending write and remaining preparation;
saved scalar/tuple application prefixes copy their private argument arrays on
resume, while the referenced guest values remain shared across invocations.
Bytecode aggregate-input capture remains unqualified. Bytecode parked caller
conversion and arbitrary hand-built yielded capture markers remain unsupported; ordinary bytecode saved delimited suffixes are separate and supported.
Caught delivery abandons its interrupted child, rather than resuming it.
This is not complete delimited-continuation support. Capturing through a thunk
update rejects explicitly; GHC also excludes update/STM/foreign stack barriers
from valid capture. Unmatched prompts are outside GHC's defined domain, not a
portable exception API supplied here. Cross-context tags/resumptions reject.

The source contract comes from pinned `compiler/GHC/Builtin/primops.txt.pp`,
`rts/Continuation.c`, and `rts/ContinuationOps.cmm`. The
[GHC proposal](https://ghc-proposals.readthedocs.io/en/latest/proposals/0313-delimited-continuation-primops.html)
explains the higher-level design; the pinned implementation determines masking
and invalid-capture behavior.
