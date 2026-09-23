# Int-result thunk experiment

The tested **32-byte specialized thunk should not replace ordinary Int thunks by default**. It saves allocation and live guest-object space when fresh results are forced and the original thunk stays reachable. This bounded experiment did not show a throughput win, and caller publication strongly favors the existing compact `I#` representation. A possible 24-byte representation is worth a separate test; it was not implemented or measured here.

The experiment uses an isolated source snapshot of `87e6c6d79d74c22ea95c371c4942d04e62e03e2a`; the main checkout was not changed. These are actual THC/Truffle runtime microexperiments, not integration into GHC Core lowering or an end-to-end application speedup. The producer is synthetic and reads are sequential scans; random-access or mixed-polymorphism workloads were not tested.

## What was compared

- **Ordinary:** actual THC `Thunk`, `Force`, immutable `CapturedFrame`, and actual generated `I#` `DataLayout`.
- **Inline copy:** a cell with two references, an explicit int state, and a primitive long; the ordinary boxed producer returns `I#`, whose payload is copied into the cell. A diagnostic for fresh results only.
- **Inline direct:** the same cell, with a private producer ABI that writes a primitive result into its destination. Existing shared/cached boxes are retained through a reference-result state, preserving identity.

The private mixed Int consumer accepts finalized specialized cells and explicit `I#` values. Generic THC `DataValue` consumers were not changed. The producer roots are non-tail-calling; the prototype is not a replacement for the complete runtime force protocol.

## Measured sizes and allocation

On the pinned compact-header JVM, instrumentation measured ordinary thunk **24 B**, specialized thunk **32 B**, actual `I#` **16 B**, and the experiment's common capture **24 B**. Compressed references were enabled and object alignment was 8 B. Header-off sensitivity results are retained separately.

Allocated bytes per newly created cell, including its capture and result where applicable:

| Result regime | Ordinary | Inline direct | Inline copy |
| --- | ---: | ---: | ---: |
| Never forced | 48 B | 56 B | — |
| One quarter forced | 52 B | 56 B | — |
| Half forced | 56 B | 56 B | 64 B |
| All forced, fresh results | 64 B | 56 B | 72 B in all confirmation forks |
| Existing shared/cached result | 48 B | 56 B | not applicable |

Raw samples include an additional **56 B per outer batch call**, matching both 1,024- and 65,536-cell allocation controls. The table gives the per-cell allocation slope. Allocation counters are for the invoking guest thread; they are not sampled allocation profiles or whole-process allocation totals.

The original screening source produced **56 B** for fully forced inline-copy, whereas the final source with the additional half-forced scenario produced **72 B** in all three confirmation forks. Both source versions, commands, hashes and raw logs are preserved. These are different source/configuration runs, not evidence of random behavior within identical source. A temporary-box-elimination claim therefore cannot be generalized from the screen.

Live graph bytes per cell, excluding the common root array and shared runtime metadata:

| Reachability after setup | Ordinary | Specialized |
| --- | ---: | ---: |
| Never forced, capture retained | 48 B | 56 B |
| One quarter forced | 46 B | 50 B |
| Half forced | 44 B | 44 B |
| Fully forced, fresh result, thunk retained | 40 B | 32 B |
| Fully forced, same shared result | 24 B + one shared 16 B box per population | 32 B + same shared box |
| Caller publishes only the evaluated value | 16 B | 32 B |

These are identity-deduplicated reachable guest graphs, not heap-dominator retained-size measurements. Captures are cleared upon successful force. Cached boxes can remain independently rooted in runtime metadata, so they do not represent a per-cell reclaimable saving.

For the tested shape and independently materialized fresh results, the crossover is **more than 50% forced**: cell/result storage is `24 + 16p` versus `32`, with equal remaining capture costs on both sides. This is a property of the measured 32-byte design, not an intrinsic limit on specialization.

## Confirmation timing

Oracle GraalVM 25.3.4.1+1.1 / Java 25.0.4.1, macOS arm64, 512 MiB fixed heap, compact headers, class-owned layouts, boxed-value cache off. The recorded runs used a local slot coordinated to avoid competing builds and benchmarks. Each combination ran in **three fresh JVMs**, with five 2-second windows of compiled warmup and five 2-second measured windows. Variant/case order rotated by fork. No sizing agent or graph dumping ran during these measurements.

Fresh/shared batches contain 65,536 cells; read-only arrays contain 262,144 entries. Reads use one scan per guest invocation. Read-only setup allocates and forces before timing. `reread` preserves thunk identities; `published-read` replaces each array slot with the evaluated result, leaving ordinary `I#` values versus finalized specialized cells.

Times are nanoseconds per created-and-forced cell or per read. Ratio above 1 means slower than ordinary. Each reported median summarizes the three fork medians; windows are not treated as independent replicates.

