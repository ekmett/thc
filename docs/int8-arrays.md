# Signed and unsigned byte arrays

The four added GHC 9.14.1 primitives are:

| Primitive | Value arguments | Result |
| --- | --- | --- |
| `readInt8Array#` | `MutableByteArray# s`, `Int#`, `State# s` | `(# State# s, Int8# #)` |
| `writeInt8Array#` | `MutableByteArray# s`, `Int#`, `Int8#`, `State# s` | `State# s` |
| `indexInt8Array#` | `ByteArray#`, `Int#` | `Int8#` |
| `readWord8Array#` | `MutableByteArray# s`, `Int#`, `State# s` | `(# State# s, Word8# #)` |

Existing `writeWord8Array#` and `indexWord8Array#` complete the unsigned family.
Both array references have exact `BoxedRep (Just Unlifted)` proofs. Signed and
unsigned payloads retain `Int8Rep` and `Word8Rep`; neither is interchangeable
with machine Int/Word or another narrow width. State has no physical result
slot. Arity, argument flags and recursive result proofs are checked by both
loaders and the strict auditor.

The carrier remains the primitive JVM `byte[]`, with no wrapper or boxed
individual bytes. The AST uses fixed-child nodes; bytecode uses typed Long
operands and destinations. Signed reads widen the byte to `[-128,127]`, unsigned
reads to `[0,255]`; writes keep the low eight bits. Indices remain full-width
Long values until checked against the byte length. One-byte elements require
no endian conversion or alignment scaling. State is evaluated and validated
before access or result publication. Unsafe freeze preserves the array's
identity; tests do not mutate after freezing.

## Public source and independent evidence

`examples/THC/Unboxed8Arrays.hs` uses the actual installed `array-0.5.8.0`
implementations of `accumArray`, `runSTUArray`, `newArray`, `readArray`,
`writeArray` and `(!)` for public Int8/Word8 `UArray`/`STUArray` programs.
Bounds `(-3,4)` and observed indices are constant; values and updates depend on
the argument. GHC discharges the constant checked-index branches naturally.
No optimizer fences or replacement library bodies are used.

For `D(x)` reducing modulo 256 and interpreting it with the selected signedness,
the public models are `7*D(x+1)+11*D(x+5)+13*D(2*x)` for accumulation and
`7*D(x)+11*D(x+7)+13*D(2*x+21)` for ordered ST updates. Each cell widens before
its weighted checksum. The expected Word8 `-2` constant wraps to 254; GHC emits
its ordinary overflow warning.

`Int8ArrayAudit.hs` adds an ordered signed/unsigned alias control, empty storage,
and three raw narrow-result entries. The alias control reads before mutation,
then writes and reads the same two bytes through both signed and unsigned views.
The raw entries return Int8#/Word8# directly to the THC host, so a widening
conversion cannot hide a noncanonical Long result. Native GHC widens those
results only in its driver.

Fresh preparation covers nine entries and 846 inputs each: **7,614 native/model
rows**. Inputs include every value from -256 through 255, all 64 machine bit
positions and their neighbors with both signs, and alternating byte patterns.
An unbounded Python model and separate sequential-cell/bytearray checks verify
the results. Pre/post-Tidy exports require all expected primitive occurrences,
strict whole-closure acceptance and exactly two guest functions per entry: the
entry and its immediately applied State lambda.

The JVM matrix executes both Core stages on AST and bytecode with inlining
both enabled and disabled. It compiles the observed active targets and requires
exactly two compiled guest entries, unchanged target identities and valid
last-tier code after every measured call. That is **60,912 compiled invocations
and 121,824 guest entries per handoff configuration**, in addition to the
interpreted checks. The count excludes the host bridge. Storage and malformed
Core controls check all 256 byte patterns, full-width invalid indices, exact
signedness/width/State/levity, failed-effect publication and released storage.

## Reproduction and limits

Run with pinned GHC 9.14.1 and GraalVM 25.3.4.1/JDK 25:

```sh
compiler/build.sh
python3 scripts/prepare-int8-arrays.py
python3 scripts/test-int8-array-model.py
python3 scripts/test-core-bytearrays.py
./gradlew --no-daemon test --tests 'thc.runtime.Int8Array*'
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew --no-daemon test --rerun --tests 'thc.runtime.Int8Array*'
```

`build/int8-arrays/manifest.json` records original source, preparer, auditor and
artifact hashes, toolchain/package information, primitive counts and the native
executable. Standard fresh test preparation and CI run the fixture/model and
retain these artifacts. Native accesses are initialized and in bounds; invalid
indices are tested only against THC's explicit managed-domain errors. General
dynamic checked bounds, array error closures, concurrency, atomics, pinned
storage, FFI and allocation failure are outside this slice. No throughput,
allocation-elimination, or machine-register claim is made.

The focused local validation passed all seven new JVM tests in both normal and
forced dense-handoff modes, with identical test sets and no failures or skips.
The four model tests, ten managed ByteArray contract tests and pinned primitive
name/arity and scalar-signature checks also passed. This focused checkpoint does
not claim a full regression-suite run.
