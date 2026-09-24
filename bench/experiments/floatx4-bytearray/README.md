# FloatX4 managed ByteArray evidence harness

The [retained x86-64 checkpoint](evidence-x86_64/README.md) passes all sixteen
Float captures plus fresh signed/unsigned memory regressions. It preserves
the original AST dispatch failure and the separate exact-frame reader fix.

This is a fail-closed, native-backed check for the six local FloatX4 memory
intrinsics. It is not evidence of successful compilation until an actual capture
has passed. The reader's synthetic tests are parser tests, not guest execution.

The portable semantic corpus contains exactly 6,720 rows across twelve scalar
entries. Raw movement observes signed zeros, subnormal/normal boundaries,
infinities and two signed quiet-NaN payloads through scalar Word32/Float array
aliases. It requires no scalar bitcast primitive. Selected signaling NaNs belong
only to a separately labeled native diagnostic file, never the portable corpus,
the installed-entry test, or the graph inputs. No NaN arithmetic payload promise
is made.

## Selected graph contract

The four roots are captured once for each pre/post Core stage and AST/bytecode
backend: sixteen captures. Each uses 32 finite input patterns and two complete
installed-code passes: 1,024 checked compiled invocations in total.

| Root | Caller arguments | Required live packed memory |
| --- | --- | --- |
| `vectorIndexGraph` | byte array, index | one 128-bit read; index unit 16 bytes |
| `scalarIndexGraph` | byte array, index | one 128-bit read; index unit 4 bytes |
| `vectorStoreGraph` | byte array, index, four Int seeds, State | one 128-bit write; index unit 16 bytes |
| `scalarStoreGraph` | byte array, index, four Int seeds, State | one 128-bit write; index unit 4 bytes |

Every call receives fresh caller-owned 64-byte storage. Every byte is checked;
stores must return the identical array after their final freeze, with no later
mutation. Inputs rotate each of eight finite integer edges through each lane,
covering every safe offset. The Float32 products with weights 3/5/7/11 and all
partial sums are exact in this bounded domain. Expected bytes are independently
reconstructed; all graph rows are mapped back to the hashed native oracle.

The pre-lowering graph must show the original caller-array address, a live dynamic
index and exactly one plain, non-reference, fixed 128-bit access dominating the
return. A load must have one Float32x4 view, all four scalar Float32 cuts and the
exact four weighted products/left-associated sums feeding `F2L` and the public
Long result. A store must retain four distinct host Long inputs, each converted
by `L2F`, and the exact Float32 broadcast/insert construction feeding the write.
Integer lane extension, double intermediates and numeric reinterpretation are
not accepted substitutes. Exact 128-bit bitwise reinterprets are allowed.

Allocated-register final LIR must contain exactly one matching `VECTORLOAD` or
`VECTORSTORE`, an actual XMM register and managed heap address, and a 128-bit
`VMOVDQU`, `VMOVDQU32` or `VMOVUPS` operation. A mnemonic in a comment, scalar
access, YMM/ZMM operand or virtual register is insufficient.

Whole-graph live private allocation, calls, fields, scalar array payload traffic,
carrier/Vector API references and Float/Double boxes are rejected. The inherited
frame-tag, primitive-frame backing, constant bytecode/destination-index metadata
and bloom bookkeeping exceptions require exact ownership, slot and non-escape
proofs. They do not permit a vector payload to survive. Unknown metadata fails
closed; a new shape requires source-backed review, not a broad exception.

## Running

After the normal fixture preparation and installed distribution have succeeded,
with the pinned GHC 9.14.1/Graal JDK environment:

```sh
bash bench/experiments/floatx4-bytearray/run-runtime.sh build/floatx4-bytearray-runtime
```

Use the project's shared JVM/build gate and coordinate the execution window.
This script is not permission to overlap another worker's JVM job. It keeps the
existing single-tier capture threshold and forty complete warmup passes; there
are no retries, settling calls, graph-limit increases or policy changes. The
active selected target must be installed at last tier and unchanged/valid before
and after every measured input. Graph captures disable counters; the separate
`SimdFloatByteArrayTest` checks exact per-row compiled-entry deltas and active
target identities with inlining enabled and disabled, at normal automatic
compilation settings, plus empty handoff pools.

Source/probe/runner, installed JARs, JDK files and native/Core/model inputs are
hashed before and after capture. Raw BGV/CFG, selected parsed graph, final LIR,
commands, logs and statuses are retained. Existing output directories cannot be
reused. A failure is retained; there is no implicit guest replay. A justified
offline reader-only correction uses `--recheck-after-checker-fix`, verifies every
non-reader source/binary/input unchanged, and retains the original checker,
failure and correction hashes.

Offline tests (no JVM):

```sh
PYTHONDONTWRITEBYTECODE=1 python3 bench/experiments/floatx4-bytearray/test-runtime-audit.py
PYTHONDONTWRITEBYTECODE=1 python3 -O bench/experiments/floatx4-bytearray/test-runtime-audit.py
```

Historical integer arithmetic/memory graphs are rejection controls only, not
Float memory execution evidence. The claim is restricted to the captured
little-endian x86-64 paths. There is no big-endian, non-x86, alignment-performance,
throughput or no-spill claim. Interpreted Vector API fallbacks may allocate, and
the Object host ABI may box the Long result. Vector function/formal/capture/join,
heap and transported State/vector tuple ABIs remain unsupported.
