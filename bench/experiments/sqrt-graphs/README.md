# Public square-root production graphs

These four fixed-input controls run genuine `Prelude.sqrt` Core through the AST
and BytecodeDSL runtimes. Native GHC supplies 13 integer consumer results per
precision. The separate sqrt oracle checks IEEE bit patterns; this graph harness
uses dynamic `Int#` inputs to expose the complete conversion/sqrt/consumer path.
There are no timing measurements or forced guest inlining decisions.

```sh
export JAVA_HOME=/path/to/pinned/graalvm
cabal run exe:thc-fixtures --offline -- sqrt
python3 bench/experiments/sqrt-graphs/run.py /tmp/new-sqrt-graphs \
  --capture bench/experiments/sqrt-graphs/captured
```

Commit runtime changes before running. The runner requires a fresh output
directory, verifies committed runtime source hashes, rebuilds `installDist`, and
records the runtime jars, native oracle/fixture manifest, tooling and JDK release at
launch. It reuses the fixed-input tuple graph harness and strict allocation/call
auditor, then requires exactly one `SqrtNode` and a final square-root instruction
at the expected precision. Raw BGV/CFG files stay in the output directory. Only
pre-lowering and final low-tier snapshots are parsed. `--collect-only` checks
launch hashes, exact entry/backend/native-row labels, raw graph hashes, and LIR
against the original CFG before packaging evidence.

All four controls retain the exact compiled entry after native replay:

| Consumer | AST before/after lowering | Bytecode before/after lowering |
| --- | ---: | ---: |
| `floatCase` | 19 / 94 | 44 / 118 |
| `doubleCase` | 19 / 94 | 44 / 118 |

Each graph has one dynamic square-root node and one final host-result Long box.
There are no intermediate floating boxes, allocation nodes or residual guest
calls. The recorded final AArch64 LIR uses `FSQRT SINGLE` or `FSQRT DOUBLE`;
Float's exact widening and result narrowing disappear around the square root.
Argument/result conversions remain as the workload requires. Virtual bytecode
frame-state metadata is not a heap allocation.

Graph instrumentation is disabled: these controls establish exact target validity,
not per-row compiled-entry counters. `SqrtPrimitiveTest` separately requires exact
counter increments for each measured row in both export stages, backends,
inlining modes and the CI scalar-handoff control. Non-inlined floating calls
retain the existing Object ABI. The graph result does not establish universal
inlining or spill-free execution, and the public Object return has no additional
result registers.
