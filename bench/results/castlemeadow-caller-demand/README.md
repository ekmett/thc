# Correct caller demand, with Default held fixed

Removing the avoidable argument thunks did **not produce a measured throughput win** in this paired run. The enabled median is 0.267% higher; both variants are about 1.18–1.19× native GHC.

| Bytecode configuration | Median workload | Fork medians (ms) |
| --- | ---: | --- |
| callDemand certificates removed | 1.589148 ms | 1.589148 / 1.590225 / 1.585036 |
| callDemand certificates enabled | 1.593387 ms | 1.591926 / 1.593387 / 1.594149 |
| Native GHC | 1.343328 ms | 1.342700 / 1.344928 / 1.343328 |

Enabled/control is **1.002667**; enabled/GHC is **1.186149**. All three enabled process medians are slightly higher, but the difference is small relative to the observed fork/window spread. [Summary](comparison/summary.json), [all 45 windows](comparison/timings.tsv), [validation](comparison/validation.json) and [exact commands/hashes](comparison/run-config.json) retain the evidence.

This comparison uses the same 176-test runtime JAR on both sides: SHA256 `4e4568c2bdb19bba7a69cf366966bdac0f987d02c30f0ee46ed42aa03abf79bf`. Enabled Core contains caller-demand certificates; the control removes only those metadata keys. The server independently stripped 7,742 certificates containing 7,686 strict positions and checked exact equality for all 17 modules. `balanceR_`'s callee entry contract remains all false on both sides. [Structural proof](provenance/call-demand-structural-proof.json), [transfer hashes](provenance/call-demand-transfer-manifest.json) and [original frozen manifests](provenance/call-demand-v1-manifest.json) distinguish this from the earlier manual callee-entry experiment.

Both variants pass all 18 Linux native-oracle inputs before and after requested compilation with zero traps. Instrumented preflight counters show total thunk evaluations falling 16,411→3: all 16,408 argument-thunk evaluations disappear, leaving the unrelated `lvl` evaluations. Timed processes have instrumentation disabled. The 176-test suite and both-backend local Map checks predate the Linux run; the server repeats the full Map check under the actual Default policy.

Both sides use explicit `-Dpolyglot.compiler.InliningPolicy=Default`, depth 2, expansion/inline budgets 12,000, class-owned layouts and compact headers. Typed cases, leading-case returns, constructor-class matching, unchecked storage and boxed-value caching are explicitly off. The frozen Core was exported on macOS and JIT-compiled on Linux. Native GHC9.14.1 was built on Linux with -O2 and Core/STG lint against the matching benchmark and vendored containers 0.8; no macOS native binary was transferred.

The i9-12900K server runs pinned GraalVM 25.3.4.1+1.1/JDK25, G1 and a 4 GiB initial/maximum heap. The complete harness and its children inherit P-core logical CPUs 0–15 affinity. Three fresh JVM forks each warm at least 45s and 30,000calls; native warms 10s; five windows per engine/fork last at least 2s, and the serial engine order rotates. [Driver](execution/driver-caller-demand.json) and [host telemetry](execution/caller-demand-default-host-samples.jsonl) record unchanged powersave governors, one-minute load 0.31–1.27 and package temperature at most 64°C. All 45 windows pass checksum, warmup and no-measured/final-Truffle-event checks. These guards do not exclude host JIT/GC or every external disturbance.

Run `python3 tools/verify.py` from any working directory to recheck file hashes, runtime/Core relationships, recorded structural proof, full 18-input preflights, exact options, raw timing windows and medians without a JVM or network. Recomputing the full Core deep diff requires the retained input corpus; the portable verifier checks its captured proof and immutable hashes.

Matched Linux allocation/worker-graph diagnostics are separate processes and were pending when this timing bundle was sealed. No allocation improvement or specific inlining recovery is inferred from these timing rows.
