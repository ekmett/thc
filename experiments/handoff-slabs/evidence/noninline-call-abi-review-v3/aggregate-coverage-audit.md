# Real Core aggregate coverage audit

Read-only audit of the main THC source on 2026-09-23, with pinned GHC 9.14.1 source documentation. No new runtime implementation or performance run was made for this audit.

## Finding

Unboxed tuples and sums are an explicit language-coverage gap. The dense handoff prototype does not close it: it transports supported scalar argument fields and a single Long or natural Object result. It does not lower a logical aggregate to multiple components, destructure one, or preserve several simultaneously outstanding aggregate results.

The current genuine GHC/native-oracle tests are valuable for integer arithmetic, boxed data, closures, laziness, partial/overapplication, joins, and recursive/tail control flow. They do not establish runtime support for unboxed aggregates. The oracle entries currently have `Int# -> Int#` external signatures, which can remain the host interface for aggregate tests: put opaque aggregate producers and consumers inside each entry.

| Surface | Current evidence | Missing evidence/behavior |
|---|---|---|
| Exported representations | `compiler/Thc/Plugin.hs:137` records `typePrimRep_maybe`; explicit tuple/sum types are classified `unknown` | Structured component boundaries, sum layout and alternative-to-slot maps |
| Constructor metadata | `compiler/Thc/Plugin.hs:243` records boxed/tuple/sum kind, tag, worker arity, and per-worker-slot primitive reps | Executable aggregate construction and pattern matching |
| AST and bytecode loaders | `runtime/Program.kt:1060` and `runtime/BytecodeProgram.kt:812` reject non-boxed constructors | Both backends need the same aggregate semantics |
| Data field storage | `runtime/DataValues.kt` supports supported integer, reference, and zero-width fields | Multi-register fields and Float/Double/vector storage are not implemented |
| Capability audit | `scripts/core-capabilities.json` permits only boxed constructors; `audit-core.py` rejects multi-register constructor fields | Reachable aggregate binder/result boundaries without an aggregate constructor need a deliberate negative test |
| Existing aggregate fixture | `compiler/test-fixtures/RepresentationAudit.hs:21` exports empty-tuple identity; metadata checker asserts `unknown` plus `primReps: []` | This is compiler metadata coverage, not runtime aggregate coverage |
| Precise exception fixture | Demand metadata audit exports a state-token/unboxed-tuple result path | It does not execute general IO/stateful primops or tuple returns |

One capability distinction deserves a focused regression test. `CoreRepresentations.parse` and `audit-core.py:representation` deliberately accept `unknown` with arbitrary recorded primitive reps. Consequently `unknown` alone is not a fail-closed aggregate capability marker. A reachable empty-tuple identity can mention no tuple constructor in its body. Test that boundary explicitly before describing all unsupported aggregates as rejected. This is a validation gap, not a claim that current execution demonstrably miscomputes it. Do not reject every `unknown`: that category also represents legitimate abstract boxed values.

## First bounded fixture

Add a genuine GHC fixture with an opaque pair producer, opaque forwarding call, and integer-valued entry:

```haskell
{-# LANGUAGE MagicHash, UnboxedTuples #-}
{-# OPAQUE pair #-}
pair :: Int# -> (# Int#, Int# #)
pair x = (# x +# 257#, x -# 1025# #)

{-# OPAQUE forward #-}
forward :: Int# -> (# Int#, Int# #)
forward x = pair x

entry :: Int# -> Int#
entry x = case forward x of
  (# a, b #) -> case pair (x +# 4097#) of
    (# c, d #) -> a +# b *# 3# +# c *# 5# +# d *# 7#
```

Use pinned-GHC exports before and after Tidy, audit the reachable graph, and compare both AST and bytecode with native GHC. Unequal coefficients make component permutation visible; values outside the Long cache expose accidental boxing assumptions. Include negative, zero, large positive, and signed-boundary inputs with the existing machine-Int wraparound oracle. Check the exported Core actually retains the opaque aggregate boundary.

