# Regular Sequence library coverage

This records the normal public-loader matrix for four Sequence entries added on
top of integration revision `09662cdaee0ac84d2a6236adeed8990885d5ea61`.
No guest runtime implementation, compilation limit or settling behavior changed.

Fresh GHC 9.14.1 compilation of the pinned unmodified containers-0.8 source
produced 2,812 native rows, all matching independent models. Sequence contributes
152 supported inputs and 114 frontier-only inputs. The full source/boot/export
provenance and all seven per-entry strict audits are fingerprinted by the regular
`build/libraries/cases.json` manifest.

AST and bytecode, each with handoff disabled and enabled, all passed exactly
8,028 comparisons and 2,760 positive compiled-entry gates. The phase counts per
mode are 2,676 interpreted, 84 compiled-warm, 2,592 withheld cold and 2,676 final
compiled. Sequence contributes 456 comparisons and 164 compiled gates per mode.
All 17 supported-entry diagnostics have zero blackholes/traps and no deferred
unsupported paths. Set and the three recorded Sequence frontiers reject at load.
Both the captured row identities/values and the native manifest were reconciled;
there are no missing or duplicate checks.

The lazy workload's fresh Core retains a reachable self-referential lifted
payload binding. Its successful spine-only checks therefore exercise laziness
with a genuine bottom. The independent review verified all 71 source/input and
63 artifact hashes, reran the model/frontier tests and audit-reuse check, and
checked 126 additional fresh-native/model boundary cases without findings.

`evidence.json` retains source, native binary, Core/provenance, runtime JAR and raw
log hashes, with exact commands and limits. Complete logs remain at the recorded
build paths; the normal CI artifact collection also retains those logs and the
per-entry audits. Reproduce with `python3 scripts/prepare-library-tests.py`, an
`installDist` build, then the four commands recorded in the evidence (the same
backend/handoff loop used by Build CI). The preparation requires the pinned GHC,
GHC package manager, containers archive and GraalVM described in the repository.

The additional view-only source entries are outside this matrix. This result
does not claim to fix the historical first-call host-linkage observation.
See [library coverage](../../../docs/library-coverage.md) for the exact supported
operations and remaining missing-definition frontiers.
