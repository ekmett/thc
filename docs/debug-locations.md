# Source locations through optimized Core

THC retains GHC source attribution in both runtime backends. Source and boot exports enable `-g` and structured source notes by default; `THC_SOURCE_NOTES=false` opts out. AST expressions and roots retain immutable source sections; bytecode emission uses the DSL's source-region metadata. There are no executable tick wrappers, logging calls, or debugger events on the normal path.

The exporter records source files with their contents, span identities, original GHC coordinates, and exact UTF-16 character ranges where the contents resolve those coordinates. Expression metadata retains the innermost source and the full outer-to-inner note chain; binder spans provide root fallbacks. Module merging checks consistency when the same source identity occurs more than once. The schema is documented in [the exporter README](../compiler/README.md#optional-source-attribution).

GHC columns begin at one, expand tabs to eight-column stops, count Unicode code points, and end exclusively. Truffle character ranges count UTF-16 units. `SourceNotes.hs` deliberately includes a supplementary Unicode character, a tab, and a `LINE` pragma naming a missing source file. An independent exporter checker verifies the character ranges; missing or remapped content retains original coordinates without invented offsets. The runtime uses a separate content-free `Source` when no exact character range is available, so GHC tab columns are never mistaken for Java character columns. If an exclusive range ends at column one of a later unavailable line, its primary section identifies the known start point and its provenance still retains the full original range.

`scripts/prepare-tests.sh` always generates genuine source-enabled `SourceNotes` and `RepresentationAudit` fixtures under `build/source-core`, even when the ordinary workload opts out. Default CI Map exports also retain source notes. Runtime tests cover their locations and results, including recursive and polymorphic join lowering, with source attachment enabled and disabled. Synthetic fixtures also cover nested source notes and absent content.

The initial source export was checked against the frozen `work/core-proofs-v1` corpus. All existing test-fixture executable trees match after lexical alpha renaming. The same comparison retains representation, WHNF, speculation, strict-field and join metadata, not just expression tags. All **52 syntactically reachable Map definitions** also match exactly, including cold error paths. Across the whole Map bundle, `-g` changes some unreachable lookup variants and generated metadata; the supplied binding count changes from 1619 to 1621. The capability frontier stays at 52 reachable definitions, three missing globals and thirteen reported issues.

The source-enabled Map export has 8,995 span records and 40,654 references, including 32,790 references carrying nested notes. These are metadata counts, not executed instructions. The independent checker resolves every Map file's embedded content. The smaller source fixture intentionally verifies the missing-content path.

Reproduce the executable comparison after retaining a no-source baseline:

```sh
scripts/prepare-tests.sh
compiler/export-map.sh
python3 scripts/check-source-metadata.py build/map/core/*.json
python3 scripts/compare-executable-core.py \
  work/core-proofs-v1/map/modules.txt build/map/modules.txt \
  --strip-snapshot-prefix --entry mapAggregate
```

Runtime `sourceNotesEnabled=false` suppresses source construction and attachment while evaluating the same exported bundle. The benchmark uses this switch against the same `-g` corpus and runtime, isolating attachment from any GHC optimization changes. The comparisons below measure both attachment and the complete runtime change; source emission alone is not evidence that attachment is free.

`SourceNote` is optimization-tolerant attribution. GHC defines it as a non-counting annotation with soft scope; transformations may copy or widen its coverage. It contains a `RealSrcSpan` and a source name. Optimized laziness, inlining, thunk updates and join lowering therefore prevent a promise of one debugger step per original expression. Binder spans locate declarations but do not locate every generated operation. See GHC 9.14.1's [Tickish definition](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/compiler/GHC/Types/Tickish.hs) and [source-position semantics](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/compiler/GHC/Types/SrcLoc.hs).

Actual tracing or breakpoints remain separate work: AST instrumentable nodes and tags, or Bytecode DSL instrumentation/tag support, plus lexical scopes and debugger-safe inspection of lazy values. Source tables supply attribution for roots, nodes and bytecode locations; they do not provide stepping, forced-value history, or an event log.

## Steady-state regression check

No steady-state regression was detected on the real `Data.Map.Strict` workload.
The primary comparison uses the frozen runtime from immediately before source-note
support against the frozen source-enabled runtime, with the same source-enabled
Core bundle in both. The earlier runtime ignores the extra metadata. The 52
reachable definitions also match the no-source export, as checked above.

| Backend | Before source support | Source notes enabled | Change | Native GHC | Enabled / GHC |
| --- | ---: | ---: | ---: | ---: | ---: |
| AST | 3.425346 ms | 3.424845 ms | -0.01% | 1.293698 ms | 2.647× |
| Bytecode | 2.441079 ms | 2.376102 ms | -2.66% | 1.290936 ms | 1.841× |

These are medians of three process medians, each from five windows lasting at
least two seconds. Processes run serially in rotated order. Each JVM completes
at least 12,000 changing-input workloads and fifteen seconds of warmup before
measurement. Checksums match native GHC; instrumentation is off; no unsupported
trap, Truffle compilation, or deoptimization occurs in measurement or final
installed-code verification. The input cycle is 10,000 through 10,015. The machine
is macOS ARM64 with GraalVM 25.3.4.1 / JDK 25 and GHC 9.14.1.

A separate AST attachment toggle, using the identical new JAR on both sides,
measures **3.577795 ms disabled versus 3.592339 ms enabled (+0.41%)**. Its first
baseline process overlapped an unrelated native build and is retained unchanged;
the separate before/after comparisons followed it. All **135 windows** across
these three comparisons pass validation. Small changes of either sign are not
evidence that source metadata itself improves execution speed.

Each enabled process reports 8,656 deduplicated linked spans and 19 attributed
roots; disabled processes report zero. The compilation logs contain original
Haskell file/line attribution. Generated roots without suitable notes can still
report `Src n/a`; no location is invented for them.

The [combined results](../bench/results/source-notes/summary.json),
[AST before/after](../bench/results/source-notes/ast-before-after/summary.json),
[bytecode before/after](../bench/results/source-notes/bytecode-before-after/summary.json),
and [AST attachment toggle](../bench/results/source-notes/ast/summary.json) retain
raw windows, compilation logs, validation, exact commands, and hashes. The frozen
source-enabled runtime JAR has SHA-256
`855ed5bec19fc9fb142fcdaec73134b2028313f1151b3e12a00b83a5d43f45d5`.
[Evidence](../bench/results/source-notes/evidence/) includes both frozen build
manifests, executable-equivalence checks and all four Map correctness modes.
Map agrees with native GHC on 18 inputs, before and after compilation, with
source attachment both on and off in each backend.

The pre-notes and initial source-enabled AST builds are both slower than the
earlier published 2.426 ms typed-tail build. The graph check below exposed a
result-dispatch barrier and the final build repairs it. The table above remains
the isolated source-note comparison, rather than silently mixing in that repair.

Source attribution is not free in every sense. The seventeen-file Map JSON
bundle grows from **15,213,613 to 52,104,046 bytes** with source tables and nested
references. Loading time and retained memory have not been quantified here.
`THC_SOURCE_NOTES=false` disables export, and `-Dthc.sourceNotesEnabled=false`
disables runtime attachment for an existing bundle. The steady-state conclusion
only concerns execution after loading, compilation and warmup.

## Graph check and typed dispatch repair

The [actual compiled join graphs](source-note-graphs/README.md) retain source-on
and source-off controls for both backends, plus the repaired AST. Source
attachment preserves the indexed graph edges, scheduled blocks and node classes
at each selected phase. The AST controls have a process-specific bloom constant
difference, recorded explicitly in the comparison. This is a structural check,
not a claim of byte-for-byte identical machine code.

The initial AST graph exposes an unrelated defect in `FunctionBody.execute`:
Kotlin's enum `when` compiles to a lookup in a synthetic mutable `int[]` followed
by a switch. Although the node's result kind is constant, the pinned Graal build
does not fold that lookup. Long, data, closure, address and fallback paths survive,
with nine copies of the join loop before high-tier lowering.

Direct enum identity comparisons let partial evaluation select the concrete
entry point. Before lowering, the graph falls from **431 nodes to 86** and from
nine loop copies to one. The enum-array load and integer switch disappear. After
mid-tier optimization it has 330 nodes rather than 1,178; later loop splitting
produces four loops, which are not the former typed-dispatch copies. The live
loop values remain primitive, with no calls, call packets or allocations in the
loop. The Object-returning root still boxes its final Long on exit. Source
attribution remains available.

The repair was then measured separately, with source notes **enabled on both
sides**, identical Core and the same warmup/validation protocol:

| AST build | Time | Cost / GHC |
| --- | ---: | ---: |
| Source notes, enum-switch dispatch | 3.455851 ms | 2.694× |
| Source notes, direct result dispatch | 2.509363 ms | 1.956× |
| Native GHC reference | 1.282605 ms | 1.000× |

That is **27.39% less time**, not a speedup attributable to source metadata.
The [repair comparison](../bench/results/source-notes/ast-dispatch-fix/summary.json)
adds 45 validated windows, bringing the recorded total to **180**. The final
runtime JAR SHA-256 is
`8f4d77ad37da5bd4d21295e625d43067bc114e085d7af1df25204e74b77fc23a`.
The bytecode timing above uses the preceding source-enabled freeze; this final
repair changes only the AST result-dispatch implementation.

All **108 tests** pass with source export enabled by default, including actual
cloned AST/bytecode targets retaining root and child locations through
compilation. The repaired AST also matches all 18 native Map cases before and
after compilation, with no unsupported trap. Logs and frozen source hashes are
retained with the benchmark evidence. No further optimization is included here.
