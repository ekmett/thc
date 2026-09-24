# Managed Int arrays

`readIntArray#`, `writeIntArray#`, and `indexIntArray#` extend the existing
[managed ByteArray operations](bytearrays.md) on both execution backends.
They are the missing primitive family for `UArray Int Int` and
`STUArray s Int Int` in the pinned `array-0.5.8.0` library.

The payload is still one primitive JVM `byte[]`: a native-endian `long` view
accesses each eight-byte machine Int on THC's 64-bit targets. Offsets count
**elements**, not bytes. Full-width bounds checks precede narrowing and byte
offset multiplication; incomplete trailing words are inaccessible. Writes
preserve every bit, and byte operations see the same backing storage. There is
no separate `Long[]` or `long[]`, wrapper, copy, or per-element box.

The contracts are exact, not inferred from a shared Java carrier:

| Operation | Arguments | Result |
| --- | --- | --- |
| `readIntArray#` | mutable array, `Int#` index, `State# s` | `(# State# s, Int# #)` |
| `writeIntArray#` | mutable array, `Int#` index, `Int#` value, `State# s` | `State# s` |
| `indexIntArray#` | immutable array, `Int#` index | `Int#` |

`Int64Rep` and `WordRep` cannot substitute for `IntRep`. The read tuple retains
two logical components but has one primitive Long destination; the State token
has no payload slot. State expressions are evaluated before memory access, and
failed reads do not publish a result. Plain accesses make no atomic or
concurrent-use guarantee.

The public examples use ordinary `accumArray` and `runSTUArray` with fixed
bounds and indices but changing full-width values. GHC itself discharges those
bounds checks; the exported pre/post-Tidy Core is unmodified. The general
dynamic-index probe still reaches unresolved cold error-formatting definitions
including `GHC.Internal.Ix.$w$sindexError`. This slice does not claim arbitrary
array programs, nor discard cold branches to accept a program.

[The public examples](../examples/THC/UnboxedArrays.hs) cover duplicate
accumulations, ST read-after-write feedback, and an empty array. Two primitive
fixtures additionally test independent allocations, repeated writes, and byte
overwrites straddling adjacent Int elements. Their 393 inputs include both
signed endpoints, every bit position and neighboring values, and asymmetric
byte patterns: 1,965 native/model rows in total. The empty-array example makes
no claim that an allocation must survive optimization.

Public copying `freeze`/`thaw`, `(//)`, and `accum` on an existing UArray reach
native `memcpy` in this library version and are not covered here. Neither are
raw addresses, pinned storage, FFI, unsafe freeze-alias mutation or native
uninitialized-memory reads. The byte allocation size remains JVM-limited.
Native/Core artifacts must use the runtime host's byte order; this is not a
cross-endian export contract.

Preparation and checks:

```sh
python3 scripts/prepare-int-arrays.py
python3 scripts/test-core-bytearrays.py
python3 scripts/test-int-array-model.py
./gradlew test --tests 'thc.runtime.IntArray*'
```

The native oracle, independent mathematical models, exact dependency audits,
and source/artifact hashes are retained under `build/int-arrays`. Runtime tests
exercise both Core stages and both backends before and after compilation;
every checked compiled invocation must enter installed guest code and retain
valid host/active entry targets. Bounds, byte aliasing, state ordering, exact
metadata and malformed applications have separate controls. CI also executes
the dense-handoff configuration. These are correctness checks, not throughput
measurements or a claim that all allocation disappears after compilation.

[Production graph evidence](../bench/experiments/int-array-access/README.md)
checks both primitive fixtures on AST and bytecode with normal inlining. It
shows primitive Long loads and stores into the shared byte arrays, whose
allocations remain, plus one scalar host-result box. The graph captures use
default handoff and do not claim residual-call or throughput coverage.
