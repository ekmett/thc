# Experimental reusable AST code

`Program.prepareCode(language, module, entries)` uses the existing AST lowerer to
prepare explicitly selected closed roots and their global dependencies. It does
not evaluate their guest bodies. `PreparedCode.newInstance(language)` creates a
fresh ordinary `Program`, global cells, thunks, closures and metrics without
lowering those roots again. Definitions outside the selected dependency set stay
unprepared; asking for one fails rather than lowering it during execution.

Each closure or thunk carries the program instance in its existing
`CapturedFrame`. Even capture-free roots have an owned empty environment. Root
entry checks the capture layout and code identity; a global read then uses its
fixed binding index in that instance. No context-wide binding table or per-call
owner wrapper is involved. Partially applied closures retain the same environment.

This is an incremental ownership split, not an AOT command or a second backend.
Current admission covers closed machine-word arithmetic functions and thunks,
global references and scalar literals, using normal validation and lowering.
Constructor, foreign, nested-closure and general application paths are not yet
admitted. Neither bytecode reuse nor cross-context sharing is enabled; the
production language policy remains `EXCLUSIVE`.

In particular, asynchronous force/resumption, tail-transfer instrumentation,
typed aggregates, constructor ownership and foreign bridges still need explicit
instance routing before those families can be admitted. Context-independent
`prepareForAOT` and actual THC auxiliary-cache persistence are separate work.
The same-context JIT control declares one call using its first instance before
installation, then requires the untouched second instance's first call to enter
and retain the original compiled target. That is not a zero-training AOT claim.
