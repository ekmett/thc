# Linux release smoke: caller demand remains opt-in

Release JAR `5af987892c35fedc0f170d245574ad704609e9747c7993f970991191d71a773c` passed all four requested Linux checks on 2026-09-23. Each checks all **18 native-oracle inputs before and after requested compilation**, with zero unsupported traps: **144 verified input/phase results** in total.

| Backend | Caller-demand property | Argument-thunk evaluations | `lvl` evaluations | Traps |
|---|---|---:|---:|---:|
| AST | Absent (default off) | 16,408 | 3 | 0 |
| AST | `-Dthc.callDemands=true` | 0 | 3 | 0 |
| Bytecode | Absent (default off) | 16,408 | 3 | 0 |
| Bytecode | `-Dthc.callDemands=true` | 0 | 3 | 0 |

These are observed counters from the instrumented correctness checks. This is **release validation, not a new performance or allocation measurement**. The earlier isolated demand experiment's timing and graph publications remain unchanged.

The new JAR was copied to a fresh `sources/release-main-5af98789` server directory. All ten dependency JARs match the frozen `call-demand-v1` runtime. All 17 certified Core modules and the Linux native oracle retain their earlier hashes. The same certificate-bearing Core is used for both off and on, so this checks the new runtime opt-in gate directly. Earlier snapshots were not overwritten. [Input manifest](input-manifest.json), [local staging proof](local-proof.json) and [Core structural proof](provenance/call-demand-structural-proof.json) preserve the hashes.

All four runs explicitly use Graal's `Default` policy, recursion depth 2, both inlining budgets 12,000, bytecode or AST as specified, diagnostic traps, source notes, `-Xms4g -Xmx4g`, compact headers and CPUs 0–15. Class-owned layouts are on; the other five experimental representation/dispatch flags are off. JVM option-injection environment variables are removed before launch; their names, if present, are recorded without exposing their values. The off runs contain no `thc.callDemands` property. [Driver](tools/run.py) and [validation](validation.json) retain exact commands and diagnostics; each named run directory contains unchanged stdout, stderr and command JSON.

The pinned Linux runtime is GraalVM 25.3.4.1+1.1 (Java 25.0.4.1). Core was exported on macOS; the native binary was built on Linux using GHC 9.14.1 and vendored containers-0.8 ([native build](provenance/native-build.json)). The local release build's Gradle XML inventory independently reports **177 tests, zero failures/errors/skips** ([inventory](provenance/local-test-inventory.json)); that full suite was not rerun on Linux in this smoke check.

Verify the portable bundle:

```sh
python3 tools/verify.py
```

The verifier checks every published file hash, release/dependency/Core/native hashes, all four exact configurations, every native-oracle row, trap and thunk counters, and the recorded local test inventory. Binaries and the large Core corpus are referenced by immutable hashes rather than duplicated here.
