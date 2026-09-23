# Word16X8 graph controls

This harness consumes actual exported `SimdWord16X8` Core and source-matched
native GHC rows from `build/simd-word16x8`. It checks `plusCase`, `minusCase`,
and `timesCase` on AST and bytecode at both retained Core stages: twelve captures.
Each root takes two scalar `Int#` inputs and returns a nonnegative weighted
checksum observing all eight unsigned lanes. No vector argument ABI is added.

The pinned GHC 9.14.1 surface comprises `packWord16X8#`,
`unpackWord16X8#`, `broadcastWord16X8#`, `plusWord16X8#`,
`minusWord16X8#`, and `timesWord16X8#`; there is no
`negateWord16X8#`. Pack and unpack transport eight `Word16#` components,
not eight `Int16#` components.

After preparing native inputs with `scripts/prepare-word16x8-audit.py` and building
`installDist`, invoke the harness with the pinned Graal JDK and a new directory:

```sh
bash bench/experiments/word16x8-foundation/run-runtime.sh build/word16x8-runtime-capture
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

## Packed words and unsigned observations

Each selected graph must retain exactly one connected arithmetic node with eight
`i16` components: `AddNode`, `SubNode`, or `MulNode`. These are physical
short bits, not a claim that `Word16Rep` is interchangeable with `Int16Rep`.
The pinned JDK `ShortVector` implements wrapping low-sixteen-bit arithmetic;
the same bits implement unsigned addition, subtraction and multiplication.

Every output lane must feed the public checksum through exactly one
`ZeroExtendNode` from 16 to 64 bits. All eight conversion identities are
recorded as `unsignedLaneExtensions`; signed widening, wrong-width widening,
and bypassing the conversion are rejected. This matches the fixture's
`word2Int# (word16ToWord# lane)` observations of 0..65535.

Final allocated-register LIR must contain exactly one respective `VPADDW`,
`VPSUBW`, or `VPMULLW` instruction. The result and both operands must be
physical `xmm` registers with `V128_WORD` kind and `XMM` width.
Multiplication is one direct packed i16x8 product: no byte-multiply reconstruction,
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
support, or no spills. In particular, interpreted JDK `ShortVector` fallbacks
allocate private `short[]` payloads. The graph harness disables compiled-entry
counters; separate instrumented correctness tests must assert their exact
per-input deltas. Model-only exports cannot pass this native evidence gate.

The original same-compilation-header handling is retained: duplicate identical
Graal metadata is allowed, but distinct compilation identities or repeated final
LIR phases are rejected. An explicit offline checker correction can change only
the reader and its test source, retains the original failed reader and exit
status, and does not replay guest execution. Runtime, probe, runner, JAR and JDK
hashes remain frozen. Evidence records one `physicalPackedInstructions` entry
per capture alongside all eight unsigned lane-conversion identities.

The separate parser mutation tests use synthetic graph/LIR data and are not
themselves SIMD evidence:

```sh
python3 bench/experiments/word16x8-foundation/test-runtime-audit.py
python3 -O bench/experiments/word16x8-foundation/test-runtime-audit.py
```

## Retained x86-64 capture

[evidence-x86_64/README.md](evidence-x86_64/README.md) retains twelve actual captures
at runtime `5bbdb040a26ef98c784c2772e5eae0466a534123` on eak-quartus with
GHC 9.14.1 and Oracle Graal 25.3.4.1/JDK 25. Each root checks 268 native rows
twice after compilation, totaling 6,432 checked comparisons. All twelve pass
their first check, without reader correction or guest replay. The actual graphs
contain all 96 result-connected unsigned lane extensions. Final allocated LIR
contains four XMM VPADDW, four XMM VPSUBW and four XMM VPMULLW instructions.

These selected inlined graphs eliminate local vector carriers, payloads and
lane boxes; the public Long result box remains. This is bounded packed-code
evidence, not throughput, no-spill, globally allocation-free, vector-ABI,
cross-platform or machine-code-disassembly evidence.
