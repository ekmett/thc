# Controlled scalar follow-up and remaining Map failures

The bytecode compilation repair is repeatable: four fresh-process default-mode
forks reduce median explicit compilation from 3.838 s to 0.158 s. The first
useful result remains approximately 61 ms. AST is essentially unchanged.
Map's immediate native/compiled checks pass in both backends and handoff modes,
but a new quiet-window throughput campaign still fails its second candidate
fork's compilation-retention assertion. There is no completed Map throughput
comparison and no claim that Map's remaining allocation regression is fixed.

## Candidate and method

This checkpoint builds on `92b26351` (the poll repair is `97c17adf`) and also
moves masking/annotation reads onto Truffle context-thread-local cells. It is
based on exactly `3f9e3c64fe7caf94f738018a106140f47e7f6251`; no concurrent
worker's source was included. Frozen JAR SHA256s for this follow-up:

```text
runtime c64789bc1edcece902b5e5d9532005c75fd467f5b03b1ae12b664eebf8f6e0a4
tools   ecc14d632966731fe6cd1f8f19eb4cde4a46addfcc977b2186c5df9b41a88c85
```

All other tools, input Core, native binaries and baseline/reference identities
are those in [the initial report](README.md). Source notes and asynchronous
delivery remain enabled. No compilation limits, checks, retry policies or
warmup thresholds changed. The scalar baseline here is **current pre-repair
`3f9e3c6`**; the Map baseline is **pre-growth `f72f924f`**, since current
pre-repair Map bytecode cannot install its oversized compiled code.

The native-image worker was paused; the Pandoc owner's third full capture had
ended at its native digest/erf call gates, and its last focused tests ended
before this window. Four scalar forks ran serially from 12:12:56 through
12:14:01 EDT on September 26, rotating baseline/candidate order in alternating
forks, separately for AST/bytecode and default/dense handoffs. Host process,
load-average and CPU-counter snapshots bracket every process in the retained
local `build/performance-regressions/controlled-scalar/*.host-*` files. Load
averages at the endpoints were `1.39 1.87 1.91` and `2.02 1.94 1.93`; idle
resident Gradle/Kotlin daemons were not counted as active builds. This is a
coordinated quiet window, not a dedicated isolated benchmark machine.

The lifecycle probe is instrumented, validates every result against native GHC,
and checks the very first post-compilation result plus compiled-entry increment.
It does not substitute a warmed throughput measurement for first-call latency.
All 32 processes passed; compact phase JSON, compilation traces, GNU time
records and status are in `controlled-scalar/`.

## Scalar results

Each cell is the median of four independent processes; brackets are min–max.
Times are milliseconds. `load` includes the actual public loader/lowering, not
only JSON parsing. `compile` follows the same 200 checked training calls.

| Backend / handoff | Revision | Load | First useful result | Training 200 | Explicit compile |
| --- | --- | ---: | ---: | ---: | ---: |
| Bytecode / default | Baseline | 305.10 [297.36–311.03] | 61.30 [59.85–64.15] | 1013.50 [1012.86–1021.92] | 3838.36 [3806.10–3857.09] |
| Bytecode / default | Candidate | 296.64 [294.51–298.78] | 61.02 [60.92–61.31] | 186.32 [184.73–193.51] | 158.26 [157.56–160.96] |
| Bytecode / dense | Baseline | 298.03 [296.47–301.28] | 60.25 [59.36–65.33] | 1011.80 [995.85–1037.83] | 3801.85 [3784.74–3846.58] |
| Bytecode / dense | Candidate | 298.57 [296.66–301.58] | 60.27 [59.21–61.80] | 193.84 [188.36–196.85] | 161.98 [160.75–165.11] |
| AST / default | Baseline | 218.11 [215.09–234.49] | 26.24 [25.83–27.62] | 107.33 [102.39–117.59] | 64.85 [64.37–65.18] |
| AST / default | Candidate | 214.99 [214.20–215.47] | 25.99 [25.60–27.25] | 105.38 [101.62–109.06] | 64.19 [63.86–64.48] |
| AST / dense | Baseline | 221.49 [220.57–221.96] | 26.60 [25.08–29.00] | 110.48 [109.29–113.17] | 88.51 [88.07–89.87] |
| AST / dense | Candidate | 218.29 [215.76–221.90] | 25.70 [24.80–26.27] | 113.41 [108.59–116.93] | 88.50 [87.61–92.21] |

Context creation is 167–181 ms across the matrix. Default bytecode whole-process
wall medians are 5.51 s versus 0.995 s; RSS medians are 704.1 versus 437.4 MiB
(ranges 589.1–721.4 versus 413.0–452.1 MiB). Dense bytecode process medians are
5.455 versus 1.015 s, RSS 625.5 versus 427.7 MiB. These process RSS figures
include the JVM/compiler and are not retained guest-heap sizes. The default
first compiled call is 180 versus 88 microseconds, but a single instrumented
call per fork is not a steady-state throughput result.

