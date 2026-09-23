# Float and machine-Word unboxed arrays

This slice uses the installed, unmodified `array-0.5.8.0` public API with
GHC 9.14.1. `Float` storage is four bytes and has exact `FloatRep` payloads;
machine `Word` storage is eight bytes on the required 64-bit host and has
`WordRep`, not `Word64Rep`. Both use element indices and native byte order.

The selected primitive families are `readFloatArray#`, `writeFloatArray#`,
`indexFloatArray#`, `readWordArray#`, `writeWordArray#`, and `indexWordArray#`.
Reads return genuine `(# State# s, Float# #)` or `(# State# s, Word# #)`
tuples. Writes return `State# s`; immutable indexing returns the scalar.
Their value arities are respectively three, four, and two.

## Runtime storage and validation

Both AST and bytecode use the existing shared `byte[]` allocation and unsafe
freeze identity. Float accesses use a native-order four-byte `VarHandle` view
and primitive Float expressions, frame slots and bytecode locals. Word accesses
reuse the eight-byte raw-Long storage operations, preserving every bit, while
the loader separately requires the exact `WordRep` contract. This reuse does
not reinterpret machine Word as `Word64Rep` or permit `IntRep` payloads.

Each full-width element index is checked against the complete-element count
before narrowing or multiplication; partial trailing bytes are inaccessible.
Reads and writes evaluate and validate their State token before memory access,
and a failed read cannot publish a result. Unit tests cover independent
allocations, native byte order, cross-view aliases, partial tails, extreme
indices, raw quiet-NaN Float movement and failed State effects.

The native suite mutates each of the six actual primitive applications to
exercise saturation, representation flags, signedness/width, scalar-versus-tuple
State and unknown payload kinds on both backends. Contradictory supported
contracts fail at load in both policies. Unknown tuple leaves are the existing
aggregate frontier: strict loading rejects them; diagnostic loading records
the unsupported shape and must trap when demanded, with no handoff loans or
retained references. No diagnostic policy was changed for these operations.

## Real public workloads and independent models

The public modules use checked constant bounds `(-3,4)` and indices `-3`,
`0`, and `4`. `accumArray` exercises repeated accumulation at `-3`;
`runSTUArray`, `newArray`, `readArray`, `writeArray`, and `(!)` exercise
read-after-write and immutable observation. No public workload has an optimizer
fence. The retained `OPAQUE` helpers below are separate ABI movement controls.

Let `x = (raw & 65535) - 32768`, and let `S64` reinterpret a result modulo
`2^64` as signed. All public Float intermediates are exactly representable
dyadics, and the final `float2Int#` conversion is finite and in range.

| Entry | Independent result | Inputs | Actual guest calls |
| --- | --- | ---: | ---: |
| `unboxedFloatAccum` | `150*x + 277` | 397 | 2 |
| `unboxedFloatST` | `176*x + 76` | 397 | 2 |
| `unboxedWordAccum` | `S64(44*raw + 62)` | 397 | 2 |
| `unboxedWordST` | `S64(44*raw + 350)` | 397 | 2 |
| `moveFloatBits` | `raw & 0xffffffff` | 590 | 3 |
| `indexFloatBits` | `raw & 0xffffffff` | 590 | 3 |
| `aliasWordBytes` | Ordered byte model below | 397 | 2 |

The 397 machine-input patterns include each of 64 individual bits and its
neighbors, both signs, signed endpoints, alternating patterns, and differing
upper/lower halves. The Float movement domain adds both signs of zero,
infinities, subnormal endpoints, normal boundaries, finite neighbors, and quiet
NaNs with individually varied payload bits. Different upper halves exercise
the initial Word32 narrowing. Signaling NaNs are excluded **only** from the
movement entries, not from integer/Word inputs.

The movement roots write the low 32 input bits through `Word32Array#`, read
them through an opaque `Float#` tuple helper or opaque immutable scalar helper,
write that Float into a second array, and return `indexWord32Array#` widened to
machine Int. Pre/post-Tidy structural gates require the actual Float memory
operations and helper, so an optimized-away cross-view roundtrip cannot count
as evidence. This checks movement, not arithmetic NaN payload preservation.

For `aliasWordBytes`, encode the two unsigned words `raw` and
`raw xor 0x55aa55aa55aa55aa` into 16 native-order bytes. Retain the original
first word as `before`, then write byte 7 as `(raw+101)&255` and byte 8 as
`(raw+37)&255`. Decode `first` and `second` after those writes. The result is

```
S64(3*before + 16*first + 20*second
    + 17*byte[0] + 19*byte[7] + 23*byte[8] + 29*byte[15])
```

The Haskell checksum separately weights post-write reads and immutable
indices (5+11 and 7+13). Its primitive-count gate requires all those operations.
Python uses explicit integer/byte storage, with a separate shift/mask test for
both endiannesses; it performs no host floating-point expected-value arithmetic.

## Preparation and proof boundary

