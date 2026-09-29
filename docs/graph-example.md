# Breadth-first graph traversal

`t/fixtures/core/GraphWorkload.hs` is a pure dependency-graph workload using ordinary
`Data.IntMap.Strict` adjacency lists, a `Data.Sequence` FIFO and a `Data.IntSet`
discovery set. No collection operation is implemented specially by THC.

`breadthFirst` marks vertices when enqueuing them, so repeated edges, cycles and
diamonds do not repeat pending work or overwrite a shorter distance. Missing
adjacency for a reached vertex denotes a sink; an absent starting vertex denotes
an empty search. The result contains every reached vertex and its shortest
unit-edge distance.

The deterministic generator clamps its size to 0–512 vertices, uses mixed-sign
keys, and keeps the final quarter in a disconnected component. Ring edges make
each component connected; forward shortcuts, self-loops and duplicated edges
exercise queue ordering and discovery. This is a correctness workload, not a
random-graph distribution or a graph-library performance claim.

The primary unary `Int# -> Int#` entry, `graphChecksum`, incorporates all reached
keys and distances. `graphReachable` and `graphDistanceTotal` expose the two
aggregate observations. `graphDistanceAt` packs `size * 1024 + vertexIndex` into
one nonnegative input and returns that vertex's distance or -1. The native test
also calls `graphControl`, which selects fixed empty, singleton, path, diamond,
duplicate/self-loop, disconnected, dangling-edge, absent-root, shortcut and
directed-cycle graphs, including individual reached/missing vertex queries.

## Run the example

Use a complete-Core GHC installation. The producer exports the original
containers library and required GHC modules, then audits the reachable closure.
A missing dependency fails preparation rather than being synthesized or pruned.

```sh
# Select the pinned GHC 9.14.1 full-Core installation in GHC and GHC_PKG first.
cabal run exe:thc-fixtures --offline -- graph-bfs
./gradlew graphWorkloadTest
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew graphWorkloadTest
```

After preparing the fixture and running `make runtime`, the ordinary JVM launcher
can execute the unary example directly:

```sh
graph_modules=$(sed 's|^|build/graph-bfs/|' build/graph-bfs/post-modules.txt | paste -sd, -)
THC_BACKEND=bytecode build/install/thc/bin/thc "$graph_modules" graphChecksum 64
```

Its result is `538924180`; diagnostics are written separately to stderr. Set
`THC_BACKEND=ast` for the other backend. The producer requires a complete-Core
GHC installation explicitly and does not fall back to thin or reconstructed
definitions when that prerequisite is absent.
