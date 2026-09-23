# Set source-binding identity audit

Follow-up to library checkpoint `0292a924549aacc0ec8ce776e52b287a88c321b9`,
performed on GHC 9.14.1 on 2026-09-23. This is a source-closure audit, not a
Set execution or performance claim.

## Result

The sole missing Set source binding was already resolved by the library helper's
explicit post-Tidy export. Fresh pre- and post-Tidy compilations of the unchanged
Set workload and SHA256-verified containers-0.8 source confirm:

| Export boundary | Supplied bindings | Reachable bindings | Missing source-library globals | Missing boot globals | Capability issues |
|---|---:|---:|---:|---:|---:|
| Before Tidy | 629 | 71 | 1 | 3 | 210 |
| After Tidy, before CorePrep | 634 | 71 | 0 | 3 | 210 |

The only missing-global difference is
`main:Data.Set.Internal.merge_$smerge1`. The fresh pre-Tidy source export has
two private `$smerge` bindings (`$smerge_siHn` and `$smerge_siGS` in this run),
both with the same five-argument type. Private uniques are not stable across
compilations. Matching names, types or arities would be ambiguous and is not the
fix.

GHC's generated `Data/Set/Internal.hi` declares `merge_$smerge1` at arity five,
with worker strict-entry marks `[false, true, true, true, true]`, but supplies no
executable unfolding. The post-Tidy source JSON contains exactly one definition
under that actual external identity, a five-argument lambda, with those same
marks and `entryStrictSource = "ghc-id"`. Seven audited call sites resolve to
it: one from the specialized workload difference, two from `merge`, two recursive
sites and two from the other specialized merge worker.

The existing `Thc.Plugin.exportLate` serializes actual `cg_binds` and records
those definitions for the same compilation's closure walk. The helper selects
that boundary for **all** source modules in the library compilation, including
the importing workload. It does not rename pre-Tidy JSON, invent an unfolding,
change library source, insert runtime aliases or alter aggregate representations.

No additional exporter/runtime change is justified by this source-binding audit.
The preparation helper already rejects any missing `main:` definition in Set's
reachable graph, so this identity regression has an executable preparation gate.

## Remaining unsupported frontier

The complete multiset of capability `(code, detail)` pairs is identical before
and after Tidy: 101 aggregate-representation, 29 constructor-kind, 65
constructor-field-representation and 15 unsupported-primitive reports. The
primitive reports are 14 `reallyUnsafePtrEquality#` uses and one `readMutVar#`.
The genuine unboxed pairs/triples remain unboxed and unsupported.

All three remaining missing globals are boot exception machinery, not Set source
identity failures:

- `ghc-internal:GHC.Internal.Exception.$fExceptionErrorCall_$ctoException`
- `ghc-internal:GHC.Internal.Exception.Backtrace.collectExceptionAnnotationMechanismRef`
- `ghc-internal:GHC.Internal.Stack.withFrozenCallStack1`

Both audits reject the workload. The already-validated strict runtime checks
also reject Set on both backends; these rejections are not execution passes.

## Reproduction

After preparing the pinned compiler and the library boot bundle using
`python3 scripts/prepare-library-tests.py`, run from the repository root in Bash:

```sh
for mode in pre post; do
  flags=()
  if [ "$mode" = post ]; then
    flags+=(-fplugin-opt=Thc.Plugin:post-tidy)
  fi
  THC_CORE_OUT="$PWD/build/set-identity-$mode/core" \
  THC_GHC_OUT="$PWD/build/set-identity-$mode/ghc" \
    compiler/export.sh -i"$PWD/vendor/containers-0.8/src" \
      -I"$PWD/vendor/containers-0.8/include" \
      -fplugin-opt=Thc.Plugin:closure=setAggregate "${flags[@]}" \
      examples/THC/SetWorkload.hs || exit
  python3 scripts/audit-core.py --entry setAggregate \
    --output "build/set-identity-$mode/audit.json" \
    "build/set-identity-$mode/core" build/libraries/boot/core
  result=$?
  # Exit 1 is the expected unsupported-frontier result, not a supported pass.
  if [ "$result" -ne 1 ]; then exit 1; fi
done
```

Compare the reports' `missingGlobals` sets and capability issue multisets, and
inspect the actual binding in each `core/Data.Set.Internal.json`. This audit
does not require or perform a native timing campaign.
