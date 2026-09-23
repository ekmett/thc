# Selective IntThunk integration assessment

This is a source-level assessment of the isolated snapshot `87e6c6d79d74c22ea95c371c4942d04e62e03e2a` plus the two experiment files. It proposes no main-checkout changes. Benchmark results and their uncertainty belong in the experiment report; source inspection alone cannot establish a throughput or cycle-count advantage.

## Smallest useful adoption

Keep ordinary `Thunk` as the default. Begin with an opt-in lowering for a nonrecursive delayed computation whose result is **exactly the wired lifted `Int`/`I#` constructor**, whose fresh-result behavior is known, and whose important consumers are specialized `I#` elimination sites. A promising first real-Core fixture is a delayed `Int` retained in an escaped closure and consumed repeatedly: the original cell can survive through immutable captures even after evaluation, so storing its primitive result can remove a separate fresh result object.

Do not change the object shape of ordinary thunks or attach a layout to each specialized cell. Keep the existing class-owned `I#` `DataLayout`; the specialized node/root can own the constant expected layout. Reuse the candidate's entry slot for failures and existing shared/cached results. Preserve the ordinary guest-failure memoization, blackhole and retry protocol; a private producer that has staged a result must restore both original suspension references on retry.

The first production trial need not start with a new direct ABI. The boxed-producer/copy experiment uses existing producers, and Graal may remove its temporary fresh `I#`; the screening run observed this, while the final confirmation source retained the temporary box. A production version must distinguish a fresh result from an existing shared/canonical result and retain the latter through `REF_RESULT`. The direct destination ABI is justified only by additional evidence that it improves surviving allocation or execution enough to pay for another entry convention, tail-call handling and backend maintenance. The harness deliberately excludes tail-calling private producers; it is not a complete replacement for ordinary `Force`.

## Evidence still missing

`CoreRepresentation` currently carries `kind`, evaluatedness and primitive-register representations. `DATA` plus one boxed register does not mean `Int`, and `LONG` describes several primitive types rather than lifted `Int`. The exporter similarly classifies boxed data without exporting an exact result tycon/constructor identity in `rep`/`resultRep` metadata. Pretty-printed type text is insufficient evidence.

For a general selective lowering, export and validate exact wired logical identity from GHC, separately from physical representation and evaluatedness. Require the pinned constructor identity `ghc-internal:GHC.Internal.Types.I#`, boxed constructor form, one `IntRep` field, and appropriate lifted result type. A narrower first implementation can prove exact constructor results from explicit constructor expressions and case alternatives already carrying stable constructor IDs; all reachable return paths must satisfy the proof. Unknown calls, joins, casts/newtypes and mixed return paths need propagated proof or the ordinary fallback. Exact `Int` type still does not prove that a returned `I#` is fresh, unaliased, uncached or likely to be demanded.

## Force, cases, publication and materialization

The candidate cannot simply replace `Thunk` at allocation sites. General `Force` only recognizes ordinary `Thunk`; it would otherwise return an unevaluated candidate unchanged. `Evaluate.executeDataValue`, `FunctionBody` with a data result, `DataCase`, field restoration and the generated runtime type system require real `DataValue` carriers. `DataLayout.matches` validates the actual constructor carrier. None currently recognizes a finalized primitive candidate as `I#`.

Use a specialized logical-Int force/elimination path for selected sites. At a generic **demand** boundary, materialize an actual `I#` once and memoize that exact object in `REF_RESULT`, or retain an existing shared/cached box directly. Materialization must not force a lazy value merely because it is passed to an unknown function or stored in a field. The smallest rollout therefore excludes unknown lazy escapes unless every eventual generic demand can route through the adapter. Returning a candidate as general `DataValue` is not valid under the current carrier contracts.

Known `I#` cases can bind the raw primitive without allocating a box when their case binder is unused or remains within specialized consumers. If the whole case binder escapes to a generic data consumer, or a later node relies on evaluated-data carrier evidence and skips forcing, materialize or propagate a distinct carrier proof. Existing `LocalRead.writeForced`/`updateForcedCell` and bytecode `ForceLocal` publication recognize ordinary `Thunk`; candidate publication and recursive aliases must be handled explicitly. Audit both AST and bytecode backends, including typed application/return paths, before broadening eligibility.

`CapturedFrame` and constructor properties are final StaticShape fields. A capture already containing a candidate continues to reference that cell after force; it cannot be rewritten into a primitive or a smaller box. Current evaluated-data captures and strict data fields can be declared `DataValue`, so they cannot hold the candidate. Keep lazy specialized captures as an appropriate reference carrier, or materialize on an already-demanded boundary. Capture-time conversion must not introduce demand. Do not globally weaken all precise `DataValue` fields to `Object` to accommodate this optimization.

## Why published reads can lose

The `published-read` setup moves allocation and first force outside measurement and replaces root slots with WHNF results. Ordinary roots then hold compact `I#` `DataValue` objects directly; their reader can use constructor-class/layout knowledge and load the primitive field. The candidate roots still hold larger update cells with an explicit state and a payload. The experimental consumer checks that state to distinguish inline primitive data from `REF_RESULT`. That extra carrier width and state-dependent path can affect scanning/locality and generated code even though construction is untimed. This is a plausible mechanism, not a claim about particular generated branches or processor cycles without compilation/assembly evidence. A materialize-and-publish strategy could recover the compact read carrier, but would also reintroduce the box and must be measured separately.

Keep ordinary thunks for unknown or polymorphic results, low-demand populations, shared/cached result forwarding, already-evaluated inputs, paths quickly publishing only the compact box, and cases where precise carriers or generic escapes would require widespread materialization. Strict/CBV sites that already avoid allocating any thunk should continue to do so. Eligibility should combine correctness proofs with measured lifetime/demand behavior; “result type is Int” alone is an inadequate adoption rule.

## Source anchors in the isolated snapshot

- `compiler/Thc/Plugin.hs:137` (`typeRep`) and `:247` (constructor identity/field metadata).
- `src/main/kotlin/thc/runtime/CoreRepresentations.kt:7` and `:17` (representation versus exact carrier proof).
- `src/main/kotlin/thc/runtime/Program.kt:43`, `:163`, `:254`, `:477`, `:559`, `:800`, `:819` (thunk, force, typed data consumers, captures and delayed lowering).
- `src/main/kotlin/thc/runtime/Frames.kt:119` and `:188` (capture shapes and final properties).
- `src/main/kotlin/thc/runtime/DataValues.kt:18`, `:105`, `:138` (wired identity, carrier ownership and primitive construction/cache).
- `src/main/java/thc/runtime/BytecodeRoot.java:180`, `:218`, `:333`, `:354` and `RuntimeTypes.java:16` (publication and typed data contracts).
- `src/main/kotlin/thc/runtime/ExperimentalIntThunk.kt` and `IntThunkExperiment.kt` (isolated candidate and published-read setup; no production integration).
