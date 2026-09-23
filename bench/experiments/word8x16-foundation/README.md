# Word8X16 graph controls

This harness consumes actual exported `SimdWord8X16` Core and source-matched
native GHC rows from `build/simd-word8x16`. It checks `plusCase`, `minusCase`,
and `timesCase` on AST and bytecode at both retained Core stages: twelve captures.
Each root takes two scalar `Int#` inputs and returns a nonnegative weighted checksum
that observes all sixteen unsigned lanes. No vector argument ABI is added, and
GHC has no `negateWord8X16#` operation.

After preparing native inputs with `scripts/prepare-word8x16-audit.py` and building
`installDist`, invoke the harness with the pinned Graal JDK and a new directory:

```sh
bash bench/experiments/word8x16-foundation/run-runtime.sh build/word8x16-runtime-capture
```

Acquire the host's shared resource gate around the complete capture command,
reserving this worktree's actual `build` directory. The runner does not rebuild
or prepare fixtures. It rejects an existing output directory and stops on the
first failed command, retaining the command, output, and exit status. It uses the
same compilation settings and forty interpreted corpus passes as the existing
DoubleX2 control, followed by one explicit compile and two checked corpus passes.
Every compiled input must preserve the native answer, active target identity,
and exact installed entry validity. There are no retries, postcompile settling,
compiler limit changes, or altered inlining policies.

The gate requires source/artifact hashes, the original native compiler identity
and commands, agreement with the independent integer model, and accepted strict
audits. It snapshots runtime sources, installed JARs, the harness, graph reader,
and JDK binaries/modules before execution and checks them afterward. It retains
raw BGV/CFG files, parsed graphs, exact command logs, and compact final LIR.

Before lowering, addition and subtraction must each retain exactly one connected
arithmetic node with sixteen `i8` components. These are physical byte bits, not a
claim that `Word8Rep` is interchangeable with `Int8Rep`. Every output lane must
feed the public checksum through exactly one `ZeroExtendNode` from 8 to 64 bits.
All sixteen conversion identities are recorded as `unsignedLaneExtensions`;
signed widening, wrong-width widening, and bypassing the conversion are rejected.
This matches the fixture's `word2Int# (word8ToWord# lane)` observations of 0..255.
Surviving carrier/vector/array allocation, primitive array loads, field traffic,
calls, intermediate lane boxing, and additional Long boxes are rejected. The two
host Long input unboxes, host Object-array reads, and one public Long result box
are allowed explicitly. Final allocated-register LIR must contain the respective
`VPADDB` or `VPSUBB` instruction on a physical `xmm` register with `V128_BYTE`
kind and `XMM` width.

## Byte multiplication is a packed-short expansion

x86 has no packed low-byte multiply instruction. The pinned Graal source
`VectorAPIBinaryOpNode.expandByteMulViaShortOps` reinterprets adjacent byte pairs
as eight 16-bit lanes without widening the 128-bit register. It computes:

```text
lo = ((x & 255) * (y & 255)) & 255
hi = ((x >>> 8) * (y >>> 8)) << 8
result = reinterpret_as_16_bytes(lo | hi)
```

Every operation above is lane-wise on eight packed shorts. Discarding high bits
implements unsigned byte multiplication modulo 256. The same low product bits
also implement signed wrapping multiplication, but result widening is different.
The gate requires exactly two result-live
`i16x8` products, their matching input vectors, the exact masks and shift amounts,
the connected OR/reinterpret reconstruction, and all sixteen output cuts feeding
the checksum. It then requires exactly two allocated-register XMM `VPMULLW`
instructions of kind `V128_WORD`. It does not require or claim an `i8x16 MulNode`
or a nonexistent `VPMULLB`. A different genuine compiler expansion must be
reviewed explicitly; this source-pinned recognizer deliberately fails closed.

The reader distinguishes eliminated frame metadata from vector payloads. A byte
array is exempted only when its complete virtual owner/field/use chain proves it
is `FrameWithoutBoxing.indexedTags`, with matching frame arrays, constant initial
tags and deoptimization-state-only consumers. Materialization, escape, unknown
owners and actual vector payload references still fail. Arithmetic results and
both operands must be exactly physical XMM/128-bit values with the appropriate
byte or short kind. The signed fixture's special narrowed zero view for negation
does not apply here; actual wider arithmetic and unreviewed cast views fail.

This bounded gate currently supports x86-64 only. It does not claim a globally
allocation-free ABI, a throughput improvement, vector function/formal/capture/join
support, or no spills. In particular, interpreted JDK `ByteVector` fallbacks
allocate private `byte[]` payloads. The graph harness disables compiled-entry
counters; the separate instrumented correctness tests must assert their exact
per-input deltas. Model-only exports cannot pass this native evidence gate.

The original same-compilation-header handling is retained: duplicate identical
Graal metadata is allowed, but distinct compilation identities or repeated final
LIR phases are rejected. An explicit offline checker correction can change only
the reader and its test source, retains the original failed reader and exit
status, and does not replay guest execution. Runtime, probe, runner, JAR and JDK
hashes remain frozen. The evidence stores `physicalPackedInstructions` as a list
of one byte instruction or two word-product instructions, alongside the graph
expansion details.

No Word8X16 runtime capture is claimed by this harness's source or synthetic tests.
The separate parser mutation tests use synthetic graph/LIR data and are not
themselves SIMD evidence:

```sh
python3 bench/experiments/word8x16-foundation/test-runtime-audit.py
```
