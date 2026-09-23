# Linux x86-64 mechanism evidence

Verified 2026-09-23 on **eak-quartus**, Intel Core i9-12900K (Alder Lake),
Linux x86-64. This is not the Castlemeadow host. Full CPU flags and kernel are
retained in `cpu.txt` and `kernel.txt`; SSE2 and AVX2 are present.

Source: `b79addb6928aae0c2e451f3406f65736d20ade36` (`codex/simd-foundation`).
Runtime classpath: the pinned installDist from THC
`812f8dcec015de8909838e2b2b2b2da2b6acd6b8b6` in
`/home/ekmett/ai/thc-library-coverage-01a0cdeb`.
JDK: Oracle GraalVM 25.3.4.1+1.1 / Java 25.0.4.1, recorded in the adjacent
release and version files. Compact object headers were enabled.

Reproduction from this checkout:

```sh
. /home/ekmett/thc-benchmarks/2026-09-23-083914/environment.sh
THC_RUNTIME_ROOT=/home/ekmett/ai/thc-library-coverage-01a0cdeb \
  bench/experiments/simd-foundation/run.sh build/simd-foundation-probe
```

The unmodified probe and audit exited successfully: all 49 independent input
pairs agreed with the scalar formula before and after explicit compilation,
and the exact target remained last-tier valid. The final allocated LIR contains:

```text
xmm0|V128_QWORD = VPADDQ (x: xmm1|V128_QWORD, y: xmm0|V128_QWORD) size: XMM
xmm1|V128_QWORD = VPSLLQ x: xmm0|V128_QWORD size: XMM y: 3
xmm0|V128_QWORD = VPXOR (x: xmm0|V128_QWORD, y: xmm1|V128_QWORD) size: XMM
```

The audited high-tier graph has no vector-object/array allocations or residual
invoke nodes. The scalar `Long` box at the outer Object-return boundary remains.
This host used VEX-encoded instructions; it does not establish generated-code
behavior on an SSE2-only CPU. No audit recognition changes were necessary.

`evidence.json` retains source and output hashes; `final-lir.txt` is the complete
last `After FinalCodeAnalysisStage` block. Full BGV/CFG graphs remain under the
local `build/simd-foundation-probe/graphs` directory.

This establishes the pinned Truffle/Vector API lowering mechanism, not THC Core
SIMD support or throughput. The unmodified probe checks target validity after
execution, not a per-invocation compiled-entry counter; those are different
claims. No timing campaign or compiler-limit changes were made.
