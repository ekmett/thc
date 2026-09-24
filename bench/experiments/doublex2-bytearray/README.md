# DoubleX2 managed ByteArray evidence harness

This is a fail-closed, native-backed check for the six local DoubleX2 memory
intrinsics. It is not evidence of successful compilation until actual captures
have passed. Synthetic reader tests and retained rejection controls are not new
guest execution.

The portable semantic corpus contains exactly 4,384 rows across twelve scalar
entries. Raw movement uses signed Int64 encodings and scalar Int/Double array
aliases; it observes signed zeros, subnormal/normal boundaries, infinities and
selected signed quiet-NaN payloads without scalar bitcast primitives. The raw
write witness is the signed encoding of the two input words XOR the selected
byte; graph stores use the separate bounded checksum witness below. The 576
selected signaling-NaN cases are separate native-only diagnostics, excluded from
the portable corpus, installed-entry test and graph inputs. No NaN arithmetic
payload guarantee is made.

## Selected graph contract

Four roots are captured once for each pre/post Core stage and AST/bytecode
backend: sixteen captures. Each uses sixteen finite patterns and two complete
installed-code passes, for 512 checked compiled invocations.

| Root | Caller arguments | Required live packed memory |
| --- | --- | --- |
| `vectorIndexGraph` | byte array, index | one 128-bit read; index unit 16 bytes |
| `scalarIndexGraph` | byte array, index | one 128-bit read; index unit 8 bytes |
| `vectorStoreGraph` | byte array, index, two Int seeds, State | one 128-bit write; index unit 16 bytes |
| `scalarStoreGraph` | byte array, index, two Int seeds, State | one 128-bit write; index unit 8 bytes |

Every call gets fresh caller-owned 64-byte storage. All bytes are checked; stores
must return that identical array after their final freeze, without later
mutation. The inputs rotate eight integer edges from -2^40 through +2^40 across
both lanes, with distinct sentinels and every safe offset. This is not an
exhaustive Cartesian bit-pattern/offset domain. Binary64 products with weights
3/5 and their sum are exact here: the absolute weighted sum is below 2^44.
The graph store's checksum-times-257 plus byte witness fits signed Int64.
Expected bytes and checksums are independently reconstructed and mapped to the
hashed native oracle.

The pre-lowering graph must show the original caller byte-array address, live
dynamic index and exactly one plain non-reference 128-bit access dominating the
return. A load requires one binary64x2 view, both f64 scalar cuts and two distinct
weighted products feeding one sum, then `D2L` and the public Long result.
A store requires two distinct host Long inputs converted by `L2D`, an exact
two-lane Double broadcast/insert, and the live write. Integer observations,
Float32 rounding, FMA and lane loss are not substitutes. Only exact 128-bit
byte16/int64x2/float64x2 bitwise reinterpret chains are permitted.

Allocated-register final LIR must have one matching `VECTORLOAD` or
`VECTORSTORE`, physical XMM register, managed heap address, and 128-bit
`VMOVDQU`, `VMOVDQU32` or `VMOVUPD`. Pinned Graal
`AMD64VectorMove.java:497-510` selects the latter for Double lanes and the
integer move for bitwise byte/long views. Scalar `VMOVSD`, YMM/ZMM operands,
virtual registers and mnemonic comments are insufficient.

Live private allocation, calls, fields, scalar payload traffic, carrier/Vector
API references and floating boxes remain forbidden. Exact eliminated
frame-tag/primitive-backing, interpreter bytecode/destination-index metadata and
bloom-header bookkeeping exceptions require ownership and non-escape proofs.
Double frame slots admit tag 3 with an omitted/default or f64 primitive and no
object value. They do not permit arbitrary long arrays or floating payloads.

That frame exception is sourced from the pinned JDK
`lib/src.zip` members
`jdk.graal.compiler/jdk/graal/compiler/truffle/nodes/frame/NewFrameNode.java:81-88`
and `VirtualFrameSetNode.java:84-101,123-132`: ordinary virtual frame writes keep
the actual primitive value; only the OSR/static path forces long bits.
Vector reinterpretation's little-endian convention is explicit in
`jdk.incubator.vector/jdk/incubator/vector/Vector.java:714-743,3034-3069`.
This harness checks only the native little-endian x86-64 path.

## Running

After normal fixture preparation and the installed distribution have succeeded,
with the pinned GHC 9.14.1/Graal JDK environment:

```sh
bash bench/experiments/doublex2-bytearray/run-runtime.sh build/doublex2-bytearray-runtime
```

The single-tier capture threshold and forty full warmup passes are inherited
unchanged. There are no retries, settling calls, graph-limit increases or new
compiler policies. The active selected target must be installed at last tier and
unchanged/valid before and after every measured input. Graph counters are off;
the separate instrumented semantic test must establish exact per-row compiled
entry deltas, active targets and empty handoff pools at normal automatic settings.

Runtime sources, probe, runner, JARs, JDK and native/Core/model artifacts are
hashed before and after capture. Raw BGV/CFG, selected graph, final LIR,
commands, logs and statuses are retained. Existing capture directories cannot
be reused. A justified reader-only offline correction requires
`--recheck-after-checker-fix`, unchanged non-reader inputs and retention of the
original failure/checker. It never replays guests.

Offline parser tests (no JVM):

```sh
PYTHONDONTWRITEBYTECODE=1 python3 bench/experiments/doublex2-bytearray/test-runtime-audit.py
PYTHONDONTWRITEBYTECODE=1 python3 -O bench/experiments/doublex2-bytearray/test-runtime-audit.py
```

Committed signed/unsigned/Float memory and integer arithmetic captures are
rejection controls only. Float loads share the two-argument host ABI but fail
the required binary64 observation; four-lane stores also differ in arity, so
their rejection is not proof of different machine bytes. There is no
big-endian, non-x86, throughput, alignment-performance or no-spill claim.
Interpreted fallbacks may allocate and the host Long result may box. Vector
function/formal/capture/join/heap and transported State/vector tuple ABIs remain
unsupported.
