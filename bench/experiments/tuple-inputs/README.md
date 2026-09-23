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

## Captured results

The complete 18-control capture is pinned to `a94c63a12e5f6e202f28db75c1b2b93969f07904`;
production runtime files are unchanged from
`752de4b57bf62b7882f90d1fdcdd38556ed0b0cf`.
All 12 native controls pass two replay passes: 116 selected native rows per pass,
232 comparisons total. All six synthetic loop controls pass their first compiled
six-row replay, another 36 formula checks. These counts exclude interpreted
warming and do not combine native and synthetic rows into one oracle claim.

| Native entry | AST inline high/low nodes | Bytecode inline high/low nodes |
| --- | ---: | ---: |
| pairInputs | 31 / 118 | 56 / 141 |
| mixedCase | 26 / 112 | 49 / 134 |
| lazyCase | 25 / 111 | 48 / 133 |

Every inline native graph has no committed tuple carrier, call packet, transport
field traffic or guest call. One Long Object-return box remains. Mixed floating
leaves are constants in this workload, so this is not evidence for dynamic
floating arithmetic throughput. The unused bottom remains undemanded.

Every residual native graph commits exactly one precise input carrier and one
Object array of length 1. The carrier has three payload/header fields for pair
and lazy inputs, five for mixed inputs. The mixed case additionally allocates its
ordinary boxed `Box` value. Residual call boundaries and ownership cleanup memory
remain visible. Their existence is not described as register passing.

| Dynamic loop | AST high-tier nodes | Bytecode high-tier nodes |
| --- | ---: | ---: |
| self | 163 | 150 |
| A→B→C→A | 218 | 185 |
| A→B→C→D→E→C | 316 | 279 |

Both high-tier and physical-LIR cycles retain payload/depth recurrences and exit
conditions. No carrier, packet or tail-transfer allocation, transport memory or
guest call remains inside the loop. All remaining Long boxes lie outside its SCC
and feed returns. The independent review also distinguishes the two prefix steps
from the surviving C/D/E loop. The usual safepoint handshake remains.

An initial residual inspector incorrectly expected named handoff-field stores,
a condition copied from the result-slab experiment. Input initialization had
already lowered to `INIT_LOCATION` writes. That capture passed all native and
installed-target checks; the corrected inspector rechecked its original graph
offline by inspecting the committed carrier's exact fields and Object[1] packet.
The final full capture uses that corrected inspector from launch. The initial
raw/log hashes and correction are recorded separately in `captured/validation.json`;
no runtime result was repaired by replay.

The later `7bddb65` correctness fix affects legacy scalar locals without an exact
primitive source proof beside a tuple. Such a bytecode local can become
object-backed after Long/Float/Double target alternation; it now uses a generic
read with a checked cast. Exact tuple leaves retain primitive accessors. All 18
focused methods pass in both modes on that successor, including the original
failing transition. The graph capture remains explicitly historical at `a94c63a`;
it is not relabelled as a capture of this later source.

Two additional bytecode inline controls are pinned to the actual final runtime
`7bddb65ab2e0385e147c3b76971f77fe32caec20` in
`captured/postfix-evidence.json`. `pairInputs` and `mixedCase` retain the same
56/141 and 49/134 high/low node counts, with no committed transport allocation,
transport field traffic or guest calls; each still has the scalar Long return
box. Both pass two native replay passes with unchanged valid active targets:
18 selected rows per pass, 36 comparisons. This specifically checks that the
new source-proof selection folds for these exact primitive inputs. It does not
recapture the residual or cycle controls and makes no new claim about them.

The bounded runner builds the current main JAR with `jar` and combines it with
the installed dependency JARs, excluding the installed THC JAR. This preserves
the old capture's installed runtime. It records and verifies every selected
runtime, tool, native-input and raw-graph hash independently:

```sh
JAVA_HOME=/path/to/pinned-graal \
  python3 bench/experiments/tuple-inputs/run_postfix.py /tmp/thc-tuple-input-postfix \
  --capture /tmp/thc-tuple-input-postfix-summary
JAVA_HOME=/path/to/pinned-graal \
  python3 bench/experiments/tuple-inputs/run_postfix.py /tmp/thc-tuple-input-postfix \
  --collect-only --capture /tmp/thc-tuple-input-postfix-summary
```
