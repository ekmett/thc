# Word32X4 graph controls

This harness consumes actual exported `SimdWord32X4` Core and source-matched
native GHC rows from `build/simd-word32x4`. It checks `plusCase`, `minusCase`,
and `timesCase` on AST and bytecode at both retained Core stages: twelve captures.
Each root takes two scalar `Int#` inputs and returns a nonnegative weighted
checksum observing all four unsigned lanes. No vector argument ABI is added.

The pinned GHC 9.14.1 surface comprises `packWord32X4#`,
`unpackWord32X4#`, `broadcastWord32X4#`, `plusWord32X4#`,
`minusWord32X4#`, and `timesWord32X4#`; there is no
`negateWord32X4#`. Pack and unpack transport four `Word32#` components,
not four `Int32#` components.

After preparing native inputs with `scripts/prepare-word32x4-audit.py` and building
`installDist`, invoke the harness with the pinned Graal JDK and a new directory:

```sh
bash bench/experiments/word32x4-foundation/run-runtime.sh build/word32x4-runtime-capture
```

Acquire the host's shared resource gate around the complete capture command,
reserving this worktree's actual `build` directory. The runner does not rebuild
or prepare fixtures. It rejects an existing output directory and stops on the
first failed command, retaining the command, output, and exit status. It uses the
same compilation settings and forty interpreted corpus passes as the existing
DoubleX2 control, followed by one explicit compile and two checked corpus passes.
Every compiled input must preserve the native answer, active target identity,
and exact installed last-tier entry validity. There are no retries, postcompile
settling, compiler limit changes, or altered inlining policies.

The gate requires source/artifact hashes, the original native compiler identity
and commands, agreement with the independent integer model, and accepted strict
audits. It snapshots runtime sources, installed JARs, the harness, graph reader,
and JDK binaries/modules before execution and checks them afterward. It retains
raw BGV/CFG files, parsed graphs, exact command logs, and compact final LIR.

## Packed dwords and unsigned observations

Each selected graph must retain exactly one connected arithmetic node with four
`i32` components: `AddNode`, `SubNode`, or `MulNode`. These are physical
int bits, not a claim that `Word32Rep` is interchangeable with `Int32Rep`.
The pinned JDK `IntVector` implements wrapping low-thirty-two-bit arithmetic;
the same bits implement unsigned addition, subtraction and multiplication.

Every output lane must feed the public checksum through exactly one
`ZeroExtendNode` from 32 to 64 bits. All four conversion identities are
recorded as `unsignedLaneExtensions`; signed widening, wrong-width widening,
and bypassing the conversion are rejected. This matches the fixture's
`word2Int# (word32ToWord# lane)` observations of 0..4294967295.

Final allocated-register LIR must contain exactly one respective `VPADDD`,
`VPSUBD`, or `VPMULLD` instruction. The result and both operands must be
physical `xmm` registers with `V128_DWORD` kind and `XMM` width.
Multiplication is one direct packed i32x4 product: no byte-multiply reconstruction,
pair of products, or nonexistent `VPMULLB` is accepted. Wider arithmetic and
unreviewed cast views fail. A different genuine compiler shape needs explicit
review; this source-pinned recognizer deliberately fails closed.

Surviving carrier/vector/array allocation, primitive array loads, field traffic,
calls, intermediate lane boxing, and additional Long boxes are rejected. The two
host Long input unboxes, host Object-array reads, and one public Long result box
are allowed explicitly. The reader distinguishes eliminated frame metadata from
vector payloads. A byte array is exempted only when its complete virtual
owner/field/use chain proves it is `FrameWithoutBoxing.indexedTags`, with
matching frame arrays, constant initial tags and deoptimization-state-only
consumers. Materialization, escape, unknown owners and vector payload references
still fail; there is no generic virtual-array exemption.

This bounded gate currently supports x86-64 only. It does not claim a globally
allocation-free ABI, a throughput improvement, vector function/formal/capture/join
support, or no spills. In particular, interpreted JDK `IntVector` fallbacks
allocate private `int[]` payloads. The graph harness disables compiled-entry
counters; separate instrumented correctness tests must assert their exact
per-input deltas. Model-only exports cannot pass this native evidence gate.

The original same-compilation-header handling is retained: duplicate identical
Graal metadata is allowed, but distinct compilation identities or repeated final
LIR phases are rejected. An explicit offline checker correction can change only
the reader and its test source, retains the original failed reader and exit
status, and does not replay guest execution. Runtime, probe, runner, JAR and JDK
hashes remain frozen. Evidence records one `physicalPackedInstructions` entry
per capture alongside all four unsigned lane-conversion identities.

The separate parser mutation tests use synthetic graph/LIR data and are not
themselves SIMD evidence:

```sh
python3 bench/experiments/word32x4-foundation/test-runtime-audit.py
python3 -O bench/experiments/word32x4-foundation/test-runtime-audit.py
```

This initial harness commit makes no actual Word32X4 capture claim. Native
fixtures, exact source snapshots and successful production captures are separate
required evidence.
