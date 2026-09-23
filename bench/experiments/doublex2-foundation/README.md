# DoubleX2 production graph controls

The harness uses genuine exported `SimdDoubleX2` Core and source-matched native
GHC rows. It selects the two independent-input `plusCase`, `minusCase`, and
`timesCase` entries on both backends at every retained export stage. It keeps
normal inlining settings and performs no timing. Every postcompile input checks
the native result, active call-target identity, and exact entry code validity.
Compiled-entry counters are disabled here; `SimdDoubleVectorTest` independently
requires exact per-input counts with instrumentation enabled.

Prepare inputs with `python3 scripts/prepare-doublex2-audit.py` on a supported
GHC native backend, then build `scripts/gradle.sh --no-daemon installDist` and run:

```sh
bench/experiments/doublex2-foundation/run-runtime.sh build/doublex2-runtime
```

The graph gate rejects model-only provenance and stale source/artifact hashes.
It snapshots runtime sources and installed JARs before launch and verifies them
after capture. The graph and CFG must name the exact selected compilation root.
Before lowering, no vector/wrapper/array allocation, intermediate floating box,
field traffic or residual guest call may survive. The final allocated-register
LIR must contain packed binary64 ADD/SUB/MUL for the corresponding operation,
with no fused multiply/add. The public Long result box is expected and counted.
Raw BGV/CFG files and compact final LIR remain in the selected output directory.

An export-only run is useful for local model checks but cannot pass this native
production gate. A transferred oracle must retain verified source hashes and
its original native toolchain provenance; executing that input on another JVM
platform does not imply native GHC execution there. Movement/edge entries may
legitimately scalarize because only one output lane is observed; they are strict
bit-sensitive correctness controls, not packed-code evidence.