Run `python3 scripts/test-float-word-array-model.py`, then
`python3 scripts/prepare-float-word-arrays.py` with the pinned toolchain and
the shared resource gate. Preparation regenerates real optimized pre/post-Tidy
Core, runs all-branch strict audits for all seven entries at both stages,
compiles `NativeFloatWordArray.hs`, and compares 3,165 native rows against
independent models. It writes `build/float-word-arrays/manifest.json`,
`oracle.tsv`, and `expected.tsv`, with source/artifact hashes, exact primitive
counts, per-entry input domains, and recorded native byte order.

All fourteen strict root audits accept after the six selected capabilities are
present, with zero missing globals. Each public/alias root has one reachable
global; movement roots have two. Actual guest-call counts additionally include
the immediate, unconditional, zero-slot `State# RealWorld` lambda. A complete
proven local join prefix is not a guest function; its body remains traversed.
The preparation rejects extra lambdas, conditional/malformed State calls,
changed helper results, or a changed global closure. Native/static preparation
does not itself establish interpreted or compiled JVM execution; those are
separate runtime test gates, including the exact per-call counts above.

The JVM native tests execute every row interpreted and then compiled, at both
Core stages, on both backends, with inlining enabled and disabled. They discover
the active split targets through AST children and bytecode instruction caches,
compile callees before callers, and require exact compiled-entry increments,
unchanged target identities and last-tier validity after every measured call.
There are no settling calls or retries. The complete matrix contains 25,320
compiled invocations and 60,080 guest-root entries per handoff configuration.
Result and argument loans must be released, warmed result pools reused, and
supported workloads must report zero unsupported traps and blackholes.

General dynamic checked bounds, library error paths, copying/freezing via FFI,
floating host arguments, arbitrary floating arithmetic, signaling-NaN identity,
and non-native byte order on the current native host are not claimed. Existing
`unsafeFreezeByteArray#` alias semantics are used, not broadened by this slice.

## Initial verified checkpoint — 2026-09-23

On eak-quartus, Linux x86_64, using GHC 9.14.1 and GraalVM 25.3.4.1/JDK 25:

- Fresh complete fixture preparation passed; all 3,165 native/model rows and
  fourteen strict pre/post audits passed. All eighteen source and thirty-four
  artifact hashes were rechecked after both full suites.
- All ten focused JVM tests passed. Full default and dense-handoff suites each
  passed 388 tests in 82 suites with zero failures, errors or skips; `installDist`
  passed in both modes. Dense handoff used a forced rebuild/rerun.
- Each handoff configuration passed all 25,320 measured compiled invocations and
  exact 60,080 guest-root entries described above, with no weakened entry gates.
- Python tests passed without skips: 13 Float/Word model, 5 Int model, 9 Double
  model, 7 Int32 model, 9 ByteArray contracts, 43 auditor and 4 primop inventory.
- Independent xhigh review covered runtime, JVM tests, build inputs and CI.
  Ultra review independently reconstructed every native result, checked actual
  Core call counts, all 52 hashes and the diagnostic-trap controls. Both were
  clean after the documented test-policy and wording corrections.

Runtime/test revision: `95f056f60041e6b1abf8beab51e597cdd001b3f3`.
That initial checkpoint is stacked on `70726466ad1f82f433e8aa7207094be3f95d0cd7`;
the integration owner separately supplies the narrow-literal unknown-metadata
correction `8536441f913225412b2c699255c9fd500effc8c0`. The initial runs did
not include it; the combined validation below supersedes that limitation.

Retained local evidence is under `build/float-word-arrays/`,
`build/prepare-all-float-word.log`, `build/test-default-float-word.log`,
`build/test-handoff-float-word.log`, and `build/test-results/float-word-default/`
and `float-word-handoff/`. The initial focused failure was an overbroad
load-time expectation for diagnostic unknown tuple leaves; the corrected test
requires their actual on-demand trap. No runtime workaround was introduced.

## Combined literal-correction checkpoint — 2026-09-23

The reviewed parent correction `8536441f913225412b2c699255c9fd500effc8c0`
merged without conflicts at `9c9943c167c8f5e41d7d029388d33b170b7cb6e0`.
No Float/Word implementation changes were needed. A second fresh complete
preparation passed, including fourteen genuine `noinline` narrow-literal native
rows and their exact unknown-metadata assertions before and after Tidy.

The combined tree passed **390 tests in 82 suites in each mode**, with zero
failures, errors or skips; both `installDist` builds passed and dense handoff
again used a forced rebuild/rerun. This includes the unchanged Float/Word
per-row compiled-entry gates and the new literal native/compiled regression.
All eighteen Float/Word source plus thirty-four artifact hashes and sixteen
Int32 source plus twenty-nine artifact hashes were rechecked afterward.
All earlier Python suites passed again, now with 44 auditor tests.

Combined logs use the `-corrected.log` suffix; preserved XML results are in
`build/test-results/float-word-corrected-default/` and
`build/test-results/float-word-corrected-handoff/`. Current generated fixtures
and manifests describe this combined checkpoint; initial logs/XML remain as
historical evidence. The feature does not reproduce or reimplement the parent
correction, and PR/integration handling remains with the integration owner.
