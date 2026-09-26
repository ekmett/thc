# Compact interface-provider identities — 2026-09-26

Relative to public `a9d0f7f3`, the interface probe retains GHC `Module` values
while deduplicating providers, then converts each distinct provider to its
unit/module strings once. Previously every retained `Name` unpacked both
strings before inserting them into a set, repeatedly allocating the same
identities throughout large interfaces.

The original GHC Binary traversal, writer callbacks, `putNameLiterally` calls
and binary write order are unchanged. Provider membership is checked against
the same complete request; known-key Names are still visited. GHC 9.14.1's
`Module` ordering compares unit and module FastStrings lexically. This is not
a Name-table shortcut, skipped dependency check, changed fingerprint algorithm,
or reuse of evidence across different inventories.

## Frozen helper screen

The same complete 512-interface request was run in four fresh processes in
baseline, candidate, candidate, baseline order. Only the helper binary changed;
the compiler, package database and interface files were read-only. Both helpers
were built with the same pinned compiler and ordinary `-O1` configuration.
Each invocation used `--probe-inventory`, `+RTS -s`, and GNU time. Host load was
shared, so times are screening evidence rather than a quiet startup benchmark.

| Statistic | Baseline 1 / 2 | Candidate 1 / 2 |
| --- | ---: | ---: |
| Allocated bytes | 11,460,949,256 / 11,460,949,256 | 1,322,777,960 / 1,322,777,960 |
| Maximum live bytes | 123,072,176 / 123,072,248 | 110,951,264 / 110,951,264 |
| RTS memory | 333 / 333 MiB | 245 / 248 MiB |
| CPU seconds | 3.060 / 3.341 | 1.382 / 1.545 |
| Wall seconds | 3.057 / 3.338 | 1.381 / 1.543 |
| Maximum RSS | 362,304 / 336,768 KiB | 280,896 / 282,624 KiB |

Allocation fell **88.46%**, from 11.461 GB to 1.323 GB per probe. This measures
the helper only, not total driver startup or a complete application. The
candidate does not contain the separate streamed-project-digest change.

Every response was byte-identical, including inventory order, full retained
interface fingerprints and complete-Core flags. Request SHA-256:
`4fa503c331618faf1e8834c970aea54e7b6ac2353d8582d877d31e120798413b`.
Response SHA-256 for all four processes:
`b327f05fdbd98edbb24895c93a1657135f1ef827e6ef69b0683c5c6fc01afb60`.

The helper invocation is reproducible with a saved exact inventory request:

```sh
/usr/bin/time -v thc-interface --libdir "$libdir" --unit "$unit" \
  --way dynamic --probe-inventory --package-db "$database" \
  +RTS -s -RTS < request.json > response.json 2> profile.txt
```

## Verification

The strict development build passes all nine focused driver controls. Fresh
real-interface fixture production passes 21 native rows and the complete/thin,
source, identity, way, foreign and cache gates. In particular, omitted unit and
module inventories still force ordinary acquisition even when usage metadata
is present; retained-Core, foreign, annotation and dependency mutations remain
observable; initial-probe mutation and failed-refresh preservation still pass.

All 30 selected runtime tests pass across default and dense modes:
`HandoffTest` and `InterfaceCoreNativeTest`, including strict admission,
original native results, first-installed AST/bytecode calls and retained code.
No runtime code, compiler option, or assertion was changed.
