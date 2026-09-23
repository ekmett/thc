# SIMD foundation mechanism probe

This fixed-input correctness and compiler inspection probe exercises the installed
Graal 25.3.4.1 / JDK 25 Vector API through an ordinary Truffle root. It checks 49
independent pairs, forces compilation with normal compiler limits, executes all
pairs again, and verifies that the exact installed entry remains valid. This is
mechanism evidence; it does not yet claim THC Core vector support.

Build `installDist`, then run:

```sh
JAVA_HOME=/path/to/pinned/graalvm \
  bench/experiments/simd-foundation/run.sh build/simd-foundation-probe
```

`THC_RUNTIME_ROOT` may point to another built checkout. Compact headers are enabled.
The script adds the JDK incubating Vector API module and requires 128-bit integer
SIMD (AArch64 ASIMD or AMD64 SSE2). It does not change compiler size or time limits
and does not measure throughput.

The audit checks the pre-lowering graph for allocation/invoke nodes, then inspects
final LIR after register allocation for packed add, XOR, and shift instructions.
The scalar `Long` box required by the outer `Object` return remains. Included
AArch64 evidence has physical `v0`/`v1` vector registers. x86 must run the same
script; an AArch64 result is not evidence of x86 generated code.

THC's intended durable vector storage is exact primitive lane fields or typed
frame slots. The Vector API serves as the transient arithmetic intrinsic, not a
persistent array-backed guest value. GHC `VecRep 2 Int64ElemRep` remains a distinct
logical vector representation even where its physical spill slots are two longs.

## Actual THC Core execution

After `installDist` and `scripts/prepare-simd-audit.py` (or its explicit
`--export-only` AArch64 mode), run:

```sh
JAVA_HOME=/path/to/pinned/graalvm \
  bench/experiments/simd-foundation/run-runtime.sh build/simd-runtime-native
```

The runtime harness executes the genuine exported Core on both backends against
49 native GHC rows for each supported entry, compiles the exact entry, runs it
again twice, and checks that installed code remains valid. It audits the
pre-lowering graph for vector carrier/array allocations, field traffic and
invokes, then requires packed 128-bit ADD/SUB in physical registers. The vector
join frontier remains a rejection control in JVM tests.

AArch64 uses its own genuine pre-Tidy export and explicitly reuses the committed,
source-matched x86 native oracle. x86 fresh preparation checks both pre/post-Tidy
exports and a newly generated native oracle. The input provenance distinguishes
these paths. `runtime-aarch64/` records four production controls, separate from
the earlier mechanism probe. It proves SIMD arithmetic within scalar THC roots,
not an ABI that passes vectors across residual guest calls in SIMD registers.
