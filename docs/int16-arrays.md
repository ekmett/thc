# Native-endian Int16 and Word16 arrays

This slice exercises `readInt16Array#`, `writeInt16Array#`,
`indexInt16Array#`, and their three `Word16Array#` counterparts. Typed indices
select two-byte elements; `Word8Array#` indices select individual bytes. The
exact payloads are `Int16Rep` and `Word16Rep`, not machine Int/Word or another
narrow width. Reads return genuine `(# State#, Int16# #)` or
`(# State#, Word16# #)` tuples, with no physical slot for the state token.
Writes return State; immutable indexing returns the narrow scalar. Values widen
with sign extension for Int16 and zero extension for Word16.

## Runtime and exact literal boundary

Both backends use a native-order primitive `short` VarHandle view of the same
managed `byte[]`, with no boxed or copied element array. Signed reads widen the
short to Long; unsigned reads zero-extend it; writes keep the low sixteen bits.
Full-width indices are checked against `byteLength/2` before narrowing/scaling,
so an odd trailing byte cannot be accessed as an element. State validation
precedes memory effects and result publication; unsafe freeze preserves identity.
Storage tests include every one of the 65,536 bit patterns, byte1/2 aliases,
wide views, odd tails, enormous indices and failed State effects.

Canonical signed `int16` syntax is accepted only in `[-32768,32767]`, including
literal alternatives. Both `int16` and the already parsed `word16` forms now
provide intrinsic exact representation proofs. The shared 16/32-bit proof
helper preserves the reviewed rule: absent or genuinely unconstrained
unknown/null metadata can refine from literal syntax; contradictory known
representations and malformed records fail. No int8 literal support is added.

The native JVM matrix runs interpreted and compiled before/after Tidy, on AST
and bytecode, with inlining enabled and disabled. It discovers active split
targets (including bytecode instruction caches), compiles callees first and
requires exactly two guest entries, unchanged target identities and valid
last-tier code after every measured invocation. There are no settling calls,
retries or raised runtime limits. This covers 19,344 array invocations and 112
literal invocations per handoff configuration: 19,456 calls / 38,912 guest
entries. Result/argument loans and references must be released, warmed result
pools reused, and supported execution must have no traps or blackholes.

Malformed width, signedness, arity, State and representation-flag controls run
on both backends and load policies. Unknown tuple leaves retain the existing
diagnostic frontier: strict mode rejects them at load; diagnostic mode records
the reason and must trap exactly once on demand without handoff leaks. Other
contradictory positive contracts still fail during loading.

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

## Verified checkpoint — 2026-09-23

On eak-quartus, Linux x86_64, GHC 9.14.1 and GraalVM 25.3.4.1/JDK 25:

- Fresh complete fixture preparation passed: 2,418 array native/model rows,
  14 genuine erased-literal rows and all sixteen strict pre/post audits.
- All sixteen focused JVM tests passed: five storage tests, five native/gate
  tests, three new literal tests and three retained Int32 literal regressions.
- Full default and dense-handoff suites each passed **403 tests in 85 suites**,
  with zero failures, errors or skips. `installDist` passed in both modes;
  dense handoff used a forced rebuild/rerun.
- Each mode passed all 19,456 measured compiled invocations / 38,912 exact guest
  entries described above, preserving per-call identity and last-tier checks.
- Python passed without skips: 9 new model, 9 ByteArray contract, 47 auditor,
  4 primop inventory, 5 Int model, 9 Double model, 7 Int32 model and 13 Float/Word
  model tests. All sixteen source and twenty-nine artifact hashes were rechecked
  after both full suites.
- Independent xhigh review checked native/storage tests and build/CI wiring.
  Ultra review checked pinned contracts, literal/runtime behavior, actual Core
  counts and all hashes; it separately reconstructed every array/literal native
  result. Both reviews were clean without running JVM guest code themselves.

Tested runtime revision: `8fa2a9982d4fcc19397e678ff7a752f6ccbec6f6`, based on
`37ed64fa5bc27173d5143885e796dd2eb319954b`. No main/current-batch merge was added.
Evidence remains under `build/int16-arrays/`, `build/prepare-all-int16.log`,
`build/test-focused-int16.log`, `build/test-default-int16.log`,
`build/test-handoff-int16.log`, and `build/test-results/int16-default/` /
`int16-handoff/`. This checkpoint makes no performance or packed-code claim.
