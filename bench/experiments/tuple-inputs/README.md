# Typed tuple input graph controls

This experiment separates genuine exported call edges from synthetic control-flow
stress cases. It uses the production AST and BytecodeDSL runtimes, normal compiler
limits and fixed correctness inputs. It performs no timing campaign.

The twelve native controls use fresh `TupleInputAudit` pre-Tidy Core: `pairInputs` supplies
two independent Longs through one logical unboxed tuple; `mixedCase` combines
Long, constant Float/Double leaves, a nested empty tuple and a lifted reference;
`lazyCase` passes a genuine recursive bottom as an unused lifted leaf. Each runs
with normal guest inlining and with guest inlining disabled, on both backends.
The prepared native rows and independent formulas are checked separately before
capture. The graph harness warms those rows once, compiles the observed guest
target once, and checks two complete replay passes without changing its identity
or accepting invalidated code. It does not compile the host wrapper or instrument
entry counters; the regular native JVM tests independently enforce exact compiled
entry counts and host/original/active target validity at both export stages.

The six `loops/*.json` controls are synthetic Core, not a new GHC native oracle.
They exercise direct self transfer, A→B→C→A, and a two-call prefix followed by the
C→D→E→C cycle. Independent host arguments supply an initial payload and dynamic
depth. Each iteration adds depth to the payload and decrements depth; the returned
scalar is wrapped `x + 7 + depth*(depth+1)/2`. Six rows include depth 0, 1, 2, 3,
17 and 64 and full-width payload boundaries. The harness warms once, explicitly
compiles once through the public compile member, then checks the first reverse
replay with valid host, original guest and unchanged observed active targets.

`read_graphs.py` identifies the cyclic strongly connected component, expands
integer phi recurrences, attributes result boxes to returns outside the loop,
and rejects allocation commits, guest calls and transport fields inside it.
`read_cfg.py` inventories lowered memory and calls. `read_lir.py` independently
finds final physical-instruction cycles, requires arithmetic and backedges, and
rejects allocation/guest-call/transport instructions in those loops. Normal
thread-local safepoint polling and its handshake slow path are reported separately.
Virtual objects retained only in frame states are not heap allocations.

The captured evidence records exact source, tools, native manifest, JAR and raw
BGV/CFG hashes. The collector rechecks launch inputs, raw hashes and exact case
labels, regenerates graph inventories, and derives final LIR directly from CFG.
Do not attach a newer runtime revision to an older capture. Native and synthetic
results remain separately labelled in `captured/evidence.json`.

## Reproduce

Use the pinned Graal JDK and GHC from the project setup. Fresh native preparation
is required; no model-generated row can substitute for the native oracle.

```sh
GHC=ghc GHC_PKG=ghc-pkg python3 scripts/prepare-tuple-input-audit.py
JAVA_HOME=/path/to/pinned-graal \
  python3 bench/experiments/tuple-inputs/run.py /tmp/thc-tuple-input-graphs \
  --capture /tmp/thc-tuple-input-summary
JAVA_HOME=/path/to/pinned-graal \
  python3 bench/experiments/tuple-inputs/run.py /tmp/thc-tuple-input-graphs \
  --collect-only --capture /tmp/thc-tuple-input-summary
```

`installDist` is built by the runner. No compiler threshold, loop explosion,
graph-size, heap or retry limit is raised. Collection requires the original
hash-matched files, including raw graphs and native inputs. The compact committed
summary is not a substitute for those raw files when rerunning the collector.

## Scope and earlier failures

Typed storage is not by itself evidence of register passing. A compiled residual
edge may allocate a precise carrier and a one-element outer Truffle packet; no
tuple leaves are encoded as Object-array elements. Fully inlined controls account
for removal of that transport separately. Scalar Long Object-return boxing may
remain, outside the loop. Register allocation may spill to ordinary compiler stack
slots; no universal no-spill or residual multi-register ABI claim is made.

Earlier fixed-depth controls optimized the entire loop away and are excluded from
the dynamic-loop claim. A real first-call AST host invalidation was traced to an
anonymous result destination whose captured slot array lacked the existing
`AstTupleDestination` compilation-final annotation. Reusing that destination
fixed the unchanged strict gate. A separate dynamic-target cleanup loop explosion
was fixed by the frame-free generic cleanup helper. No settling, extra compilation
attempt or weakened assertion accompanies either fix. Historical failing captures
remain outside this compact successful capture and are not relabelled as passes.
