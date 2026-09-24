# Scalar bit primitives

THC implements these 21 unary GHC 9.14.1 primitives in both the AST and bytecode
backends. Machine `popCnt#`, `clz#`, and `ctz#` were already supported.

| Operations | Input | Result |
| --- | --- | --- |
| `popCnt8/16/32#`, `clz8/16/32#`, `ctz8/16/32#` | `Word#`, lower N bits | `Word#` count |
| `popCnt64#`, `clz64#`, `ctz64#` | `Word64#` | `Word#` count |
| `byteSwap16/32#`, `bitReverse8/16/32#` | `Word#`, lower N bits | `Word#`, N defined bits |
| `byteSwap64#`, `bitReverse64#` | `Word64#` | `Word64#` |
| `byteSwap#`, `bitReverse#` | machine `Word#` | machine `Word#` |

The pinned `compiler/GHC/Builtin/primops.txt.pp` defines the narrow count
operations over the lower input bits. Higher input bits are ignored, including
negative signed host carriers. Both zero counts return N for an all-zero N-bit
input; population count returns zero. THC uses 64-bit machine words.

GHC marks only N output bits defined for narrow byte swaps and bit reversal.
THC chooses canonical zero upper bits. The native driver masks the undefined
upper bits before comparison; this makes no claim about their native contents.
The exported wrappers contain no output mask, so guest comparisons also check
THC's canonical result directly. Full-width results preserve all 64 bits in a
signed `Long` carrier. The explicit `Word64#` wrappers use the real
`wordToWord64#` and `word64ToWord#` conversion primitives.

Widths become immutable AST fields or bytecode constant operands during
lowering. Guest execution uses primitive `long` operands and results, with
fixed masks/shifts and Java's population-count, zero-count, byte-reversal, and
bit-reversal operations. No generic boxed arithmetic is added.

`compiler/test-fixtures/BitPrimopsAudit.hs` uses opaque wrappers with dynamic
inputs. The Haskell fixture producer exports both pre-Tidy and post-Tidy Core
and requires strict dependency audit acceptance. JVM tests check retained
primitive names and the pinned input/result representations. The manifest hashes source inputs,
exported Core, audit reports, the native driver, and its 11,923 result rows.
Samples include every bit position and transition, zero, all ones, alternating
bits, signed-carrier extremes, and exhaustive byte inputs with several upper
bit patterns. Native GHC results are independently checked with an unbounded
integer bit-position model.

The JVM test runs every row before and after explicit compilation on both
backends and both export stages. Each post-compilation row must increment the
compiled guest-entry counter exactly once; the active guest targets and host
root must remain installed. Unsupported traps, blackholes, thunk evaluation,
and partial-application allocation remain zero. Separate controls reject zero
and two arguments at load time for every new unary primitive, including in
diagnostic mode.

From a clean checkout with the pinned GHC and Graal toolchains:

```sh
compiler/build.sh
cabal run exe:thc-fixtures --offline -- bit
./gradlew test --tests thc.runtime.BitPrimopsTest
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew test --tests thc.runtime.BitPrimopsTest --rerun
```

`scripts/prepare-tests.sh` includes preparation, so the ordinary clean-build CI
and opt-in handoff test runs exercise this suite. This slice does not add
deposit/extract operations, arithmetic, shifts, or SIMD.
