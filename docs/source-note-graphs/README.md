# Source notes and primitive local joins

These are actual Graal graphs for GHC's exported `RepresentationAudit.joinLoop`, not diagrams inferred from the interpreter. The loop sums `1..n` through a local join with two `Int#` arguments. The five captures use inputs 10,000–10,015, ten seconds of warmup, instrumentation disabled, and verified installed guest code and checksums.

Source notes preserve the graph topology in both backends. The captures also exposed an unrelated AST enum-dispatch barrier. Replacing that switch with direct enum identity comparisons removes the barrier in the separate **v2** capture.

| Guest root | Compilation | After PE | Before high lowering | After mid tier | Loops before high / after mid |
|---|---:|---:|---:|---:|---:|
| AST v1, notes on | 1910 | 343 | 431 | 1178 | 9 / 17 |
| AST v1, notes off | 1905 | 343 | 431 | 1178 | 9 / 17 |
| Bytecode v1, notes on | 1989 | 101 | 101 | 137 | 1 / 1 |
| Bytecode v1, notes off | 1958 | 101 | 101 | 137 | 1 / 1 |
| AST v2, notes on | 1912 | 98 | 86 | 330 | 1 / 4 |

The [topology comparison](topology-comparison.json) checks every node ID, class and block assignment, every labeled/indexed edge, and every scheduled block. All three phases match exactly between source-on and source-off within each backend. This is stronger than equal counts. Bytecode's selected node properties also match. AST's per-process ancestry bloom constant and its dependent OR stamp differ; the report records those differences. Source locations, compilation identifiers, machine-code addresses and timing/profile annotations are outside this comparison. These captures do not establish equivalence for every program or a throughput result.

## The loop, in the graph

The [SSA/CFG audit](join-graph-audit.json) includes actual node IDs and recurrence edges. In bytecode C1989, `LoopBegin 2147` is in B3 and `LoopEnd 2148` returns B4→B3. Its `i64` phis are 2305 (counter) and 2306 (accumulator). After the peeled first iteration, they start at `n-1` and `1`; backedge nodes 2218 and 2223 compute `k-1` and `acc+(n-k)+1`.

In AST v2 C1912, `LoopBegin 305` and `LoopEnd 1820` give B2→B1. Counter phi1979 starts at `n` and receives Add1117 (`k-1`); accumulator phi1984 starts at zero and receives Add1430 (`acc+(n-k)+1`). The extra primitive phis are retained in the audit. Four later mid-tier loops are compiler transformations of the remaining body, not surviving result-type variants. Bytecode’s after-mid graph contains `MapVectorNode`, `SequenceVectorNode` and `FoldVectorNode`; this demonstrates vectorization in Graal IR, without establishing a particular machine instruction sequence.

All five guest roots have no residual method calls or Object[] packets. Their cyclic CFG blocks have no materialized allocation or boxing. No materialized `LocalJoinJump`, `TailCall`, closure or capture object survives. Each root retains one `java.lang.Long` result allocation site outside all CFG cycles: this is not an allocation-free root. Host Polyglot bridge graphs were captured separately and are excluded from these guest-loop claims.

- [Repaired AST CFG](captures/ast-v2-on/before-high-cfg.svg) · [node/edge JSON](captures/ast-v2-on/before-high.json)
- [Bytecode CFG](captures/bytecode-on/before-high-cfg.svg) · [node/edge JSON](captures/bytecode-on/before-high.json)
- [Original AST CFG](captures/ast-on/before-high-cfg.svg) · [node/edge JSON](captures/ast-on/before-high.json)

The images preserve all scheduled block edges and display selected real nodes. Omitted debug, constant and bookkeeping labels are disclosed on each image; the JSON retains all nodes and edges.

## The AST barrier

The original graph contains constant ordinal zero (node5), an `int[]` constant (254), `LoadIndexed 253`, and `IntegerSwitch 255` with four keys plus a default. Their source is `FunctionBody.execute`, `Program.kt:514`. The [frozen JVM bytecode](FunctionBody.javap.txt) identifies the array as Kotlin's `FunctionBody$WhenMappings.$EnumSwitchMapping$0` and shows `getstatic → ordinal → iaload → tableswitch`.

Although the result kind is known, the synthetic mutable array prevents the switch from folding here. Long, data, closure, address and fallback body paths remain, followed by additional loop transformations. The original graph is allocation-clean but unnecessarily large. The [barrier evidence](ast-enum-barrier.json) retains the exact nodes and typed entry paths. The v2 graph has no enum mapping load or `IntegerSwitch`; its before-high graph has one loop instead of nine. The [separate Map benchmark](../debug-locations.md) measures the performance change; it is not inferred from that node reduction.

## Source attribution and reproduction

The notes-on captures report 84 source spans and one attributed root. Their Truffle compilation traces identify `compiler/test-fixtures/RepresentationAudit.hs:34`; actual Graal node positions also contain the Haskell locations. Before high lowering, 195 original AST nodes, 38 bytecode nodes, and 26 repaired AST nodes contain that attribution. Both notes-off captures report zero attached spans/roots and zero Haskell positions in those graphs.

[Manifest](manifest.json), [v1 runtime mapping](runtime-manifest.json), [v2 runtime mapping](runtime-v2-manifest.json), and per-capture provenance identify the exact sources, JARs, input, command, and raw graph hashes. The manifests' source hashes are authoritative: the captures include uncommitted snapshots, rather than pretending the recorded base revision contains every change. [Frozen runtime sources](frozen-runtime-sources.tar.xz) and the [exported Core fixture](RepresentationAudit.json) retain those inputs.

The [raw BGV archive](selected-bgv.tar.xz) contains the five selected guest compilations, with their original source positions. Compact phase JSON omits bulky phase timing/debug properties and extracts guest/Java source positions; it preserves the node/edge/CFG structure. To reparse the exact compiler output with GraalVM 25.3.4.1:

```sh
export JAVA_HOME=/path/to/graalvm-jdk-25
bash docs/source-note-graphs/reparse.sh /tmp/thc-source-graphs
```

`capture-source.sh`, `provenance-source.py`, `audit-source.py` and `publish-source.py` preserve the original working-directory tools. Their commands reference the recorded frozen runtime directories; fresh execution requires reconstructing that runtime and recording its JAR hashes, not silently substituting the current checkout. `render-cfg.py` regenerates DOT from the retained phase JSON; Graphviz produces the SVGs. The AST-on validator initially assumed an unavailable `localJoinCount` diagnostic; the provenance records that tooling correction and archives its original validator. Guest execution and its graphs were not rerun or altered for that correction.
