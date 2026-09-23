# Matched Agnostic Force diagnostics

The candidate removes five artificial forcing loops from the actual compiled Map worker. The terminal-value invariant permits this: successful memoization publishes state 2 only after rejecting a returned Thunk. The candidate still has nine materialized thunk sites and nine associated capture sites. This is a graph simplification with a small measured allocation change, not broad thunk elimination.

| Selected worker | Existing Force loop | One-step Force |
| --- | ---: | ---: |
| Compilation ID | 3481 | 3487 |
| Root ID / role | 23 / lambda ww @ n/a | 23 / lambda ww @ n/a |
| PE nodes | 660 | 624 |
| Before-high nodes | 6,789 | 6,956 |
| After-mid nodes | 16,925 | 16,019 |
| Machine code bytes | 201,202 | 180,477 |
| Force-origin loops, before-high and after-mid | 5 | 0 |
| All loops, before-high / after-mid | 11 / 12 | 6 / 8 |
| Residual guest calls / Object[] sites | 75 / 75 | 76 / 76 |
| Materialized Long / Thunk / capture sites | 40 / 9 / 9 | 41 / 9 / 9 |
| Exact bytes/workload | 6,653,506 | 6,628,594 |

The [CFG/SSA audit](force-graph-audit.json) identifies Force loops by the first source-position frame, rather than counting nested guest loops merely because Force occurs somewhere in their call stack. Existing before-high LoopBegin nodes 3059, 4385, 18219, 22724 and 41758 have generic Object phis 3060, 4386, 18220, 22725 and 41759. Their incoming dataflow merges original values with memoized `Thunk.value` loads and, where present, evaluated constructors. The one-step graph has none of these Force-origin backedges. Real guest loops remain. Both call trees retain hot insertion as Inlined at child 2; the broader inline frontier changes slightly. Static call/allocation counts are not dynamic execution counts, and the larger before-high candidate count prevents describing every phase as smaller.

All three ThreadMXBean allocation samples are identical per variant: −24,912 bytes/workload (−0.3744%). Profiles measure allocation separately from throughput. JFR samples and full logs are retained, but no JFR/graph execution duration is presented as a speed result. [Graph comparison](graph-comparison.json) preserves the exact roots, frontier and allocation samples. The separately guarded [Agnostic throughput comparison](../agnostic/README.md) measured −0.471% elapsed time.

Both variants use the same certified Core, dependencies, native oracle, heap and runtime options as that comparison: bytecode, explicit Agnostic, 12,000 budgets/depth 2, class-owned layouts plus compact headers; other experimental switches false and caller-demand property absent. Source notes are on, runtime instrumentation off. Every profile/capture warmed at least 45 seconds and 30,000 calls, validated the native checksum, retained installed guest code, and had no forbidden Truffle events in measurement/final verification. Allocation is three 256-call samples; JFR follows a separate settling interval. The full 18-input before/after native checks are retained in the adjacent throughput bundle.

`selected-worker-bgv.tar.xz` contains both original worker BGVs: 128,676,150 raw bytes compressed to 3,896,732 bytes. The [archive manifest](selected-worker-bgv-manifest.json) records raw, archive and parsed-phase hashes. The [independent reparse proof](reparse/topology-verification.json) matches all eight original phase topologies. Topology digests cover graph identity, node IDs/classes/block membership, every edge and every block; they deliberately exclude elapsed timings and node properties. Full properties remain in the raw BGVs. All other raw graphs remain at the recorded server paths in [raw-artifacts.json](raw-artifacts.json).

Run `python3 tools/verify.py` without a JVM to check the inventory, byte-for-byte archive extraction, exact inputs/options, warmup and checksum gates, six allocation samples, call-tree facts and reparse proof. To regenerate the proof without executing guest code: `python3 tools/reparse.py --java-home /path/to/pinned-graalvm /new/output/path`. This requires GraalVM 25.3.4.1. The captured tools and serial driver are under `tools/`; original absolute paths are preserved as provenance.
