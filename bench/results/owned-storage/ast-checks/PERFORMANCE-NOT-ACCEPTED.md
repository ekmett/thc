# Performance result not accepted

The 45 windows pass native checksums, source-attribution checks, installed-code checks, and compilation/deoptimization guards; frozen inputs are unchanged. The timing result is nevertheless unsuitable for a clean performance claim.

Native GHC fork 3 slowed to 2.494 ms from 1.253–1.283 ms. Checked AST fork 3 slowed to 4.274 ms from 2.173–2.204 ms, and unchecked AST to 4.140 ms from 2.072 ms. At 2026-09-23 06:20:13 UTC, a read-only power/CPU snapshot found the machine on battery at 4%, discharging, with an early battery warning. No recorded thermal/performance warning or other heavy JVM/GHC appeared in the subsequent snapshot; that snapshot cannot exclude earlier transient contention.

The low-battery state plausibly explains the slowdown, but causality is not established. All raw results remain. The benchmark finished naturally, and no additional CPU workload was started. Repeat under stable external power before accepting the aggregate ratios. Argument-type-speculation preflight/screens remain unrun.
