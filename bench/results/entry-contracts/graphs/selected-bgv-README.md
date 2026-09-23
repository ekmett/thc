# Selected raw bytecode graphs

The selection contains six complete BGVs: original-runtime baseline and typed-frames-v4 candidate versions of lookup, fold and the main worker. It is the cumulative runtime comparison described in the parent README, not an isolated entry-contract change. The raw files total 186,888,788 bytes; a single-thread XZ archive preserves their original bytes and source positions.

| Role | Baseline compilation | Candidate compilation |
| --- | ---: | ---: |
| Lookup, `Data.Map.Internal:649` | 3224 | 3219 |
| Fold, `Data.Map.Internal:3426` | 3269 | 3236 |
| Worker, `lambda ww` | 3281 (root 27) | 3270 (root 23) |

Lookup and fold match by exact normalized source plus name. The worker has no source location in the capture; its recorded unique name and within-run root IDs provide the correspondence. Compilation/root numbers alone are not cross-run identities.

`selected-bgv-plan.json` records the selection using the existing immutable capture hashes. Preparation only reads metadata and file sizes. Until `selected-bgv-manifest.json` exists, the archive is planned rather than published. With the CPU lane available, publication performs the raw hash checks, compression and original-topology hashing:

```sh
python3 bench/results/entry-contracts/graphs/selected-bgv.py publish
```

This refuses to overwrite an existing publication. It checks the original compact inventory, preserves it as `compact-manifest-original.json`, and updates the publication inventory and raw-artifact status. No guest workload is rerun. The compressed archive has normalized tar metadata and XZ preset 3; its actual compressed hash and size are recorded after completion.

To open the graphs in Ideal Graph Visualizer, extract the archive or use the hash-validating extractor:

```sh
python3 bench/results/entry-contracts/graphs/selected-bgv.py extract /tmp/thc-selected-raw
```

The initial verification run stopped at its authorized 2% battery floor after five successful graph parses. The candidate worker and final topology comparison remain pending; [status and logs](reparse-status.json) distinguish this from a parser failure. The archive and all six original BGV hashes passed verification.

To independently reproduce the parser output with GraalVM 25.3.4.1:

```sh
export JAVA_HOME=/path/to/graalvm-jdk-25.3.4.1
bash bench/results/entry-contracts/graphs/reparse.sh /tmp/thc-selected-reparse
```

Reparsing uses the archived `GraphInspect.java`, not whichever parser happens to be current in the checkout. JVMs run serially with a 4 GB heap limit (`THC_GRAPH_HEAP` overrides it). The script verifies the archive and six uncompressed hashes, then checks all 18 selected phases against topology digests from the original parsed captures. It writes `topology-verification.json` in the new output directory.

The digest includes every node ID, class and block assignment, every labeled/indexed edge, and every block's ordered nodes and successors. Source stacks and compiler timing/address annotations are excluded from the topology comparison; the raw BGV retains them. This verifies that reparsing reproduces each original graph. It does not assert that baseline and candidate have equal graphs, reproduce a compilation decision in a new run, or establish a throughput result. The original source/runtime/corpus/capture manifests remain authoritative for what produced these graphs.
