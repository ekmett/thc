# Executable GHC bytecode objects

`newBCO#` and `mkApUpd0#` execute a GHC 9.14.1 BCO subset on both THC
Core backends. This is GHC's bytecode format, not the Truffle Bytecode DSL's
format. [GhcBCO.hs](../src/examples/GhcBCO.hs) constructs actual instruction,
literal, bitmap and pointer arrays with primops and runs them under both GHC
and THC. It includes boxed one-/two-argument calls, a positive-arity BCO,
unboxed arithmetic, branches, large operands, shared updates, nested scalar cases,
internal tuple calls, packed subwords and padded floats.

A BCO is an ordinary THC closure with an executable interpreter root.
Positive-arity application uses the existing closure/PAP/application convention.
`mkApUpd0#` accepts only a zero-arity BCO with an empty entry bitmap and produces the existing lazy updating
thunk: construction does not enter the code, repeated forcing shares the
answer, and a successful update releases the old target/environment. Each
invocation owns its operand stack; saturated/overapplied BCO calls and case continuations
share that stack until they return. The immutable decoded code/literals and
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

Qualification uses 64-bit little-endian Linux; big-endian execution has not been
qualified. The format is native-endian Word16 instructions with 64-bit literals
and a full `StgLargeBitmap` (size followed by bitmap words). Execution starts
at Word16 offset zero. `LARGE_ARGS` operands combine four Word16s in
most-significant-first order. Jumps address instruction boundaries, not bytes.
Logical arity and bitmap-covered stack width are independent: a zero-arity case
continuation describes saved free variables, and a tuple helper describes its
call descriptor and result words. The shared stack is byte-addressed, retaining
raw bytes separately from managed reference words. Opaque return/apply headers
occupy the native word positions needed by `SLIDE` and local offsets, but cannot
be read as numeric addresses or returned as guest pointers. Bitmap checks reject
reference/raw-word mismatches, including boxed numeric objects used as raw bits.

Ordinary external function entry still requires one physical 64-bit word per
argument. Zero-width, aggregate and multiword input layouts need an argument ABI
adapter; this restriction belongs to that entry path, not to continuation or
bitmap construction.

Supported instructions are `STKCHECK`, `PUSH_L/LL/LLL`, `PUSH_G`, `PUSH_UBX`,
`PUSH8/16/32`, `PUSH8/16/32_W`, `PUSH_PAD8/16/32`, `PUSH_UBX8/16/32`,
`PUSH_ALTS_P/N/F/D/L/V/T`,
`PUSH_APPLY_P` through `PUSH_APPLY_PPPPPP`, `SLIDE`, `TESTLT_I/TESTEQ_I`,
`TESTLT_W/TESTEQ_W`, `JMP`, `SWIZZLE`, `ENTER`, `RETURN_P/N/F/D/L/V/T`, `BCO_NAME`,
and the 64-bit arithmetic, bit, shift and comparison opcodes. Shift counts
must be in GHC's defined domain. `CASEFAIL` fails explicitly. Decoding rejects
unknown opcodes, truncated operands, out-of-range tables and jumps into operands;
execution checks live stack bounds and pointer/word boundaries.

This is not a complete GHCi implementation. Native calls, RTS info-table
construction/PACK, allocation/AP-building opcodes, unboxed apply frames,
breakpoints and other unlisted instructions are unsupported.

Scalar cases use the pinned interpreter's two-word continuation headers and
P/N/F/D/L/V return layouts. Internal tuple returns use its four-word receiving
header and three-word returned header, with the tuple BCO bitmap and exact
`call_info` register/spill descriptor checked at both ends. Saturated and overapplied BCO calls
preserve the receiving frame, including mixed reference/raw-word payloads.
Packed pushes use byte offsets and native byte order; padding does not change
arity or create a reference. Float entry and return place the four value bytes
at `Sp` before four padding bytes, rather than treating them as a low-half integer.

An external Core tuple return still requires a checked ABI adapter. GHC's
`call_info` and pointer bitmap describe physical stack/register occupancy, not
THC's logical result shape: zero-width fields disappear, signed scalar species
share words, and scalar float/double registers overlap. Inferring a `TupleShape`
from the caller alone would bypass producer validation. This implementation
therefore rejects a tuple return without a matching BCO continuation. Exporting
and checking the producer's logical result metadata is a subsequent requirement.
The pinned GHC bytecode producer itself rejects SIMD return/alternative
conventions (`Asm.hs` uses only `SCALAR_ARG_REGS`); this work adds no invented
vector convention. One-shot asynchronous suspension and
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

The producer retains 63 native results, 42 strict audits, unmodified pre/post
Core, command stdout/stderr and SHA-256 source/artifact provenance in
`build/ghc-bco/`. Java's arithmetic/state model is independent of runtime
execution. Native comparisons run in both backends; each first-installed check
compiles exactly the genuine public Core root and requires exactly one compiled
entry and no interpreted call to that root, with no intervening guest call.
This does not claim compilation of the dynamically allocated BCO or all helper
roots. Handoff pools must be balanced and free of retained references.

Additional JVM cases cover malformed streams, odd-sized/truncated encodings,
stack order, signed extremes, logical shifts, raw NaN bits, malformed case/tuple
headers, exact tuple descriptors, external ABI rejection, lazy/shared thunk
updates, released update targets, and foreign/closed-context use.

The genuine corpus runs with synchronous and capturing callers. Separate JVM
continuation checks use real MVar interruptions through both Core backends:
repeated cuts, overapplication with an outer pending Apply frame, initial and
returned thunk forcing, tail transfers, strict direct/megamorphic arguments,
constructor operands, nested P/T cases and instruction-loop interruption followed
by completion. A packed-loop control interrupts while a live byte keeps the
countdown stack unaligned, then widens that retained byte after resumption.
A deep BCO chain checks bounded stack spilling and result completion. Foreign
resumption rejects before claiming the continuation, leaving the owner able to
resume it. These focused controls establish continuation behavior; they do not
claim a compiled BCO interpreter or complete GHCi/Template Haskell execution.
