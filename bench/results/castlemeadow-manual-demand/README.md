# Manual balanceR_ demand probe on Castlemeadow

The manual fourth-argument demand **regressed by 9.77%**. This changes callee entry metadata and is not the final caller-demand implementation.

| Bytecode configuration | Median workload time | Fork medians (ms) |
| --- | ---: | --- |
| Original Core | 1.598564 ms | 1.598564 / 1.599559 / 1.595512 |
| Manual demand metadata | 1.754686 ms | 1.754686 / 1.761442 / 1.751383 |
| Native GHC | 1.343481 ms | 1.341828 / 1.343481 / 1.345077 |

Manual/original is **1.097664**; manual/GHC is **1.306074**. Every manual process is slower, while the native controls remain stable. All [45 windows](comparison/timings.tsv) pass checksum, warmup and no-measured-Truffle-event guards. [Summary](comparison/summary.json), [validation](comparison/validation.json) and [raw commands/hashes](comparison/run-config.json) retain the result.

Both sides use the same a74e616f540def7af2afa821e3856c7377d472ad23b7926e4b7bb6251e07fe9c JAR, class-owned layouts and compact headers; typed cases, leading-case returns, constructor-class matching, unchecked storage and boxed-value caching are explicitly off. The Core differs only in two copies of `balanceR_`'s fourth `entryStrict` flag (binding and lambda metadata). [Server structural proof](provenance/demand-structural-proof.json) records the exact JSON paths and hashes; all other modules are byte-identical. The large Core corpus and binaries are retained in the isolated benchmark workspace, not duplicated here.

Both variants passed all 18 native-oracle inputs before and after requested compilation, with zero diagnostic traps. Instrumented checks report argument-thunk evaluations falling from 16,408 to zero (three unrelated `lvl` evaluations remain). These counters are from the correctness preflight, not timed runs.

This is the same Linux i9-12900K host and pinned toolchain as the preceding three-comparison Castlemeadow bundle: GraalVM 25.3.4.1+1.1 / JDK25, GHC9.14.1, vendored containers0.8. THC uses frozen macOS-exported Core re-JITed for Linux; native GHC was built on Linux from the matching benchmark and vendored library sources. [Native build proof](provenance/native-build.json) identifies the ELF binary and linted -O2 build.

The serial harness uses P-core logical CPUs0–15, a fixed 4 GiB initial/maximum heap, G1, compact headers, source notes on, instrumentation off, default Agnostic policy and depth2/12,000-node expansion and inline budgets. Each of three JVM forks warms for at least 45 seconds and 30,000 calls; native warms for 10 seconds. Five windows per engine/fork run for at least two seconds; engine order rotates. [Execution record](execution/driver-demand.json) proves inherited affinity. [Host telemetry](execution/balanceR-fourth-demand-host-samples.jsonl) records unchanged powersave governors, one-minute load0.42–1.36 and package temperature no higher than62°C. The event guard does not rule out host JIT/GC or all external interference.

Run `python3 tools/verify.py` from any working directory to verify the complete file inventory, checksums, 18-input preflights, captured Core/JAR relationships, exact options, all raw windows and recomputed medians without a JVM or network. The portable verifier checks the recorded structural proof; reproducing the full JSON deep diff requires the retained Core inputs.

The companion `work/manual-demand-inlining-review` audit explains the macOS diagnostic graph's negative inlining score. It is separate from this Linux throughput measurement; a graph on one architecture does not by itself completely explain elapsed time on another.
