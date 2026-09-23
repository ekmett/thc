# Hosted Map comparisons, 2026-09-23

[Manual CI run 35828824528](https://github.com/ekmett/thc/actions/runs/35828824528)
passed on a GitHub-hosted arm64 Mac running macOS 26.6.2. These are a separate
machine's results. Do not compare their absolute times with the local M3 runs.

The current default bytecode runtime improves substantially over the original in
this run. **The small flag differences are inconclusive:** native fork medians
vary by 10–39% within individual comparisons, and JVM dispersion is larger still.
None of these flag deltas justifies changing a default.

Each time is the median of three per-fork medians, in milliseconds per workload.
Every fork contains five validated two-second windows after warmup. Configurations
within each comparison rotate run order; the four comparisons themselves run
serially on the same hosted runner.

| Comparison | Baseline ms | Candidate ms | Change | Native ms |
| --- | ---: | ---: | ---: | ---: |
| Original bytecode → current default bytecode | 4.307904 | 2.808612 | −34.80% | 2.224470 |
| Checked storage: constructor class matching off → on | 2.833972 | 2.909767 | +2.67% | 2.054574 |
| Class matching on: checked → unchecked storage | 3.484686 | 3.538460 | +1.54% | 2.937570 |
| Checked storage, class matching off: normal → compact headers | 3.271727 | 3.166549 | −3.21% | 2.606251 |

Native fork spreads (`max / min − 1`) are 21.55%, 10.32%, 39.32%, and 11.62%
respectively. Class-matching candidate forks range from 2.613804 to 3.962988 ms.
The storage baseline ranges from 3.338520 to 6.057896 ms. Compact-header candidate
forks range from 3.162291 to 3.712726 ms, and its first fork's last window is 86.6%
slower than its first. These fluctuations remain in the raw results. Compilation
checks passing does not establish stable hardware performance or absence of host
contention. Power observations produced no warnings; that does not prove stability.

## Validation and provenance

All **180 windows** were independently revalidated from raw TSV and logs with the
recorded harness. All 24 JVM timing forks passed warmup, installed last-tier code,
source-note, instrumentation-off, zero-trap, and no measured/final-verification
compilation-event checks. Full Map checks passed all 18 inputs before and after
requested compilation in six configurations. Default and compact-header test
suites each passed **148 tests**, with no failures, errors, or skips.

Both runtime builds used GHC 9.14.1, GraalVM 25.3.4.1/JDK 25 and one current
exported Core/native corpus. Penalty-free recursion depth is 2 and both inlining
budgets are 12,000. Sources are enabled; unsupported paths trap explicitly.
Original runtime source is `4c116a8493eafad4077a1c5eb9c046bff56fe3c4`; current source
is `894648c26c99887b0227b70aab40758ce53dcd71`. All 19 original and 25 current
`src/main` files match those commits byte for byte. The candidate JAR SHA256 is
`4829e1da3c877c3fd985c2f4d1c3a8eba15a4b7e4199f3dbc158b1e4138c5afd`, consistent
across the immutable manifest and all timing configurations. JAR binaries were
not uploaded; build attribution comes from the successful source build, exact
source match, recorded commands, and the frozen hash chain.

[Audit](audit.json), [frozen manifest](immutable-manifest.json), [run configuration](run.json),
and [raw comparisons](comparisons/) preserve this evidence. [GitHub provenance](provenance/)
records artifact 10737792076. Its downloaded ZIP was independently hashed as
`79f073703781ec50e790e18df1dc5f3edfee3281bb361dfe9fa9158d062a6723`, matching
GitHub's digest. The archive is omitted here; extracted text evidence is retained.
The separate graph publication records the clean-host reparse of all 18 selected
compiler-graph phases; this directory does not duplicate the graph data.

## Compact-header structural result

The cold instrumentation probe confirms `UseCompactObjectHeaders=true`, compressed
object/class pointers, and 8-byte alignment. Actual factory samples measure:
`Bin` 40 bytes, `I#` 24, the Long-plus-two-reference capture 32, JVM `Long` 16,
and `Object[3]` / `[4]` / `[5]` at 24 / 32 / 32 bytes. These are shallow sizes,
not allocation counts or retained sizes. The probe executes no Map workload and
requests no guest compilation. [Exact output and commands](compact-object-sizes/)
are retained alongside the [normal-header v6 probe](../constructor-class/object-sizes/).
The optional lane succeeded; no rejected JVM option or omitted failure is hidden.

The archived [workflow and source helpers](tools/) reproduce this run's protocol.
Raw windows, process logs, native oracle rows, power observations, correctness
checks, and small JUnit reports are included. Compiled libraries/classes, binary
test output, large HTML reports, and duplicate full build logs are omitted.
[manifest.json](manifest.json) hashes every published file except itself.
