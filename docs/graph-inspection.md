# Inspecting compiled graphs

Use actual Graal output to investigate residual calls, allocations and loop
state. Interpreter source, an installed call target or a small graph diagram
does not establish what survives compilation.

## Capture and export

With the [pinned toolchain](../README.md) and prepared runtime/fixtures:

```sh
bin/try.sh
bin/dump-graph.sh sumLoop 10000 work/graphs/sum-inspection
```

Choose a fresh output directory. [The capture script](../bin/dump-graph.sh)
runs the diagnostic probe with compilation tracing, scheduled BGV graphs and
backend CFG output. It compiles [GraphInspect](../tools/GraphInspect.java) against
the parser bundled with the selected Graal JDK, then exports every BGV phase
to JSON and CFG DOT under `parsed-*` directories. `index.json` identifies each
graph's group, name, phase, node counts and output paths. The optional
`THC_GRAPH_MODULES` selects a different comma-separated Core input bundle.

GraphInspect's command-line arguments are an input BGV, output directory and
optional graph-name regular expression. When using it directly, retain the
Graal parser module exports used by the script. The default expression selects
late phases; pass an explicit selection when inspecting a before-high-tier loop.
It exports real nodes, typed edges, scheduled blocks and source properties,
not an inferred diagram of interpreter code.

Capture separately from timing. Dumping changes compilation cost; use the
[benchmark workflow](../README.md#performance) for matched execution measurements.

## Summation-loop check

[`check-sum-loop-graph.py`](../tools/check-sum-loop-graph.py) accepts one exported
graph named `Before phase HighTierLowering`:

```text
python3 tools/check-sum-loop-graph.py GRAPH.json --output CHECK.json \
  --dot LOOP-NODES.dot --cfg-dot LOOP-BLOCKS.dot
```

Select `GRAPH.json` from the capture's index. The checker derives natural loops
from scheduled successors, dominators and LoopBegin/LoopEnd associations. Its
summation signature requires primitive induction and accumulation recurrences;
it does not mistake a host argument-conversion loop for that guest loop.
All scheduled nodes on continuing paths are checked, not just displayed nodes.
Boxing, allocation, heap loads, calls and unreviewed operation classes fail the
check. Virtual object/state descriptions are not treated as actual allocations.

## Interpretation limits

The loop check concerns one compiler snapshot and its continuing paths, not entry,
exit, the whole program or final machine code. A source position in a loop's
connected component does not prove that an allocation executes on each iteration.
Follow scheduled control flow, value aliases and escaping sinks.

Graph-node counts are not instruction counts. Check later lowering phases for
allocation claims and final LIR/code for machine-instruction claims. Distinguish
a surviving call from inlined code, and a constant target from a decision to
inline it. Source and graph evidence must match the compilation being diagnosed.

The [packet audit](call-packets.md#current-graph-audit) separately tracks argument
arrays, boxing, other allocations and null-result guards. Zero packet allocations
does not imply zero constructor/frame allocations or allocation-free callees.
A guard and its value path describe graph structure, not which arm executed or
which condition caused a recorded deoptimization.
