# Current default after power recovery

This is a fresh local Apple M3 comparison of the original bytecode runtime at
`4c116a8493eafad4077a1c5eb9c046bff56fe3c4` and the cumulative default runtime at
`fab44ea7225073c45b1ed911a1ae3ddf52085cb7`. Both use one frozen v6 Core/native
corpus. Constructor class matching, unchecked storage and compact object headers
are disabled. This measures the complete default change, not either storage
experiment, and predates boxed-value caching.

| Engine | Median of three fork medians | Fork medians |
| --- | ---: | --- |
| Original bytecode | 2.360546 ms | 2.360546, 2.355496, 2.363598 ms |
| Current default bytecode | 1.523473 ms | 1.630307, 1.465651, 1.523473 ms |
| Native GHC | 1.279898 ms | 1.279898, 1.268217, 1.286410 ms |

The candidate uses **35.46% less elapsed time** than the original runtime and
costs **1.1903 times native GHC** in this run. The candidate forks vary by about
11%; the first also slows within its measured windows. These observations are
retained, not filtered out. This is an exploratory workload comparison, not a
precision claim about a small optimization or arbitrary Haskell programs.

All 45 windows pass checksum, installed last-tier code, source-note and phase
checks. Every JVM warms for at least 12,000 workloads and 15 seconds before five
windows of at least two seconds. Processes run serially in rotated order, with
both inlining budgets at 12,000 and recursion depth 2. Measurement instrumentation
is disabled; no unsupported trap, measured Truffle compilation/deoptimization,
or final-verification compilation event occurred. Host JIT, GC and other machine
activity are not excluded by those checks.

The Mac had recovered to charging before this comparison. The
[power observations](power-status.json) cover each process; the summary reports
no power warning. Earlier low-battery runs remain separately marked as rejected.
Only idle Gradle/Kotlin daemons were present before this serial workload; no
other local benchmark or build was started during it.

[Summary](summary.json), [validation](validation.json), [all windows](timings.tsv),
and [configuration](run-config.json) preserve commands, hashes and results.
[Source verification](source-verification.json) confirms that the frozen source
trees exactly match the two commits above. The original JAR is
`8f4d77ad37da5bd4d21295e625d43067bc114e085d7af1df25204e74b77fc23a`;
the candidate JAR is
`4829e1da3c877c3fd985c2f4d1c3a8eba15a4b7e4199f3dbc158b1e4138c5afd`.
The comparison harness verifies immutable runtime, Core and native inputs before
and after measurement.
