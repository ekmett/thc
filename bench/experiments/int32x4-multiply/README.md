# Signed Int32X4 multiplication graph controls

This bounded harness consumes real exported `SimdInt32X4Multiply` Core and
source-matched native GHC rows from `build/simd-int32x4-multiply`. Only
`timesCase` is captured: pre/post-Tidy Core on AST and bytecode, four captures.
Each root takes two scalar `Int#` seeds and returns a weighted machine-Int
checksum observing all four signed result lanes. Residual scalar and tuple
helpers belong to the separate semantic tests, not this selected graph claim.

Pinned GHC 9.14.1 defines `timesInt32X4#` with two `Int32X4#` operands and one
vector result. Existing pack/unpack uses one logical four-`Int32#` tuple, not
`Word32#` lanes or a vector argument ABI. The pinned JDK `IntVector.mul` uses
wrapping low-32-bit multiplication. Signed and unsigned multiplication share
those low bits; their observed widening is different.

After preparing native inputs with `scripts/prepare-int32x4-multiply-audit.py`
and building `installDist`, use the pinned Graal JDK and a fresh output directory:

```sh
bash bench/experiments/int32x4-multiply/run-runtime.sh build/int32x4-multiply-runtime-capture
```

Reserve this checkout's build directory with the host's shared resource gate
around the complete command. The runner does not build or prepare fixtures. It
rejects an existing output directory and stops on the first failure, preserving
the exact command, log and exit status. Compilation settings remain unchanged:
forty interpreted corpus passes, one explicit compile, then two complete checked
corpus passes. Every checked input must preserve the native result, active target
identity and installed last-tier entry validity. There are no retries, settling
passes, compiler-limit changes or altered inlining policies.

## Signed packed product gate

The selected pre-lowering graph must contain exactly one result-connected
`MulNode` with four `i32` components. All four output `SimdCutNode` lanes must
feed exactly one `SignExtendNode` from 32 to 64 bits before contributing to the
public checksum. Evidence records the four identities as `signedLaneExtensions`.
Zero extension, wrong widths, duplicate or missing lane identities, bypassed
conversion and dead packed arithmetic fail. This matches the fixture's
`int32ToInt#` observations in [-2147483648, 2147483647].

Final allocated-register LIR must contain exactly one `VPMULLD` instruction:
the result and both operands must be physical `xmm` registers of `V128_DWORD`
kind, with `XMM` width. A pair of smaller products, wider instruction, cast view,
scalar product, mnemonic comment or different opcode is not a substitute. The
pinned Graal DWORD lowering directly supports this instruction. Any genuinely
different future compiler shape requires review; the recognizer fails closed.

Surviving vector/carrier/array allocation, payload array access, field traffic,
calls, lane boxes and additional Long boxes fail. The two host Long unboxes,
host Object-array reads and one public Long result box are explicitly allowed.
The only byte-array exception proves the complete virtual owner/field/use chain
for `FrameWithoutBoxing.indexedTags`, matching frame arrays, constant initial
tags and deoptimization-only consumers. It is not a general virtual-array
exception: materialization, escaping references and unknown owners still fail.

The input gate verifies source/artifact hashes, native GHC identity and commands,
independent integer-model agreement and strict audits. Runtime sources, installed
JARs, JDK binaries/modules, probe, runner and reader are frozen across capture.
Raw BGV/CFG, selected parsed graph, final LIR and all command chains are retained.
Repeated identical compilation metadata headers are tolerated; distinct
compilation identities or repeated final LIR phases are rejected.

An explicitly requested offline reader correction may change only the reader
and its parser tests, retaining the original failure and reader hashes. It must
not rerun guests or alter runtime, probe, runner, JAR or JDK inputs.

Synthetic parser tests are not hardware evidence:

```sh
python3 bench/experiments/int32x4-multiply/test-runtime-audit.py
python3 -O bench/experiments/int32x4-multiply/test-runtime-audit.py
```

The four existing unsigned Word32X4 multiplication captures have the same
two-input host shape and can serve as exact opposite-signedness controls. They
must fail specifically because their live lanes use `ZeroExtend32to64`, not
because of a different host arity. Such controls are not signed native results.

The [retained x86-64 evidence](evidence-x86_64/README.md) contains four actual
captures at frozen runtime `597ed24ee8835606437a7cdeb313e28b726d4762`, all passing
the original checker. Its bounded claim is packed-code evidence, not throughput, no-spill,
cross-platform or globally allocation-free proof. Interpreted JDK `IntVector`
fallbacks can allocate private `int[]` payloads; the public result box remains.
Vector formals/results/captures/heap fields remain unsupported. Graph captures
disable compiled-entry counters; separate instrumented tests must retain their
exact per-input counter checks. Model-only exports cannot pass the native gate.
