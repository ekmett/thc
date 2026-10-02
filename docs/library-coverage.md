# Library examples

THC executes ordinary Haskell library bodies. Collection and formatting
operations are not replaced by JVM algorithms. A program needs the complete
reachable Core for its selected package versions, including error paths.
Use [complete installed Core](ghc-core.md) and the [driver](driver.md) for your
project; a stock interface may omit a private worker that a public function needs.

The repository includes focused examples of:

| Area | Examples and runtime contract |
| --- | --- |
| Containers | `Data.IntMap.Strict`, `Data.IntSet`, `Data.Sequence`; [breadth-first graph traversal](graph-example.md) combines all three |
| Lazy lists and functions | [Core corpus](coverage.md): sharing, streams, captures and partial application |
| Formatting | Public `Show Int`, `Show Word` and `Show [Int]` through original GHC Show/CString bodies |
| Mutable references | [STRef/MutVar](mutvars.md), [atomic updates](boxed-cas.md), [MVars](managed-mvars.md), [STM](stm.md) |
| Boxed arrays | [Lazy storage](core-evidence.md#lifted-boxed-array-storage), [shallow slices](array-slices.md), [small arrays](small-arrays.md) |
| ByteString | [ShortByteString storage](bytearrays.md), [mutable byte operations](mutable-bytearray-ops.md) |
| Integer and Natural | [Literal representation](bignat-literals.md) |

The library suite distinguishes accepted consumers from explicit rejection
controls. Those controls describe their prepared bundle, not a permanent
limitation of every version or every caller of Set, Map or Sequence. Consult the
actual audit for a missing binding or unsupported path; do not remove cold error
branches to obtain acceptance.

## Run the examples

```sh
bin/try-libraries.sh
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true bin/try-libraries.sh
```

The script prepares native GHC results and exports, then launches fresh checks
for both backends. Reports under `build/libraries/` include per-bundle strict
audits, `oracle.tsv`, `cases.json`, and runtime logs. Stale source or artifact
fingerprints require preparation again. Compiled checks require installed guest
execution as well as correct values.

The Map workload has a separate diagnostic runner:

```sh
THC_DIAGNOSTIC_UNSUPPORTED=true bin/try-map.sh
```

Diagnostic execution installs traps at unsupported sites and can inspect a path
through a rejected bundle. It does not establish complete program support. Use
[graph inspection](graph-inspection.md) to inspect its generated code separately
from timing.
