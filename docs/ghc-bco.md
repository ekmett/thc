# Executable GHC bytecode objects

`newBCO#` and `mkApUpd0#` execute a scalar GHC 9.14.1 BCO slice on both THC
Core backends. This is GHC's bytecode format, not the Truffle Bytecode DSL's
format. [GhcBCO.hs](../src/examples/GhcBCO.hs) constructs actual instruction,
literal, bitmap and pointer arrays with primops and runs them under both GHC
and THC. It includes boxed one-/two-argument calls, a positive-arity BCO,
unboxed arithmetic, a branch, a large operand and an updating computation.

A BCO is an ordinary THC closure with an executable interpreter root.
Positive-arity application uses the existing closure/PAP/application convention.
`mkApUpd0#` accepts only a zero-arity BCO and produces the existing lazy updating
thunk: construction does not enter the code, repeated forcing shares the
answer, and a successful update releases the old target/environment. Each
invocation owns its operand stack. The immutable decoded code/literals and
lazy pointer table are retained by the BCO, without inventing numeric addresses
for guest references. Calls and wrappers reject a foreign context.

## Format and scope

The implementation follows pinned GHC `rts/include/rts/Bytecodes.h`,
`rts/Interpreter.c`, `rts/PrimOps.cmm`, `compiler/GHC/ByteCode/Asm.hs` and
`libraries/ghci/GHCi/CreateBCO.hs`. The latter's
[updating-CAF explanation](https://downloads.haskell.org/ghc/9.14.1-rc3/docs/libraries/ghci-9.14.0.20251128-6f9b/src/GHCi.CreateBCO.html)
also distinguishes positive-arity functions from zero-arity updating wrappers.
The checked local 9.14.1 sources, not another release's opcode table, determine
the wire format used here.

The current format is native-endian Word16 instructions with 64-bit literals
and a full `StgLargeBitmap` (size followed by bitmap words). Execution starts
at Word16 offset zero. `LARGE_ARGS` operands combine four Word16s in
most-significant-first order. Jumps address instruction boundaries, not bytes.
Each input currently occupies one stack word, and the bitmap distinguishes
references from raw scalar words; zero-width, aggregate and multiword input
layouts are not admitted. Host memory references never become raw word bits.

Supported instructions are `STKCHECK`, `PUSH_L/LL/LLL`, `PUSH_G`, `PUSH_UBX`,
`PUSH_APPLY_P` through `PUSH_APPLY_PPPPPP`, `SLIDE`, `TESTLT_I/TESTEQ_I`,
`TESTLT_W/TESTEQ_W`, `JMP`, `SWIZZLE`, `ENTER`, `RETURN_P/N/F/D/L`, `BCO_NAME`,
and the 64-bit arithmetic, bit, shift and comparison opcodes. Shift counts
must be in GHC's defined domain. `CASEFAIL` fails explicitly. Decoding rejects
unknown opcodes, truncated operands, out-of-range tables and jumps into operands;
execution checks live stack bounds and pointer/word boundaries.

This is not a complete GHCi implementation. Native calls, RTS info-table
construction/PACK, allocation/AP-building opcodes, case-continuation BCOs,
packed subword stack operations, tuple/vector returns, breakpoints and other
unlisted instructions are unsupported. One-shot asynchronous suspension and
bounded stack cuts retain
the instruction position, operand stack, pending application arguments and the
interrupted force or call. Repeated cuts resume the remaining work without
replaying completed effects. Constructor operands resume through the same AST
operand protocol. BCO continuations check their context owner before claiming
the one-shot state; masks, annotations and shared thunk updates use the existing
continuation machinery. Explicit delimited capture through BCO frames remains
unsupported, including the ordinary prohibition on capture across thunk updates.
The instruction loop polls the guest scheduler and asynchronous request queue as
well as Truffle safepoints; valid loops have no arbitrary instruction budget.
No BCO JIT performance claim is made.

## Checks

```sh
cabal run exe:thc-fixtures --offline -- ghc-bco
./gradlew --continue testDefault --tests 'thc.runtime.GhcBCO*' \
  testDense --tests 'thc.runtime.GhcBCO*'
```

The producer retains 24 native results, 16 strict audits, unmodified pre/post
Core, command stdout/stderr and SHA-256 source/artifact provenance in
`build/ghc-bco/`. Java's arithmetic/state model is independent of runtime
execution. Native comparisons run in both backends; each first-installed check
compiles exactly the genuine public Core root and requires exactly one compiled
entry and no interpreted call to that root, with no intervening guest call.
This does not claim compilation of the dynamically allocated BCO or all helper
roots. Handoff pools must be balanced and free of retained references.

Additional JVM cases cover malformed streams, odd-sized/truncated encodings,
stack order, signed extremes, logical shifts, raw NaN bits, lazy/shared thunk
updates, released update targets, and foreign/closed-context use.

The genuine corpus runs with synchronous and capturing callers. Separate JVM
continuation checks use real MVar interruptions through both Core backends:
repeated cuts, overapplication with an outer pending Apply frame, initial and
returned thunk forcing, tail transfers, strict direct/megamorphic arguments,
constructor operands and instruction-loop interruption followed by completion.
A deep BCO chain checks bounded stack spilling and result completion. Foreign
resumption rejects before claiming the continuation, leaving the owner able to
resume it. These focused controls establish continuation behavior; they do not
claim a compiled BCO interpreter or complete GHCi/Template Haskell execution.
