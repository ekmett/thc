# Native-endian Int16 and Word16 arrays

This slice exercises `readInt16Array#`, `writeInt16Array#`,
`indexInt16Array#`, and their three `Word16Array#` counterparts. Typed indices
select two-byte elements; `Word8Array#` indices select individual bytes. The
exact payloads are `Int16Rep` and `Word16Rep`, not machine Int/Word or another
narrow width. Reads return genuine `(# State#, Int16# #)` or
`(# State#, Word16# #)` tuples, with no physical slot for the state token.
Writes return State; immutable indexing returns the narrow scalar. Values widen
with sign extension for Int16 and zero extension for Word16.

## Genuine public examples

`examples/THC/Unboxed16Arrays.hs` uses installed, unmodified `array-0.5.8.0`
with GHC 9.14.1: public `accumArray`, `runSTUArray`, `newArray`, `readArray`,
`writeArray`, and checked `(!)`. Bounds `(-3,4)` and observed indices `-3,0,4`
are constants, so GHC discharges their bounds checks naturally. Duplicate
accumulation and state-ordered read-after-write remain in optimized Core. There
are no optimizer fences or hand-edited Core in these public examples.

Initial pre/post-Tidy feasibility found exactly the six array primitives plus
ordinary signed `int16` literals as unsupported, and zero missing globals,
aggregate frontiers, cold error closures, or FFI calls. Constants `3`, `5`,
`-2`, and `7` are genuine signed narrow literals. They are not replaced with
machine-width literals to avoid the loader requirement. The ordinary Word16
`-2` constant wraps modulo 65536; GHC reports its expected overflow warning.

Let `u = raw & 65535`, and let `D(v)` normalize modulo 65536 then interpret
the bits as signed Int16 or unsigned Word16. The independent public models are:

- Accumulation: `7*D(u+1) + 11*D(u+5) + 13*D(2*u)`.
- ST updates: `7*D(u) + 11*D(u+7) + 13*D(2*u+21)`.

Each observed cell widens before the weighted machine-Int checksum. Consequently
signed and unsigned observations differ, and the checksum does not wrap at
16 bits. It is safely within signed 64-bit range.

## Ordered alias and erased-literal controls

`compiler/test-fixtures/Int16ArrayAudit.hs` includes two ordered alias roots.
Each allocates four bytes, writes narrow elements `u` and `u xor 0x55aa`, and
reads the first element before mutation. It then overwrites byte 1 with
`(raw+101)&255` and byte 2 with `(raw+37)&255`, crossing the element boundary.
Decode both final elements in native order as `first` and `second`; the result
is

```
3*D(before) + 16*D(first) + 20*D(second)
  + 17*byte[0] + 19*byte[1] + 23*byte[2] + 29*byte[3]
```

The Haskell source separately weights typed reads and immutable indices as
5+11 and 7+13. Exact primitive-count checks require all those operations to
remain. Both signed and unsigned paths share the raw low-16-bit writes; only
their typed interpretation differs. Python uses explicit integer/byte storage
and checks it against an independent mask/shift model for both byte orders.

Separate `noinlineInt16Literal` and `noinlineWord16Literal` controls retain
opaque workers receiving `-32768` and `65535`. Genuine `noinline` erases the
literal operand metadata to unknown/no register constraints in both stages.
Literal syntax must still establish the intrinsic signed/unsigned identity.
The preparation requires exactly one direct saturated worker call, preserved
dynamic input, the actual erased literal, and no extra guest functions. These
controls are separate from the unfenced public examples. Their native result is
the signed64 wrap of `raw-32768` or `raw+65535`.

## Preparation and evidence limits

With the pinned environment and shared build resource gate, run:

```sh
python3 scripts/test-int16-array-model.py
python3 scripts/prepare-int16-arrays.py
```

Preparation regenerates original pre/post-Tidy Core, requires strict all-branch
acceptance, and compiles `NativeInt16Array.hs`. Under `build/int16-arrays`, it
records Core, per-entry audits, the native executable, `oracle.tsv`,
`expected.tsv`, `literal-oracle.tsv`, and `manifest.json`. The manifest includes
the source/artifact hashes, exact primitive counts, native byte order, installed
array package and toolchain information, and actual-root proofs.

The six array entries use 403 full-width input seeds each: 2,418 native/model
rows. Inputs cover all 64 machine bit positions and neighbors, signed16/wrap
boundaries, alternating bytes, and distinct high halves with equal low payloads.
The two noinline controls each use seven seeds, including signed64 endpoints:
14 additional native rows. Native execution proves only the recorded host byte
order; both-endian model tests do not claim native big-endian execution.

Every array root has one reachable global plus exactly one unconditional,
immediately applied zero-slot `State# RealWorld` lambda: two guest entries per
call. The literal controls have the entry and opaque worker, also two guest
entries. Structural checks traverse dictionary-held expressions and join bodies;
only complete, proven local join prefixes are exempt from function counting.
These counts exclude the host bridge. Separate JVM tests must compile the actual
selected targets and require exact per-call counters; native/static preparation
alone is not interpreted or compiled JVM execution evidence.

General dynamic checked indices, error closures, FFI copying APIs, arbitrary
bounds, atomic/concurrent accesses, and array allocation failure are outside this
fixture claim. No throughput or allocation-elimination claim is made.
