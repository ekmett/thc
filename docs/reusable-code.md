# Experimental reusable AST code

`Program.prepareCode(language, module, entries)` uses the existing AST lowerer to
prepare explicitly selected closed roots and their global dependencies. It does
not evaluate their guest bodies. `PreparedCode.newInstance(language)` creates a
fresh ordinary `Program`, global cells, thunks, closures and metrics without
lowering those roots again. Definitions outside the selected dependency set stay
unprepared; asking for one fails rather than lowering it during execution.

Each closure or thunk carries the program instance in its existing
`CapturedFrame`. Even capture-free roots have an owned empty environment. Root
entry checks the capture layout, code identity and current context owner; a global read then uses its
fixed binding index in that instance. No context-wide binding table or per-call
owner wrapper is involved. Partially applied closures retain the same environment.

This is an incremental ownership split, not an AOT command or a second backend.
Current admission covers closed machine-word arithmetic functions and thunks with
explicit Long input proofs,
global references and scalar literals, using normal validation and lowering.
Constructor, foreign, nested-closure and general application paths are not yet
admitted. Nonliteral strict globals are rejected before initializer construction:
preparation must never execute their bodies. The language uses `SHARED` metadata,
while every load still allocates its own program and runtime resources. Prepared
code can be instantiated only with its original language in that language's
currently entered context. A fresh instance may reuse code after its preparation
context closes, but another context's instance cannot enter that code. Bytecode
code reuse is not yet enabled; ordinary cached sources still construct fresh AST
or bytecode programs on every load, including repeated loads within one context.
Shared executable roots retain no context compilation-owner token. Their compiler
events are deliberately outside per-context JIT telemetry; ordinary roots and
clones retain explicit ownership. This never changes per-instance guest metrics.

In particular, asynchronous force/resumption, tail-transfer instrumentation,
typed aggregates, constructor ownership and foreign bridges still need explicit
instance routing before those families can be admitted.

Admitted reusable AST roots implement context-independent `prepareForAOT` using
their declared machine-word argument/result proofs and existing frame carriers.
The environment's concrete StaticShape subclass is not an exact declared ABI
class; its signature entry remains unknown, with layout and owner checks intact.
Prepared roots demand unevaluated scalar operands directly instead of speculating
that a cold CAF is already a primitive. This structural preparation mode does not
execute guests or seed the ordinary JIT's observed profiles. Ordinary roots do
not claim this preparation support. Actual THC auxiliary-cache persistence and
fresh-process execution with lowering/compilation prohibited remain separate work.

The same-context JIT control declares one call using its first instance before
installation, then requires the untouched second instance's first call to enter
and retain the original compiled target. That is not a zero-training AOT claim.
