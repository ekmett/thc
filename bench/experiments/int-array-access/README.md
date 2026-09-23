# Primitive Int access to managed byte arrays

Four production graph controls execute genuine post-Tidy Core from
`IntArrayAudit.hs`: `orderedInts` and `aliasIntBytes` on AST and bytecode. Each
checks all 393 native rows twice after explicit compilation, requiring the
warmed active guest target to remain valid and unchanged after every row.
This is correctness and compiler-graph evidence, not a timing benchmark.

The captured runtime is `af913bea21f41962a45a6f1835777df650e6d6a6`, the reviewed
Int-array implementation integrated with ShortByteString copying. It uses the
pinned GraalVM 25.3.4.1 on AArch64, normal inlining, and
`thc.handoffSlabs=false`. The repository wrapper independently reproduced the
four initial inspection captures with source/tool/JAR/native hashes pinned
before execution.

| Post-Tidy entry | Backend | High-tier nodes | Raw Long loads / stores | Committed byte arrays |
| --- | --- | ---: | ---: | --- |
| `orderedInts` | AST | 200 | 4 / 5 | 24 bytes, 8 bytes |
| `orderedInts` | Bytecode | 200 | 4 / 5 | 24 bytes, 8 bytes |
| `aliasIntBytes` | AST | 186 | 2 / 2 | 16 bytes |
| `aliasIntBytes` | Bytecode | 196 | 2 / 2 | 16 bytes |

At `Before phase HighTierLowering`, every raw access has
`accessKind: JavaKind.Long` and `locationIdentity: Array: byte`. Its object is
the committed backing array, and its load/store value is a primitive `i64`.
The alias control's two byte stores at offsets 7 and 8 use that same array.
There is no separate `long[]`, boxed-element array, or wrapper allocation.
Each graph contains no `Invoke` and exactly one allocating `java.lang.Long`
box, directly returned as the scalar host result. Virtual frames and other
deoptimization descriptions do not represent committed guest allocations.

**The guest byte arrays remain allocated.** Final scheduled code retains two
array-allocation slow paths for `orderedInts`, one for `aliasIntBytes`, and one
object-allocation slow path for each host-result box. These counts are code
paths, not measurements of how often the allocation slow path runs. The graphs
demonstrate primitive accesses into shared byte storage, not allocation
elimination, a residual-call ABI, or throughput improvement.

The graph harness disables instrumentation, so its gates establish native-value
agreement and target validity/identity, not compiled-entry counter increments.
The separate 15 instrumented ByteArray/Int-array tests passed in both default
and handoff modes and enforce per-row compiled guest entry. This graph matrix
does not claim residual-call or handoff graph coverage.

## Reproduce

Use GHC 9.14.1 and the pinned GraalVM installation (`JAVA_HOME`). From the
repository root, first prepare fresh native/Core evidence:

```sh
python3 scripts/prepare-int-arrays.py
python3 bench/experiments/int-array-access/run.py \
  /private/tmp/thc-int-array-access-new \
  --capture bench/experiments/int-array-access/captured
```

The output directory must be new. The runner verifies all fixture source and
artifact hashes, both strict audit stages, the exact entry/input sets and row
counts, and committed runtime/build source. It runs `installDist`, records the
installed JAR, tooling and JDK release hashes before launch, and checks them
again afterward. Compilation is explicit: background and multi-tier compilation
are disabled and automatic compilation has a high threshold. Compiler graph
and loop limits are unchanged. There are no retries or settling calls after
compilation.

Only the inspected high-tier snapshot is expanded from each raw BGV. The
collector checks graph target/case identity, primitive memory types/counts,
shared alias storage, the exact committed array sizes, the host-result box,
absence of invokes, and final allocation-path counts. Capture records pin raw
BGV/CFG, parsed snapshot and run-log hashes. Recollection fails if launch inputs
or captured evidence have changed:

```sh
python3 bench/experiments/int-array-access/run.py \
  /private/tmp/thc-int-array-access-new --collect-only \
  --capture bench/experiments/int-array-access/captured
```

Only compact counts/hashes and the selected native rows are checked in. Raw
graphs, CFGs, expanded snapshots, generated classes and build logs stay in the
external output directory. No measurements of elapsed time, allocated bytes,
or peak memory are reported.

The captured four-control run and recollection both passed. Independent
collector controls rejected a substituted case record and a changed tooling
hash. Total high-tier node counts are observations of this capture, not an
assertion that every JVM run chooses identical auxiliary control nodes.
