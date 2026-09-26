# Byte-oriented complete-Core rendering — 2026-09-26

The complete-interface helper now renders its ordered JSON directly into UTF-8
bytes and forces the complete success response before writing stdout. The
existing String serializer APIs remain available and unchanged. Their original
renderer was moved without alteration into the internal `THC.JSON` module;
the new renderer consumes the same ordered representation and preserves field
order, duplicate fields, control escaping, Unicode and numeric spelling.

Floating literal formatting remains the existing GHC serializer's String
payload, not a new floating-point conversion. Surrogate code points are rejected
instead of being silently replaced or emitted as malformed UTF-8. A late
serialization failure cannot expose a partially rendered success document:
the strict response is evaluated inside the existing exception boundary.
The separate inventory-probe protocol, error categories, missing-Core response,
binary interface reader, hydration and foreign/annotation metadata are unchanged.

## Frozen original-interface screen

Baseline was public `8ecd3a51` plus the compact-provider change (`c21584a6`,
the same source patch as `6597fe2d`). Both helpers used the same pinned GHC
9.14.1 and ordinary `-O1` configuration. Original installed containers Map and
IntMap interfaces were read-only, with source notes enabled. Each module used
four fresh processes in baseline, candidate, candidate, baseline order.
No complete compiler or application acquisition was repeated.

| Module / statistic | Baseline 1 / 2 | Candidate 1 / 2 |
| --- | ---: | ---: |
| Map allocated bytes | 3,466,175,400 / 3,466,175,400 | 2,757,921,848 / 2,757,921,848 |
| Map maximum live bytes | 346,185,280 / 346,185,280 | 142,910,720 / 147,061,888 |
| Map peak RSS, KiB | 905,664 / 904,896 | 445,632 / 454,656 |
| Map CPU seconds | 2.055 / 2.094 | 1.590 / 1.598 |
| IntMap allocated bytes | 4,467,727,816 / 4,467,727,816 | 3,557,133,168 / 3,557,133,168 |
| IntMap maximum live bytes | 406,523,640 / 389,576,576 | 183,181,344 / 183,181,112 |
| IntMap peak RSS, KiB | 1,053,120 / 1,010,880 | 552,384 / 552,000 |
| IntMap CPU seconds | 2.363 / 2.185 | 1.895 / 1.894 |

Helper allocation fell about 20.4% for both modules, and maximum RSS roughly
halved. These are per-helper results, not process-tree or whole-application
startup measurements. Times were collected on a shared host and are screens,
not quiet throughput claims.

All four Map responses were identical: 8,724,859 bytes, SHA-256
`243268befc0747864819263d562cbff09fef1bc236c3c90f83b0bda0abba476c`.
All four IntMap responses were identical: 11,221,008 bytes, SHA-256
`cbad5695464f0ec7311348cb241d38303f5afb0ad0618858ac0c08e4169d0063`.
The first shell harness omitted `ghc-pkg --unit-id` when resolving the registered
unit's import directory. It stopped before any export and was retained separately;
the completed measurements used the corrected frozen harness.

## Verification

The strict `interface-json` test suite passes ten tests, covering ordered/nested
values, all control characters, exact old escape spelling, every non-surrogate
BMP character, supplementary Unicode boundaries, arbitrary-size integers,
existing floating payload strings, deep/wide documents, late exceptions and
surrogate rejection. All nine focused driver subprocess controls also pass.

Fresh real-interface production passes 21 native rows and all source/identity,
way, thin-interface, foreign and cache controls, including omitted providers,
retained-payload mutations, exact-probe reuse and failed-refresh preservation.
Existing String serializer and source-plugin callers still build and participate
in these fixtures. All 30 selected runtime tests pass across default and dense
modes (`HandoffTest` and `InterfaceCoreNativeTest`), including original native
results, strict admission, first-installed AST/bytecode calls and code retention.
No runtime code, compiler setting, or assertion was weakened.

The first strict fixture build stopped at two redundant imports in the newly
integrated rubbish-literal fixture. A separate two-line hygiene commit removed
only those imports; the successful rerun retained warnings-as-errors.
