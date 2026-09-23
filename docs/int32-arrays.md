# Native-endian Int32 and Word32 arrays

This bounded slice covers `readInt32Array#`, `writeInt32Array#`,
`indexInt32Array#`, and their three `Word32Array#` counterparts. Typed array
indices select four-byte elements; `Word8Array#` indices select individual bytes.
The exact payloads are `Int32Rep` and `Word32Rep`, not machine `IntRep`/`WordRep`
or same-width floating representations. Reads return a genuine unboxed
`(# State#, Int32# #)` or `(# State#, Word32# #)` tuple, with no physical slot for
the state token. Writes return the state token; indexing returns the narrow
scalar. Int32 reads/widening sign-extend, while Word32 reads/widening zero-extend.

`examples/THC/Unboxed32Arrays.hs` uses installed, unmodified `array-0.5.8.0`
public `accumArray`, `runSTUArray`, `newArray`, `readArray`, `writeArray`, and
checked `(!)`. Bounds `(-3,4)` and observed indices `-3,0,4` are constants; GHC
discharges those bounds checks. Duplicate accumulation and state-ordered
read-after-write remain in genuine optimized Core. No optimizer fences or
hand-edited Core are used. Dynamic checked-index error paths, FFI copying APIs,
arbitrary bounds, and array allocation failure are not claimed by these examples.
Ordinary Int32 arithmetic also retains actual `int32` literals with exact
`Int32Rep`, so strict preparation must accept those rather than substituting
machine-width literals.

Let `u` be the low 32 bits of the signed 64-bit input, and `D(x)` normalize modulo
2^32 then interpret the bits as signed Int32 or unsigned Word32. The independently
modeled public results are:

- Accumulation: `7*D(u+1) + 11*D(u+5) + 13*D(2*u)`.
- ST updates: `7*D(u) + 11*D(u+7) + 13*D(2*u+21)`.

Each cell widens *before* the weighted checksum, preserving the observable
signed/unsigned distinction instead of wrapping the final checksum at 32 bits.

`compiler/test-fixtures/Int32ArrayAudit.hs` supplies two ordered alias controls.
Both allocate eight bytes, write narrow elements `u` and `u xor 0x55aa55aa`,
and read the first element before mutation. They then overwrite bytes 3 and 4
with `(input+101)&255` and `(input+37)&255`, straddling the element boundary.
Subsequent typed reads, immutable typed indices and four byte indices must all
observe the same storage. Their checksums weight the original read, final reads,
indices and bytes separately. The Int32 and Word32 variants differ only in the
typed interpretation, so sign extension cannot silently become zero extension.

Run preparation with the pinned GHC 9.14.1 environment:

```sh
python3 scripts/test-int32-array-model.py
python3 scripts/prepare-int32-arrays.py
```

The preparer regenerates real pre/post-Tidy Core and the native
`NativeInt32Array.hs` oracle under `build/int32-arrays`. It requires strict
all-branch acceptance, all selected typed primitives, exact alias primitive use
counts, complete unique native rows, and equality with an explicit integer/byte
model. The corpus covers all 64 input-bit positions and neighboring values,
32-bit sign/wrap boundaries, all-ones and alternating patterns, and distinct high
halves with the same narrow payload. Python controls independently test both byte
orders with a mask/shift model; native execution claims only its recorded host
byte order. Source and artifact fingerprints include the exact capability and
exporter inputs, generated driver, oracle executable, rows, Core and audits.

The guest-root proof counts the entry and its unconditional immediate local
`State#` lambda. It does not count genuine complete local-join prefixes, and it
still inspects their bodies and dictionary-held expressions for extra functions.
Thus these frozen fixtures require two guest entries per uninlined public call,
excluding the host bridge. Separate JVM validation must compile the actual
selected/nested targets and enforce those per-call counters on both backends;
native and static checks alone are not guest execution evidence.
