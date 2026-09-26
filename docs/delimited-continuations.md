# Delimited continuations: initial synchronous slice

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
does not reuse the existing one-shot asynchronous continuation owners.

## Established slice and remaining work

The [Haskell examples](../examples/DelimitedContinuations.hs) cover plain prompts,
aborting a suffix, two resumes with shared state and an unrepeated prefix,
nonmatching and same-tag nested prompts, captured catch/mask boundaries, an
escaped continuation resumed twice, and ambient masking. These are synchronous
IO examples, on both AST and bytecode backends.

Saved function and lexical-join owners also handle tail/self/join transfers
without discarding the remaining caller suffix. The recursive examples exercise
both a separately called worker and an exported local join; neither restarts the
original action. A resumed dense-ABI function detaches its old result destination
before reentering its body.

Overapplication with unfinished applications and composition with one-shot asynchronous suspension remain
unestablished and require additional runtime work. This checkpoint must not be
described as complete delimited-continuation support. Capturing through a thunk
update rejects explicitly; GHC also excludes update/STM/foreign stack barriers
from valid capture. Unmatched prompts are outside GHC's defined domain, not a
portable exception API supplied here. Cross-context tags/resumptions reject.

The source contract comes from pinned `compiler/GHC/Builtin/primops.txt.pp`,
`rts/Continuation.c`, and `rts/ContinuationOps.cmm`. The
[GHC proposal](https://ghc-proposals.readthedocs.io/en/latest/proposals/0313-delimited-continuation-primops.html)
explains the higher-level design; the pinned implementation determines masking
and invalid-capture behavior.

## Reproduction and evidence

```sh
cabal run exe:thc-fixtures --offline -- delimited-continuations
./gradlew test --tests thc.runtime.DelimitedContinuationsTest --rerun
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew test --tests thc.runtime.DelimitedContinuationsTest --rerun
```

The Haskell producer retains the native GHC invocation, its 33 results, original
pre/post Core, 22 strict entry audits, command stdout/stderr, and source/artifact
SHA-256 provenance under `build/delimited-continuations/`. The Kotlin arithmetic
and shared-state model is independent of THC execution. Each mode checks 132
interpreted observations and 44 first-installed executions with exact guest-root
entry deltas, no intervening guest calls, and balanced argument/result pools and
masking state. These checks establish this fixture's scope, not universal
continuation correctness or allocation-free capture.

Initial focused default/dense passes were retained in resource logs
`20260926-051951-x7hnpdx9` and `20260926-052047-uptvlfsq`. An earlier compiled
capture failed because it constructed a FrameDescriptor in compiled code;
`20260926-051910-1ztmxwb0` and its Graal diagnostic zip remain retained. The
fix places dynamic continuation-root creation behind a Truffle boundary.

The control-flow follow-up retains two actual failures: in
`20260926-053113-din7uo2q`, native `resumedTail(-2)` returned 115 while the AST
returned 15, because the tail trampoline bypassed the saved caller's `+100`.
In `20260926-053424-0wjtmonb`, a captured local-join jump escaped as a host
exception. Saved owner boundaries handle both transfers without weakening the
expected results or entry counters. Frame-image and closed-context tests also
check independent local copies, shared heap identity, and rejected foreign
prompt/continuation use.

The broader control-flow checks also caught a compilation regression before
publication: adding capture handlers to ordinary 25-deep nonrecursive joins
produced five-node compiled roots and no compiled entries. The exact published
baseline passed that unchanged test (`20260926-054117-vka_f2n9`); a raw-entry
restoration experiment did not fix the regression and was discarded. Capture
handlers now follow the existing whole-linked-program delimited-control flag,
keeping ordinary scalar/join graphs unchanged. The original 34-test
continuation, tail, join, and handoff regression set then passed
in default (`20260926-054517-3kkiyaot`) and dense handoff mode
(`20260926-054616-bc11zf1a`). No test assertion or guest warmup was changed.
