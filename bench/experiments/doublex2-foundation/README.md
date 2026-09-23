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

For the frozen `69615799` source handoff, import the verified archive without
executing its native binary, then use the same graph command above:

```sh
python3 bench/experiments/doublex2-foundation/import-native.py ARCHIVE.tar.gz \
  --sha256 fe2c47ebd02e530facd2a8d83f26c6aef0fbe10e558de520be6b90c51c11b02e
```

The importer checks every payload hash, all ten fixture/exporter sources against
the current checkout, exact Core inventories and strict audits for both stages,
and every native row against the local integer model. It keeps the complete
original native provenance, commands and inputs under `build/simd-doublex2/imported-native`.
A new local provenance record identifies this as imported native data and records
the current validation tools separately. It does not rerun GHC or the native binary.

## Retained native-backed AArch64 capture

`evidence-aarch64` records source revision
`d1a46e933021dc730d7853d1df69b4db22170154`. Its runtime code is the tested
`efede84` implementation; the later commits add import/provenance tooling.
The captured source/JAR hashes, original imported native metadata, compact LIR,
run logs and checksums are retained. Raw BGV/CFG hashes are in `evidence.json`;
the local raw files remain under `build/doublex2-runtime-aarch64-native`.
The native oracle came from the verified x86 GHC archive identified above.

All twelve pre/post-Tidy × AST/bytecode × arithmetic controls pass. Each uses
225 native rows in two postcompile passes: 5,400 result/active-target/validity
comparisons total. Before lowering, every AST graph has 39 nodes and every
bytecode graph has 64. Each final allocated-register LIR contains the required
128-bit `FADD`, `FSUB` or `FMUL` on `V128_DOUBLE`, with independent dynamic lane
inputs. No temporary vector/wrapper/array allocation, intermediate Double/Float
box, vector field traffic or guest call remains. One public Long result box
remains; this is not a zero-allocation Object ABI or a no-spill guarantee.

Separately, all five native-backed JVM tests pass in both default and handoff
runs. Each mode checks every one of the 3,390 native rows across both stages and
backends (13,560 measured calls), asserting the exact stage-derived compiled
entry delta, active target identity and installed entry validity after each.
The retained helpers produce 45,732 compiled guest entries per mode; these are
not 45,732 distinct inputs. No recompilation recovery or postcompile settling
was used. These focused results do not claim a full-suite run on this branch.
