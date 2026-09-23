# Untested 24-byte tagged-reference alternative

This is an analysis-only alternative to the measured 32-byte `ExperimentalIntThunk`. It has not been implemented, compiled, sized, or benchmarked. The bounded experiment and its results remain unchanged.

Remove the explicit `Int` state field. Keep two reference fields, `entryOrFailure: Any?` and `environmentOrStagedResult: Any?`, plus the full-width `Long` payload. Use ordinary references as tags; this needs no unsafe pointer tagging or JVM/debugger integration. With an 8-byte compact header, two compressed 4-byte references and one 8-byte payload, a 24-byte object is plausible. Actual field layout/alignment must be verified on the chosen JVM.

## State transitions

`BUSY` and `MISSING` are distinct private singleton objects. Neither is a guest value. `null` is an available primitive-result tag because every real pending entry is a non-null `RootCallTarget`.

| Phase / transition | `entryOrFailure` | `environmentOrStagedResult` | Payload / behavior |
|---|---|---|---|
| Pending / NEW | Producer `RootCallTarget` | Original `CapturedFrame?` | Payload ignored; no evaluation yet |
| Begin force → BUSY | `BUSY` | `MISSING` | Forcing stack first saves original target and environment |
| BUSY, stage primitive | **Still `BUSY`** | `null` | Producer writes the complete 64-bit result |
| BUSY, stage existing reference | **Still `BUSY`** | Exact existing `DataValue` | Producer preserves the box's identity; payload ignored |
| BUSY → primitive success | `null`, published last | `null` | Validate producer's Unit return and completed staging first |
| BUSY → reference success | Exact staged `DataValue`, published last | `null` | Validate expected I# layout before publication |
| BUSY → memoized guest/runtime failure | Same exception object, published last | `null` | Clear payload; subsequent forces throw the identical object |
| BUSY → retry after other throwable | Original target, restored last | Original environment | Clear payload; restore the complete original suspension |
| Any reentry while BUSY | Remains `BUSY` | Any staging value | Raise blackhole; staging cannot make the cell appear complete |

The forcing node is the only terminal-state publisher. A missing staged result, wrong constructor, or wrong private return ABI becomes a memoized `RuntimeFault`. On ordinary guest/runtime failure, discard any partially staged primitive or box. On interruption, stack locals provide the target and original environment even if the second field was already repurposed. Current single-guest-thread semantics make these transitions viable; reference-store ordering alone is not a concurrent synchronization protocol.

Success/failure clears the cell's suspension references. The saved forcing-stack locals live only until that force returns or throws; independent call-site code caches have the same ownership considerations as ordinary `Force`. Failure preserves exception identity. Reference success preserves the exact shared/cached I# object. Primitive success preserves every Long bit pattern, including both extrema; no payload value is reserved as a marker.

## WHNF and compiler constraints

Only terminal success represents a logical Int WHNF. `BUSY` remains nonterminal even after its payload has been written, preserving blackhole behavior after staging. Primitive success means this specialized cell contains the completed Int value; it does **not** make the object a `DataValue` accepted by today's typed data consumers. The same specialized elimination and demand-time materialization requirements described in `intthunk-integration.md` remain. Reference success returns the existing I# through the generic adapter.

Reference discrimination replaces an integer state switch with a null check, singleton identity test and class tests. A hot primitive-read path could become a simple null guard plus primitive load, but target discovery now depends on proving the mixed reference is a `RootCallTarget`; the broadened second field needs a `CapturedFrame?` cast on the pending path. Partial evaluation, target-cache specialization, type profiles, guards and deoptimization may improve or worsen. The current candidate already reuses an `Any?` entry slot, but removing its separate state changes those proofs and paths. No execution-cost conclusion follows from estimated size alone.

## Effect on the recommendation

The measured 50% forced-fraction allocation crossover belongs to the **32-byte implementation**: versus a 24-byte ordinary cell and a 16-byte fresh I#, its per-created-cell difference is `8 - 16 × forcedFraction`, ignoring common captures. A verified 24-byte candidate would instead have zero initial cell-size penalty and could save the fresh result box whenever forced. It would also avoid the measured extra cell-size cost for shared/cached results, while retaining those results by reference.

This does not eliminate all tradeoffs. A published ordinary I# occupies 16 bytes in the measured layout; a hypothetical 24-byte primitive cell is still 8 bytes larger and has a different discriminator/consumer path. Materializing and publishing a compact I# changes those costs again. Report the existing matrix as evidence about its measured representation, and keep this alternative as a separate unmeasured design candidate rather than attributing its hypothetical footprint or speed to the tested code.
