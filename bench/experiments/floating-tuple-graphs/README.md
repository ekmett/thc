# Floating tuple result graphs

This fixed-input experiment runs the genuine `FloatingTupleAudit` Core and native
oracle through the production AST and BytecodeDSL runtimes. It measures no timings.
It reuses the tuple graph harness and strict allocation/call auditor.

```sh
python3 scripts/prepare-floating-tuples.py
python3 bench/experiments/floating-tuple-graphs/run.py /tmp/thc-floating-graphs \
  --capture bench/experiments/floating-tuple-graphs/captured
```

`JAVA_HOME` must identify the pinned GraalVM. Commit runtime changes first: the
runner verifies their source hashes against HEAD and rebuilds `installDist` before
recording its launch manifest. Use a new or empty output directory so stale graphs
cannot enter the capture. The script leaves raw BGV/CFG and
complete final LIR outside the captured evidence, parsing only the two audited
compiler phases. `--collect-only` packages a run with a launch manifest and rejects
changed runtime jars, exported Core, oracle, native provenance, JDK release or
tooling inputs; recorded version information comes from launch time. The original
raw graph hashes, exact case checks and final LIR are rechecked during collection.

The captured eight controls retain the exact installed entry after native replay:

| Entry, normal inlining | AST pre-lowering nodes | Bytecode pre-lowering nodes |
| --- | ---: | ---: |
| `complexFloatCase` | 32 | 57 |
| `complexDoubleCase` | 32 | 57 |
| `mixedCase` | 46 | 71 |

All six normal-inlining graphs eliminate tuple carrier allocations, tuple field
traffic and residual guest calls. Scalar floating input boxes also disappear.
One Long box remains for the public Object result. Final physical LIR uses
`SINGLE`/`DOUBLE` floating registers and operations for these dynamic inputs;
this does not promise spill-free execution for arbitrary consumers.

Two `mixedCase` controls with guest inlining disabled retain actual
`OptimizedCallTarget.callBoundary` calls and generated typed result fields. The
public Object ABI has no additional result registers. Residual scalar floating
argument boxing remains outside this result-storage improvement.

Graph controls disable instrumentation and establish exact target validity after
execution. The separate `FloatingTupleTest` requires exact compiled-entry counter
increments on every measured native row in both modes, including the handoff run.
Evidence records the runtime commit, source/jar/fixture hashes, native provenance,
graph hashes, allocation/memory node inventories and final physical LIR excerpts.
