# Breadth-first graph traversal

`examples/THC/GraphWorkload.hs` is a pure dependency-graph workload using ordinary
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

## Evidence and current status

The Haskell `thc-fixtures graph-bfs` producer uses the existing library oracle
and compiler closure export. Native and Core builds compile the same unmodified
containers-0.8 archive, pinned by SHA256. Containers are exported after Tidy,
where their private worker identities agree with their actual interfaces.
Original GHC source/interface boot exports fill the same bounded exception
closure as the existing library proofs. The selected GHC installation must also
carry complete Core: the existing interface reader supplies the whole original
`GHC.Internal.List` and `GHC.Internal.Classes` modules. These replace the
overlapping thin interface fragment, not individual library functions. No
interface bodies, missing bindings or cold paths are synthesized or removed.

The producer records the complete source/module inventory, native binary and
455 native observations, command statuses, strict entry audits, exact installed
interface inventory and copied original interface bytes. Debug source-note
expansion is disabled for the large containers source export; executable Core
and the complete source inventory remain intact.
If a strict audit fails, it preserves the precise report and exits unsuccessfully;
that is a dependency frontier, not a diagnostic execution pass.

The explicit `graphWorkloadTest` suite reuses the optional proof-test source set,
separate from ordinary fixture-free runtime tests. Its large unchanged source
exports are not silently loaded by every scalar test. `GraphWorkloadTest`
compares native observations with a Kotlin synchronous
edge-relaxation model, independently of the Haskell queue/visited algorithm.
Hand-written small-graph distances anchor that model. Missing, reordered and
altered oracle rows reject. THC checks use strict loading, both backends, and
both inlining settings for the main checksum and control roots. Compiled checks
require installed guest activity on the first call after installation without
settling calls or retries. Recursive library paths have input-dependent counts;
no exact one-entry or allocation-free claim is made.

The initial thin-interface experiment failed honestly: post-Tidy checksum,
reachability and distance totals lacked `GHC.Internal.List.reverse1`; individual
distance/control queries also lacked `GHC.Internal.List.lookup`. There were no
capability issues. The pre-Tidy experiment additionally retained private
containers worker-name mismatches. These are not supported boundaries of this
example. The complete-original-module route passed all five strict entry audits
(6,344 supplied bindings, 20/20/20/24/86 reachable bindings, no missing globals or
capability issues). Native GHC agreed with the independent model on all 455 rows.
The five JVM tests passed in both default and dense handoff modes, including all
455 observations on both backends and the first-installed-call checks with and
without inlining. This establishes this bounded graph workload, not arbitrary
containers or whole-boot-library coverage.

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
