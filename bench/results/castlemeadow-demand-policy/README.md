# Isolating the inlining policy on the manual-demand probe

With identical manual Core and runtime, **Default uses 6.70% less elapsed time than Agnostic** in this controlled Linux comparison. All45 windows pass the checksum, warmup and measured-Truffle-event guards. This remains the manually changed callee-entry probe; it does not establish whether the correct caller-demand implementation helps.

| Policy/runtime | Median workload | JVM/process fork medians (ms) |
| --- | ---: | --- |
| Explicit Agnostic | 1.705624 ms | 1.709434 / 1.701143 / 1.705624 |
| Explicit Default | 1.591272 ms | 1.591272 / 1.586930 / 1.596328 |
| Native GHC | 1.341866 ms | 1.343609 / 1.341866 / 1.340257 |

Default/Agnostic is **0.932956**; Default/GHC is **1.185866**. Every Default fork median is lower than every Agnostic fork median. [Summary](comparison/summary.json), [raw windows](comparison/timings.tsv), [validation](comparison/validation.json) and [exact commands/provenance](comparison/run-config.json) retain the result. Do not compare this row against historical runtimes as if they were one paired experiment.

Both sides use the same a74e616f540def7af2afa821e3856c7377d472ad23b7926e4b7bb6251e07fe9c runtime and the same17 frozen Core modules. `08-Data.Map.Internal.json` has SHA256 fa7cfcff91ccd1708839e720ef90b6e3098627a5c518a4805cd53bd76348e05f, the manual fourth-argument entry-demand variant. Both policy preflights pass all18 native-oracle inputs before and after requested compilation, with zero traps and only3 unrelated `lvl` thunk evaluations. The policy option is explicit in every command; no THC implementation changed.

Method: Linux i9-12900K, pinned GraalVM25.3.4.1+1.1/JDK25, GHC9.14.1 native ELF from matching benchmark/vendored containers0.8. THC Core was exported on macOS and JIT-compiled for Linux. Both JVMs use4 GiB initial/maximum heap, G1, compact headers, owned layouts, all five experimental feature flags off, source notes on, instrumentation off, recursion depth2 and expansion/inline budgets12,000. The harness and children use P-core logical CPUs0–15. Each of3 forks warms at least45s and30,000calls; native warms10s;5 windows per engine/fork last at least2s, with rotated serial engine order. [Driver record](execution/driver-demand-policy.json) and [host telemetry](execution/manual-demand-policy-default-host-samples.jsonl) retain the environment: unchanged powersave governors, one-minute load0.33–1.18, package temperature at most62°C. Host JIT/GC and all external interference are not ruled out.

The Agnostic source audit found a negative benefit/cost estimate for the extra visible eager recursive call; `Default` uses a different analysis. This throughput run does not itself contain a compiled graph showing which Default edges changed, so the precise recovery mechanism remains a hypothesis pending a matching graph capture.

Run `python3 tools/verify.py` from any directory to check the full archived file inventory, exact runtime/Core/native/options, both18-input preflights,45 raw timing windows, compilation-event guards and recomputed medians without a JVM or network.
