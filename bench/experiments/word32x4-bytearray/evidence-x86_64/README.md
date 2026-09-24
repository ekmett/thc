# Verified Word32X4 managed-memory checkpoint

Runtime and final test source: `340560523065c2091cd029695180c3341a408cc2`.
Host: Linux x86-64, Intel i9-12900K; pinned GHC 9.14.1 and Graal distribution
25.3.4.1+1.1 / JDK25.0.4.1+1-LTS-jvmci-25.3-b22. No production policy,
compiler graph-budget increase, retry or postcompile settling was introduced.

## Results and attribution

| Record | Source | Result |
| --- | --- | --- |
| Direct raw-lane regression | `a5bbeb1` | Two preserved expected failures: signed/unsigned indexing accepted4.0 |
| Temporary per-memory guard | `1702245` | Targeted2, focused14 and full515 default +515 dense pass |
| Shared canonical parser | `3405605` | Targeted3, focused14 and full515 default +515 dense pass |
| Actual unsigned graph campaign | `3405605` | All16 captures pass on first check; no reader correction or guest replay |

Both full checkpoints contain106 suites per mode, with matching method
identities and zero failures, errors or skips. The final source imports parent
8bb834b's common Int/Long vector-count guard and removes the temporary direct
memory guard. Both-family index/write negatives remain. Unchanged ordinary
automatic triggers apply to the native correctness corpus; only the separate
cold-transition correctness test suppresses automatic compilation. The graph
harness separately isolates one explicitly compiled guest target.

The genuine native/model corpus has9,666 byte-identical rows. Nineteen declared
input-source and seven artifact hashes verify. This native-source inventory is
not the complete runtime inventory: CoreVectors.kt is covered by the graph's
separate75-source snapshot. Eleven installed JARs and four JDK files are also
frozen and rehashed. Current native provenance SHA-256:
`cb52d279dde6e284e2542df10ee5eac58ef7b551120b235f12cf1f7350d1ffd5`.

Each complete mode checks77,328 compiled native/model calls and229,536 exact
guest entries, across pre/post Core, AST/bytecode and inlining on/off. Each row
checks active target identity/last-tier validity, exact counter deltas and empty
handoff pools. The96 cold transitions cover six operations, both backends and
eight invalid size/index classes, requiring invalidation without effects and
successful recovery without recompilation. Native undefined invalid offsets
are never used as a correctness oracle.

The16 selected pre/post × AST/bytecode × four-root graphs check1,152
installed-target invocations, each using fresh caller arrays and checking all64
bytes. Stores return the identical array. There are eight packed loads with32
exact unsigned32-to-64 extensions and eight packed stores with32 low-32-bit
lane inputs. All16 allocated LIR records contain a physical XMM `VMOVDQU32`
caller-array access. No live private vector/carrier/payload allocation or field
traffic survives these selected compiled paths. Precisely checked interpreter,
frame-tag and bloom-header deoptimization metadata is not a live payload.

Graph evidence SHA-256:
`8f0b6b74be7b84c89410223c9efa1af487fdc7578fdaa6d438b10bb9a3a4a8c9`.
Graph instrumentation is disabled: these captures establish installed validity,
not per-call compiled-entry counters. The instrumented correctness suites
provide the separate counter gates. Public Long result boxes and caller arrays
are intentional. No throughput, no-spill, universally allocation-free execution,
general vector ABI, foreign/Addr memory or cross-architecture claim is made.

## Reading the compact package

`evidence.json` describes all selected graph/LIR records. Gzip files decompress
to the exact hash-covered graph, Core, native/model and LIR bytes named by those
records. Raw BGV/CFG files remain in the original local capture directory;
their hashes are recorded but those larger files are not duplicated here.
`validation/` retains final commands/statuses/logs and exact JUnit XML archives.
`validation/first/` retains the earlier scoped-guard run separately, and
`validation/direct-proof-red/` preserves the two original expected failures.
The15-test parent8bb verification and earlier12ec/0c10 records have separate
immutable coordination handoffs; they are not relabeled as this unsigned run.

`native/` retains current strict audits, source/artifact provenance and keyed
TSVs, plus preparation command/status histories. Older per-run JSON snapshots
refer to shared artifact paths; they are not independent copies of every
earlier generated artifact. Original full input copies remain alongside the
first validation in the local build directory. Current inputs were regenerated
after source changes, not relabeled in place.

Python checks pass normally and under `-O`, with no skips:65 auditor,23 generic
vector,14 signed-memory and15 unsigned-memory proof,10 signed and11 unsigned
byte-model,9 byte-array,4 coverage and35 graph-reader tests. The latter include
actual signed-load graphs rejected for the wrong widening; synthetic parser
checks are not unsigned compiler evidence.

`package-verification.json` records archive/original equivalence and totals.
`SHA256SUMS` seals every other package file; its hashes cover compressed bytes,
while individual graph/native provenance records cover uncompressed originals.
Neither the manifest nor this package is a cryptographic signature.
