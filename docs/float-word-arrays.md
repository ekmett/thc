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

General dynamic checked bounds, library error paths, copying/freezing via FFI,
floating host arguments, arbitrary floating arithmetic, signaling-NaN identity,
and non-native byte order on the current native host are not claimed. Existing
`unsafeFreezeByteArray#` alias semantics are used, not broadened by this slice.