Default bytecode load allocation remains approximately 48.01 versus 48.15 MB
on the calling thread, about 0.14 MB more in the candidate. The repair is not
a loader-allocation optimization. Compiler-thread allocation is not counted by
the calling-thread metric. The scalar candidate entry is 6,571 code bytes /
1,650 final IR nodes in the first fork, versus the pre-repair approximately
349 KB / 64,203 nodes. This controlled comparison measures both repairs
together; it does not attribute all improvement to the later masking change.

## Second source cause: boundary thread-local state readers

A separate JFR profile of the poll-only candidate found 6,915 main-thread
execution samples after initial entry compilation plus a two-second exclusion.
`ThreadLocalMap.getEntryAfterMiss` was the top frame in 2,675 (38.7%),
`SynchronousMasking.current` in 764 (11.0%) and `StackAnnotations.current` in
210 (3.0%). Bytecode root-entry snapshots and application-result continuation
checks repeatedly crossed boundaries to read Java thread-local maps.

`CarrierLocal` now shares one mutable cell between the existing boundary
setters/registry API and Truffle's context-thread-local fast reader. The
factory accepts its explicit carrier, including initialization from another
thread. Weak keys and weak cell references avoid registry ownership of dead
carriers; live Java/Truffle thread storage owns the cell. `remove()` resets
the existing cell rather than leaving a stale value visible to compiled code.
Neither masking nor stack annotations are omitted, cached as constants, or
disabled. This does not change the separate volatile pending-exception poll.

The same separate JFR capture on this candidate has 4,322 post-compilation
main-thread execution samples: no top frames in Java `ThreadLocal`,
`SynchronousMasking.current` or `StackAnnotations.current`. The new largest
named groups include the generated bytecode loop (1,835), `GuestThreads.poll`
(1,305) and `pollCurrent` (499). Thus the targeted lookup hotspot disappears,
while asynchronous-poll/continuation overhead remains a visible next target.
Sampling is not a controlled wall-time attribution or permission to remove
those semantic checks. All three profiles use JFR `settings=profile` and
`jdk.ExecutionSample`/`jdk.ObjectAllocationSample`, filtered to `main` after
the first `EntryRoot` compilation timestamp plus two seconds.

Allocation sampling also found substantial residual boxing and argument arrays:
poll-only sampled weight was 14.2% `Long`, 22.6% `Object[]`, versus 7.0% and
19.9% in the reference. Many samples have `CurrentMask.read` as the innermost
recorded guest frame, but an inlined allocation's recorded boundary is not proof
that this reader constructs those objects. These profiles locate investigation
targets; sample weights are not exact allocation totals. Raw JFR and decoded
events remain local under `build/performance-regressions/map-allocation-*`.

## Map retention remains a failure

The unchanged `compare-map-runtimes.py` ran the reference, candidate and native
binary in that order for fork 1, then began candidate fork 2 as its normal
rotation. Fork 1 passed the original entry compile, 12,032-or-more warm calls,
all five two-second measurement checksums, final compilation-retention check,
and no compilation/deoptimization inside the measured region. Its per-call
sample median was 1.601 ms reference, 2.852 ms candidate, 1.344 ms native.
These are **one successful fork of an incomplete campaign**, not a published
three-fork speed ratio. They show that residual Map cost warrants investigation.

Candidate fork 2 produced 12,032 correct warm results in 34.866 s with checksum
678375231696. The immediately following unchanged `compile` assertion failed:
`lambda n` logged `opt reprof`, followed by `Guest code was not installed`.
This matches the retained poll-only cold-method failure. The harness stopped;
there was no retry, settling phase, removed assertion or selectively discarded
fork. All output, configuration and the failed campaign log are committed in
`controlled-map/`. The failed run has no measured samples.

The private-target endpoint diagnostic and JFR profiles remain separate from
this result: their inspection/profiling can change cold-code behavior. A pass
in those diagnostic modes cannot establish retention for the unchanged harness.

The later endpoint-only candidate diagnostic provides stronger target evidence:
after 12,032 checked calls, `EntryRoot` is still last-tier valid at its original
address, while the standalone original `lambda n` is invalid, address zero,
invalidation reason 6, with its call count still 200. This supports cold
reclamation of the standalone body after inlining rather than loss of the
executed host-entry code. The original failed endpoint log also exposes a
diagnostic bug: its final private `getInvalidationReason` inspection rejects
the reset generic `InstalledCode`, masking the compile exception. The diagnostic
now records that inspection error as data instead of replacing the original
failure. The original failed record is retained, not rewritten.

A fresh run with that diagnostic-only correction reproduces the same original
cold-invalid/host-valid state and now preserves the actual `Guest code was not
installed` exception. Its final snapshot shows the original target's counters
reset to zero while the host target stays valid at its initial address. These
`retention-endpoint-fast-state-v2` records are committed beside the first run.
This is a new diagnostic run, not a retry that converts the benchmark to a pass.