| Case | Mode | Median ns/op | Fork-median range | Ratio to ordinary |
| --- | --- | ---: | ---: | ---: |
| force-all | inline-copy | 5.734 | 5.702–5.848 | 1.028× |
| force-all | inline-direct | 6.073 | 6.022–6.196 | 1.089× |
| force-all | ordinary | 5.576 | 5.572–5.830 | 1.000× |
| published-read | inline-direct | 1.384 | 1.335–1.406 | 1.928× |
| published-read | ordinary | 0.718 | 0.694–0.775 | 1.000× |
| reread | inline-direct | 1.399 | 1.363–1.411 | 1.057× |
| reread | ordinary | 1.323 | 1.302–1.417 | 1.000× |
| shared | inline-direct | 5.406 | 5.392–5.676 | 1.084× |
| shared | ordinary | 4.986 | 4.968–5.007 | 1.000× |

The direct variant was about **9% slower** for fresh construction/force, about **8% slower** for shared results, and about **1.93× slower** after caller publication. Retained-thunk reread ranges overlap; no reliable benefit was established in the larger working set. The smaller initial screen had suggested a benefit, which did not carry over to this confirmation matrix.

All **27 processes / 135 windows** passed result checks, exact compiled-entry-count checks and installed-last-tier checks. The merged logs show no Truffle compilation event between warmup completion and the last sample. All windows were kept, including a noisy published-ordinary fork with a 1.89 max/min window ratio. This audit does not exclude every host-JIT, GC, scheduling or thermal effect; it does not justify narrow confidence intervals.

## Compiled graph evidence

Separate diagnostic executions reproduced the measured byte counts. The ordinary caller contains a materialized `I#`; the final inline-copy caller also contains one; the direct caller contains none. Half-forced inline-copy likewise keeps its temporary `I#`. The direct destination-call packet is eliminated.

Rebuilding the preserved screening source reproduced its 56 B/cell result: its inlined caller has no committed `I#` or after-mid-tier `I#` allocation, while the final-source copy caller does. Both inline the producer and have no surviving per-cell `Object[]` allocation. Both standalone boxed producer graphs still allocate their result. This establishes caller-specific elimination in the screening shape; it does not establish why that compiler decision changed.

Read graphs retain the ordinary thunk→value→primitive dependency when thunk handles remain. After publication the ordinary root directly supplies `I#`; the candidate remains a 32-byte cell and follows its state-dependent primitive/reference-result path. That explains the representation and code-path difference, without proving a particular hardware bottleneck.

These are separate diagnostic compilations, not the exact compiled artifacts from the timing processes. Graph identifiers and source paths are recorded in `intthunk-graph-findings.md`; `graph-manifest.json` identifies raw BGV files retained locally; `graphs/` preserves diagnostic logs/commands and packet summaries. No diagnostic timings are included in the reported performance results.

## Correctness and applicability

Fourteen semantic test groups passed with the real box cache off, and fourteen with it on: full signed 64-bit payloads, laziness, once-only evaluation and sharing, terminal release of suspension references, exact guest/runtime failure memoization, host interruption/retry after staging a result, recursive blackholes, shared/cached identity and mixed finalized-value reads. These edge checks execute through Truffle but were not individually forced to compile; the numerical benchmark roots were explicitly compiled and checked.

The existing `sharedCapturedThunk` in `examples/THC/FunctionCoverage.hs` is a plausible future Core fixture: an escaping closure captures a lazy Int and reads it twice. Current exported DATA/evaluated/primitive-representation metadata does not prove exact lifted Int identity or fresh-result behavior. Pretty-printed `Int` is insufficient as a compiler proof.

## Recommendation

Keep the ordinary representation as the default. Do not integrate this 32-byte experiment on the strength of the allocation saving alone. If pursuing the idea, first test the documented **untested 24-byte reference-tagged state machine**, then apply exact constructor/type and freshness proofs to a real escaping-capture fixture. Even a 24-byte carrier would remain larger than a published 16-byte `I#`.

The reference-tagged design keeps BUSY set throughout producer execution, stages output in the second reference, publishes terminal state only after validation, and retains local target/environment copies for retry. Its object size, partial-evaluation behavior, hot guards, generic materialization and performance remain unmeasured. See `intthunk-tagged-design.md` and `intthunk-integration.md` for the specific obligations.

## Reproduction and evidence

See [REPRODUCE.md](REPRODUCE.md) for portable commands and measurement boundaries. The final source lives in `src/main/kotlin/thc/runtime/`; `screen-version/` preserves the earlier source.

Raw semantic, layout, screen, threshold and confirmation logs include exact commands and per-run hash manifests. `confirmation-summary.json` retains every window and audit result. `layout-summary.json` records actual object sizes and identity counts. `graphs/` contains the diagnostic logs and packet summaries; `graph-manifest.json` hashes raw BGV dumps retained in the originating local experiment artifact. Large graph dumps are not needed to rerun the experiment.
