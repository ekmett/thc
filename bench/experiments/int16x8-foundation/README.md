# Int16X8 graph controls

This harness consumes actual exported `SimdInt16X8` Core and source-matched
native GHC rows from `build/simd-int16x8`. It checks `plusCase`, `minusCase`,
`timesCase`, and `negateCase` on AST and bytecode at both retained Core stages.
Each root takes two scalar `Int#` inputs and returns a signed weighted checksum
that observes all eight distinct packed lanes. No vector argument ABI is added.

After preparing native inputs with `scripts/prepare-int16x8-audit.py` and building
`installDist`, invoke the harness with the pinned Graal JDK and a new directory:

```sh
bash bench/experiments/int16x8-foundation/run-runtime.sh build/int16x8-runtime-capture
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

Before lowering, the selected graph must contain exactly one connected arithmetic
node with eight `i16` components. Every lane must feed the public checksum.
Surviving carrier/vector/array allocation, primitive array loads, field traffic,
calls, intermediate lane boxing, and additional Long boxes are rejected. The two
host Long input unboxes, host Object-array reads, and one public Long result box
are allowed explicitly. Final allocated-register LIR must contain the respective
`VPADDW`, `VPSUBW`, or `VPMULLW` instruction on a physical `xmm` register with
`V128_WORD` kind and `XMM` width. Packed negation must have `NegateNode` or explicit
zero-minus semantics in the graph and lower to `VPSUBW`.

This bounded gate currently supports x86-64 only. It does not claim a globally
allocation-free ABI, a throughput improvement, vector function/formal/capture/join
support, or no spills. In particular, interpreted JDK `ShortVector` fallbacks
allocate private `short[]` payloads. The graph harness disables compiled-entry
counters; the separate instrumented correctness tests must assert their exact
per-input deltas. Model-only exports cannot pass this native evidence gate.

Parser mutation tests use synthetic graph/LIR data and produce no SIMD evidence:

```sh
python3 bench/experiments/int16x8-foundation/test-runtime-audit.py
```
