# Executable GHC bytecode objects

`newBCO#` and `mkApUpd0#` execute a scalar GHC 9.14.1 BCO slice on both THC
Core backends. This is GHC's bytecode format, not the Truffle Bytecode DSL's
format. [GhcBCO.hs](../examples/GhcBCO.hs) constructs actual instruction,
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
unlisted instructions are unsupported. Asynchronous suspension and delimited
capture through the BCO interpreter reject rather than discard pending stack
work. The instruction loop
polls Truffle safepoints; it does not impose an arbitrary instruction budget on
valid loops. No BCO JIT performance claim is made.

## Checks

```sh
cabal run exe:thc-fixtures --offline -- ghc-bco
./gradlew --continue testDefault --tests thc.runtime.GhcBCOTest \
  testDense --tests thc.runtime.GhcBCOTest
```

The producer retains 24 native results, 16 strict audits, unmodified pre/post
Core, command stdout/stderr and SHA-256 source/artifact provenance in
`build/ghc-bco/`. Kotlin's arithmetic/state model is independent of runtime
execution. Native comparisons run in both backends; each first-installed check
compiles exactly the genuine public Core root and requires exactly one compiled
entry and no interpreted call to that root, with no intervening guest call.
This does not claim compilation of the dynamically allocated BCO or all helper
roots. Handoff pools must be balanced and free of retained references.

Additional JVM cases cover malformed streams, odd-sized/truncated encodings,
stack order, signed extremes, logical shifts, raw NaN bits, lazy/shared thunk
updates, released update targets, and foreign/closed-context use.

The checkpoint passes 19 tests in each handoff mode (seven BCO tests, nine
handoff tests and three tuple-completion tests) from one compilation in
`20260926-062844-yrec0b6y`. Each mode compares 96 interpreted observations and
32 first-installed public entries across both backends and Core stages.
The final producer is `20260926-062810-m8wluvmf`; its closed 82-file cache
payload verifies. The 287 fast CI checks and 16 coverage checks also pass.

Earlier evidence is retained: `20260926-062336-i5oo_5s9` exposed a KAPT
generated-node inference issue, fixed by declaring the existing Dispatch node
array type. `20260926-062451-h1r5isn1` passed the native comparisons but found
that foreign-root lookup hit Truffle's sharing-layer assertion before the
explicit ownership check. BCO entry now checks the active context first; the
unchanged RuntimeFault expectation passes, including after the owner closes.
The original XML is retained under `build/ghc-bco/failed-first-focused/`.