Initially record an explicit unsupported capability result for this fixture; it should not silently become a skipped runtime success. After the smallest aggregate implementation, require interpreter and compiled results in both backends, plus a residual direct-call run. Add a small tail-forwarding variant and retain the first result's primitive locals across the second producer. This is a bounded first feature, not a request to add arrays, IO, floating point, and sums simultaneously.

## Follow-on fixture order

1. **Zero-width and lazy components:** a tuple containing `(# #)`, an `Int#`, and a lifted `Box`. Keep the lifted component bottom in the branch that ignores it. Test a pure state-token passthrough separately. Neither zero-width form may shift later physical fields or erase logical application arity.
2. **Sums:** exercise both constructors of `(# Int# | (# Int#, Box #) #)` and of `(# (# #) | Box #)`. Check tags, overlapping primitive/reference slots, inactive reference clearing, and laziness of lifted payloads. Use branch-specific native results so a wrong tag or map cannot pass by coincidence.
3. **Nested aggregates and unpacked fields:** tuple-of-sum, sum-of-tuple, and a real strict unpacked multi-constructor field. These can arise through GHC worker transformations even when source code does not explicitly use sum syntax.
4. **Stateful primops and other primitive reps:** mutable arrays/IO, Float/Double, and vectors need their own capability milestones. `runRW#` erasure and `realWorld#` support are not evidence that these operations already work.

## Minimum implementation boundary

Export a structured logical aggregate descriptor alongside flattened primitive reps. Preserve zero-width components and logical binder/application boundaries. Use GHC's representation APIs for sum layout and constructor-specific slot maps, not pretty-printed types, source constructor arity, or a hand-written approximation of register coalescing. Initially accept only the supported Long/reference/zero-width subset and reject unsupported components explicitly.

The pinned `GHC.Types.RepType` documents that one Core value can have multiple `PrimRep`s; both `State# s` and `(# #)` have no physical reps. `ubxSumRepType` produces a tag slot plus coalesced payload slots, and `layoutUbxSum` maps an alternative's fields to that payload layout. `GHC.Stg.Unarise` documents recursive tuple flattening and matching constructor/pattern translations. THC exports before GHC's STG unarisation, so it must provide the equivalent logical lowering itself. The [GHC 9.14.1 user guide](https://downloads.haskell.org/ghc/9.14.1/docs/users_guide/exts/primitives.html) also describes multi-component tuple returns and lazy lifted sum payloads.

AST and bytecode must agree on construction, case binders, forwarding, logical arity/PAPs, and returns. Keep semantic aggregate support independent of the experimental `thc.handoffSlabs` optimization flag. A correct control path is needed even where guest calls inline or the optimization is disabled.

For optimized multi-component transport, use an independently owned receiving result area with dense fields interned by physical representation. Nested non-tail producers require distinct outstanding destinations; tail transfer forwards the receiving continuation's destination. Keep argument loans separate. The scalar return register is a special case, not an aggregate destination protocol. The single incoming mailbox is valid only with the existing staged-argument/no-guest-callback interval; direct producer-to-input-carrier evaluation must revisit that ownership proof. Retained frame snapshots and deoptimization must not retain reusable carrier ownership.

This is a language-level storage convention across Truffle's existing Object-array/Object call interface. It does not expose GHC's physical multiple-register calling convention through public Truffle APIs. Preserve this distinction in both tests and performance claims.

Local primary sources read: `/Users/ekmett/.ghcup/ghc/9.14.1/share/doc/ghc-9.14.1/html/libraries/ghc-9.14.1-bcbf/src/GHC.Types.RepType.html` and `GHC.Stg.Unarise.html` in the same directory. Implementation pointers above are relative to `/Users/ekmett/thc/src/main/kotlin/thc/` for `runtime/` and `/Users/ekmett/thc/` otherwise.
