# Constructor-class screening interrupted

All seven requested configurations passed all 18 native-oracle inputs before and after requested compilation, with zero diagnostic traps. This includes bytecode class identity with argument speculation disabled and return speculation explicitly enabled.

The short native health probe measured 1.236495 and 1.219957 ms/workload, with valid checksums. Power reports nevertheless alternated between AC and battery, remaining at 4% and discharging.

The first AST checked/class-off screen then measured 4.264436 ms/workload, roughly twice the prior same-policy checked result. Its compiler/checksum guards passed. **No performance result is accepted.** The matrix was stopped at 06:33:55 UTC while the second AST setting had begun; the remaining five settings were not run. Only the owned matrix and its descendants were terminated. No JVM remains.

`health/` retains the exact native command, hashes, both windows, raw power/thermal snapshots, and an aggregate CPU summary. `power-observations.jsonl` records subsequent observations. `matrix/` retains all seven preflights, complete first-screen logs and partial second-screen logs. `stop-anomaly.json` records the stop timestamp/processes and power state. `performance-rejected.json` records the explicit rejection separately from the successful correctness/compiler validations. `tools/` contains the immutable helpers used. Resume with fresh output directories under stable power; retain this run.

## Published scope

This is the retained, interrupted candidate-six screening run, published without rerunning any workload, with the process-inventory omissions described below. [Provenance](provenance.json) lists all seven preflights and the exact status of each planned timing. [Original artifact hashes](original-artifacts.json) describe the original local capture, including omitted inventories and the original health summary; [publication manifest](manifest.json) hashes this directory. Frozen runtime JAR: `4829e1da3c877c3fd985c2f4d1c3a8eba15a4b7e4199f3dbc158b1e4138c5afd`.

| Backend | Storage checks | Constructor class identity | Argument speculation | Correctness | Timing |
|---|---|---|---|---|---|
| AST | on | off | on | 18 inputs before/after pass | Completed; rejected for host slowdown |
| AST | on | on | on | 18 inputs before/after pass | Interrupted; incomplete |
| AST | off | on | on | 18 inputs before/after pass | Not started |
| Bytecode | on | off | on | 18 inputs before/after pass | Not started |
| Bytecode | on | on | on | 18 inputs before/after pass | Not started |
| Bytecode | off | on | on | 18 inputs before/after pass | Not started |
| Bytecode | on | on | off | 18 inputs before/after pass | Not started |

Return-type speculation was explicitly on for every configuration. All planned screens use penalty-free recursion depth 2 and expansion/inlining budgets 12,000. The passing [native health probe](health/summary.json) did not establish sustained host stability: the later AST process slowed again, while power reports continued alternating and the battery remained at 4%. Cause remains unproved. [The rejection](performance-rejected.json) overrides the first screen's successful correctness/compiler guards; those guards cannot detect all host-performance anomalies. No speedup or regression conclusion follows from this run.

The parent directory's runtime tests, manifest and constructor-access checks are separate evidence; this copy does not change them. Raw power/thermal snapshots and [aggregate CPU figures](health/cpu-summary.json) are published. Full before/after process inventories stay in ignored local `work/` captures; [omission metadata](omitted-local-evidence.json) retains their original hashes and paths. The published health summary explicitly links the aggregates and omissions, so it is not byte-identical to its original local copy. No installed-application inventory is published. Restarts must use new output directories and stable host power, preserving this interrupted run.