Allocation over those 12,032 calls is 95,088,905,560 calling-thread bytes,
approximately 7,903,001 bytes/call: essentially unchanged from the poll-only
7,903,032 and still above reference 6,629,077. There were 496 GC collections /
349 ms recorded in that separate run (whole-JVM scope, sensitive to heap
ergonomics and observation); these are not quiet-window throughput samples.
The masking-read change is therefore a CPU-lookup repair, **not** an allocation
repair. The sampled `Object[]`/`Long` shares on the final candidate remain
26.8%/12.8%; no exact allocation win is inferred from those changing shares.

## Semantic validation

Both default and dense Gradle tasks passed all 53 selected tests each (106
executions): carrier cells, guest threads, stack annotations, mask host unwind,
call-mask segments, native delimited continuations, native masking, live async,
thread async, strict-entry async and host-entry compilation. Tests retain
native oracles, immediate first-compiled-call checks, nested masks, carrier and
context isolation, child threads and compiled asynchronous delivery. The new
tests cover adapter/cell updates, reset, and factory initialization for another
carrier. All four Map backend/handoff configurations independently passed all
18 native rows and the lifecycle probe's immediate compiled check.

An earlier test invocation failed two delimited-continuation tests because its
native fixture manifest had not yet been built. That environment failure is
retained in `fast-state-checks/missing-fixture-failure.log`; existing
`thc-fixtures` prepared the original fixtures and the full selected matrix then
passed. The successful log and four Map validations are in `fast-state-checks/`.

## Reproduction and retained limitations

Use the initial report's environment and resource gate. The scalar matrix uses
four fresh processes per configuration, baseline first in forks 1/3 and
candidate first in forks 2/4, with the following command wrapped in
`/usr/bin/time -v`:

```sh
java --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
  -Xss2m -Xmx4g -XX:+UseCompactObjectHeaders -Dthc.traceCompilation=true \
  -Dthc.backend=bytecode -Dthc.handoffSlabs=false \
  -cp 'FROZEN_RUNTIME_LIB/*:build/performance-regressions/fast-state/thc-tools.jar' \
  thc.PhaseProbeKt build/core/THC.Prim.json,build/core/THC.Fixtures.json \
  sumLoop 10000 50005000
```

The failed Map campaign command (all other options are existing defaults):

```sh
python3 tools/compare-map-runtimes.py \
  work/performance-reference/build/install/thc/lib \
  build/performance-regressions/fast-state/lib \
  build/map/native/native-oracle build/map/modules.txt \
  build/performance-regressions/controlled-map \
  --candidate-tools-jar build/performance-regressions/fast-state/thc-tools.jar \
  --baseline-commit f72f924f6e521711e7c966430b680342587a90f5 \
  --baseline-backend bytecode --candidate-backend bytecode \
  --baseline-jvm-option=-Xmx4g --candidate-jvm-option=-Xmx4g \
  --baseline-jvm-option=-Dthc.handoffSlabs=false \
  --candidate-jvm-option=-Dthc.handoffSlabs=false
```

Profiles run separately with the same frozen runtime flags and
`-XX:StartFlightRecording=filename=OUTPUT.jfr,settings=profile,dumponexit=true`,
`-Dthc.minimumWarmCalls=12000`, using `thc.ProbeKt MODULES_COMMA mapAggregate
--steady 15 2 5 10000`. Decode with `jfr print --json --events
jdk.ObjectAllocationSample,jdk.ExecutionSample OUTPUT.jfr`; use the main-thread
and timestamp filter described above. Retained JFR SHA256s:

```text
reference  c35f73f1c16fa767fca7c7e641d9204969779ec2e246c788f08b1ac3106f0ac2
poll-only  ab56fda7a23b3c3a0f6e8266c3101a328d914f476152a55de6630f62832fb667
fast-state d102a55b84dea106933d7b54b75134d062fd01b8ab33f2c17905cfda7c83e36f
```

The controlled timing gate log is `20260926-121147-e50qzfso/output.log` in
Cult's `build-agent-logs`. The final 106-test log is
`20260926-120820-184p31qd/output.log`. The frozen diagnostic JAR digest is
`ecc14d632966731fe6cd1f8f19eb4cde4a46addfcc977b2186c5df9b41a88c85`.
The subsequent diagnostic-error-reporting-only rebuild has tools JAR digest
`5e1adb1e279d5895165c3cb55c125e072d05b1396e5bf4c2f08972c52f76a6e2`;
its build/reproduction gate is `20260926-122030-f6wn7_rt/output.log`.

Remaining ranked work is (1) determine a correct original-versus-inlined target
retention contract without weakening first-compiled assertions; (2) attribute
the roughly 1.27 MB/call post-growth Map allocation excess and residual runtime
cost; (3) separately measure loader/export/audit costs under quiet conditions.
Dense/AST warmed Map and additional real libraries are not yet characterized.
No second full Pandoc capture was launched by this worker; existing owned
capture evidence remains the source for full-package extraction/audit costs.
