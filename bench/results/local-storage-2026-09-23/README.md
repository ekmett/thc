# Local constructor-class and compact-header comparisons

These two comparisons use the same frozen v7 bytecode runtime and Core corpus, with one option changed at a time. The experimental storage flags remain off by default. Results are local macOS/arm64 measurements and should be compared within each run, not against absolute timings from hosted hardware.

Constructor class matching showed no gain in this run. Compact headers had a lower aggregate median, but substantial fork variation and native drift prevent attributing the reported 13.45% difference to headers. In the compact run, the off forks were 1.528, 2.099 and 1.708 ms; native increased from 1.270 to 1.430 ms by the third fork. The on forks were 1.478, 1.430 and 1.535 ms. All results remain visible below; the lower aggregate is not an accepted stable speedup.

| Option | Off (ms/workload) | On (ms/workload) | GHC (ms/workload) | On/off change |
| --- | ---: | ---: | ---: | ---: |
| Constructor class matching | 1.485299 | 1.520765 | 1.285322 | +2.39% |
| Compact object headers | 1.707978 | 1.478318 | 1.285009 | -13.45% |

These are observed medians of three process medians, each from five measured windows. A percentage here describes this run; it is not a confidence interval or a claim of a stable speedup/regression. Both comparisons retain all 45 windows, signed native checksums, 15-second/minimum-12,000-call JVM warmups, two-second windows, installed last-tier guest code, and clean measured/final-verification Truffle event guards. The harness serializes and rotates engine order across the three forks. Those guards do not rule out host JIT, GC, thermal or other machine contention.

| Option | Fork | Off (ms/workload) | On (ms/workload) | GHC (ms/workload) |
| --- | ---: | ---: | ---: | ---: |
| Constructor class matching | 1 | 1.534612 | 1.510644 | 1.269956 |
| Constructor class matching | 2 | 1.485299 | 1.534247 | 1.285322 |
| Constructor class matching | 3 | 1.461012 | 1.520765 | 1.290759 |
| Compact object headers | 1 | 1.527610 | 1.478318 | 1.270036 |
| Compact object headers | 2 | 2.098743 | 1.430179 | 1.285009 |
| Compact object headers | 3 | 1.707978 | 1.534839 | 1.430431 |

[Class matching](class/summary.json) compares `-Dthc.constructorClassIdentity=false` with `true`; boxed-value caching and unchecked StaticShape storage are disabled, as are compact headers. [Compact headers](compact/summary.json) compares `-XX:-UseCompactObjectHeaders` with `-XX:+UseCompactObjectHeaders`; all three THC flags are disabled. Both use source notes, penalty-free inlining recursion depth 2 and expansion/inlining budgets 12,000. This does not measure a combined configuration.

The JAR SHA-256 is `a73ec0e731b96b63523933cafc876b5e1c64884e24a42ded1b504b60d5bb0d7d`. Each run-config records every library, module, native executable, supplied source tree, toolchain and harness hash, plus exact commands. Original machine paths identify measured inputs; they are not required directories for verification. Complete runtime and Core binaries are not duplicated here.

The [four native-oracle checks](map/checks.json) cover default, boxed cache, constructor class matching and compact headers across all 18 inputs before and after requested compilation, with no unsupported traps. The [153-test compact-header validation](../boxed-values/validation/compact-headers/validation.json) uses the same v7 runtime; [reference hashes](referenced-test-evidence.json) pin the existing XML evidence rather than duplicate it. These checks establish compatibility for the exercised cases, not performance.

[Power observations](publication.json) summarize the unmodified [class](class/power-status.json) and [compact](compact/power-status.json) records. Before/after samples report host state around each process; they do not continuously measure power or prove an idle machine.

```sh
python3 bench/results/local-storage-2026-09-23/verify.py
```

The verifier needs Python and the adjacent checked-in boxed-values test evidence. It rechecks publication hashes, all 90 windows and twelve JVM log guards, recomputes process/aggregate medians, enforces the single-option differences, verifies full Map checks and the referenced 153-test suite. It launches no JVM and needs none of the original `work/` paths. The immutable input hashes establish recorded identities; they cannot reconstruct omitted binaries or historical host conditions.
