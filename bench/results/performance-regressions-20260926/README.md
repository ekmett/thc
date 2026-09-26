# September 26 performance regression investigation

The [controlled follow-up](controlled.md) records the later four-fork scalar
matrix, a second context-state lookup repair, and another preserved failure in
the unchanged warmed Map harness. The screening observations below remain the
historical first checkpoint; they are not relabeled as controlled measurements.

First tested checkpoint: repair bytecode async-poll graph expansion, retain the
failed baseline, and make benchmark launches match production's Vector module
configuration. Genuine Map bytecode execution now completes the checks that
previously failed with oversized installed code. No semantics or compilation
limits were weakened.

All elapsed times below are **screening observations**, not controlled speedup
claims. Other workers' builds and the Pandoc capture overlapped this campaign.
The compilation failures, native results and graph-size change are established;
rotated quiet-window throughput and startup measurements remain follow-up work.
The repaired runtime still fails a later warmed Map compilation-retention
check; this checkpoint is not a claim that steady-state Map is now healthy.

## Revisions and inputs

- Current baseline: `3f9e3c64fe7caf94f738018a106140f47e7f6251`.
- Pre-growth reference: `f72f924f6e521711e7c966430b680342587a90f5` (September 24).
- Candidate: this checkpoint's context-thread-local poll-cell repair, based
  exactly on the current baseline; no other worker's runtime changes included.
- Both built on eak-quartus, Linux x86-64, with GraalVM 25.3.4.1 / JDK 25.
- Scalar and Map Core were exported once with the current baseline and full-Core
  GHC 9.14.1. Both runtimes consume exactly these files and independently compiled
  native GHC expected results. Scalar Core totals 554,645 bytes.
- Map uses the genuine unmodified `containers-0.8` source and the existing
  diagnostic admission: three unresolved cold library bindings remain explicit
  traps. Native rows also pass the existing independent histogram model.
- Compact object headers, 4 GiB maximum JVM heap, 2 MiB stacks, source notes on,
  synchronous last-tier compilation, and existing graph/compilation limits.
  Default and dense handoffs and both backends are separate fresh JVM processes.
- Inherited `JAVA_TOOL_OPTIONS`, `THC_BACKEND`, `JAVA_OPTS`, `THC_OPTS`,
  `JDK_JAVA_OPTIONS`, `GHC_PACKAGE_PATH`, and `GHC_ENVIRONMENT` were cleared.

The reference predates most of the recent runtime features. It is a diagnostic
comparison point, not a proposed rollback. The lifecycle diagnostic calls each
runtime's actual context factory and `loadEntry` overload by reflection.

Frozen runtime JAR SHA256s:

```text
reference 5670b6562bc4ae643f2b00bcd3295defb782f0380976a241901efd0ec6e795ec
baseline  6d77c896ab6dc920cb32ad58a5913c4707a70f45139a466572a8425d22157da4
candidate 19be03fd3990e0daad78ace7fbf09e9291a0ba98f1b35514f7a651dba5b89eff
tools     531134aeba44dfb67ffd8a789013a00065485c800b67d97a7bfe3b88cdf45486
```

Scalar Core SHA256s:

```text
THC.Prim     3a2dac26323307618f0af46388c67aa65bd8872a2ee0259fdd47ee439f982e97
THC.Fixtures b0bf6db9210e09f0e9778fdc6ed1063c1d70834b2b559badffbfd5bbd55f376f
```

## Ranked findings

### 1. Async polling expands Java ThreadLocal maps into guest loops: repaired

The scalar workload is `sumLoop(10000) = 50005000` in both runtimes and native
GHC. Every lifecycle success checks the very first call after explicit
compilation against the native result and requires a compiled-entry increment.

| Scalar bytecode, default handoff | Reference | Baseline | Repaired |
| --- | ---: | ---: | ---: |
| Explicit compile screen | 0.144 s | 4.414 s | 0.165 s |
| Entire process screen | 0.78 s | 6.27 s | 1.00 s |
| Entry compiled code size | 1,132 bytes | 349,047 bytes | 6,546 bytes |
| Entry final Graal IR node count | 347 | 64,203 | 1,573 |
| Peak process RSS screen | 462.5 MiB | 499.1 MiB | 385.9 MiB |

These lifecycle runs all include `--add-modules=jdk.incubator.vector` and entry
instrumentation. Dense baseline compilation separately takes 4.590 s versus
0.163 s in the reference. Each is one fresh-process observation; graph capture
is a separate run and is never included in a timing comparison.

The AST control stays small: its current entry is 1,269 bytes, versus 1,310
bytes in the reference. This localizes the largest observed scalar regression
to bytecode compilation, independently of the frozen GHC Core.

The genuine Map workload has a larger consequence: baseline bytecode fails
during training with `Code installation failed: code is too large`, in both
default and dense handoffs. First useful result already takes 9.4–9.8 s in
these screens, followed by 41.8–44.8 s in the failed training phase. The
reference and repaired runtime complete their native-result and immediate
first-compiled-call checks. No throughput number exists for the failed
baseline. Its full failure logs and automatic Graal diagnostic ZIPs remain
retained; no code-size-limit increase, application retry or extra warmup turns
the failure into a pass.

The source cause is the ordinary `ThreadLocal.get()` in `GuestThreads.poll`.
Production bytecode enables async continuation cuts even in scalar loops.
Graal expands ThreadLocal initialization, stale-entry cleanup and map rehashing
into those cuts. In the independently captured scalar loop graph:

| After partial evaluation | Baseline | Repaired |
| --- | ---: | ---: |
| Nodes | 7,268 | 253 |
| Nodes whose serialized properties mention `java.lang.ThreadLocal` | 6,659 | 0 |

The repair uses a stable Truffle context-thread-local `PollState`, populated on
guest entry and cleared on final exit. The hot path reads its guest target and
the target's volatile pending flag. The existing locked `claim` boundary still
checks owner, delivery permission, masking, queue order and claimed requests.
The redundant delivery ThreadLocal lookup moves entirely behind that boundary.
Standalone registry tests retain their direct polling API. Weak carrier keys
and final-entry clearing avoid retaining a finished Java carrier through the
cell. Nested entries and separate contexts retain their existing identities.

The baseline detailed graph was captured with the original scalar launch
before correcting the Vector module flag; the repaired graph uses that flag.
The corrected baseline lifecycle compilation above independently retains the
same approximately 349 KB entry, so the source diagnosis does not depend on
the missing-module configuration. Scalar execution itself needs no Vector
operation.

### 2. Warmed Map compilation retention: still failing

The existing rotated throughput harness passed its first pre-growth reference
fork (median 1,663,110 ns/call, contention-affected). The repaired runtime
installed its guest entry and completed 12,032 varying-input warm calls with
the exact native checksum `678375231696`, taking 55.422 s. Its next existing
compilation/retention assertion then failed with `Guest code was not installed`
after an `opt reprof` of `lambda n`. No candidate measurement window ran, no
throughput ratio is valid, and later forks did not run. The original failure
and invalid comparison receipt are committed under `throughput-screen/`.

This is distinct from the repaired oversized-code failure during initial
training. Investigation must explain the loss of installed code without
weakening the retention assertion, extending warmup or adding retries.

Follow-up profiling reproduces the original failure with
`-XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation` and
`-Dpolyglot.engine.TraceCompilationDetails=true`. It records a non-permanent
`Compilable not ready for compilation` bailout after `opt reprof`. The pinned
runtime's `HotSpotOptimizedCallTarget.prepareForCompilation` bytecode shows
that this particular profile-reset path responds to **cold-method
invalidation**, rather than oversized code or an application result mismatch.
`lambda n` is inlined into `EntryRoot`, so its standalone code can be unused
even while that host entry executes. Whether the executed host entry remains
valid in the failing run is not yet established.

The new `thc.RetentionProbeKt` diagnostic inspects exact host/original/active
target identities, last-tier validity, code address and invalidation reason.
It uses the real public loader and checks all 12,032 calls against a native
16-input cycle. Both sampled and endpoint-only diagnostic runs pass, including
an instrumented control whose first compiled call increments the counter.
Their host targets remain installed with unchanged code addresses. These
passes **do not clear the original failure**: target inspection calls
HotSpot's `updateHotSpotNmethod`, changes code reachability/observation, and
can perturb the cold-code behavior under investigation. The diagnostic
defaults to endpoint-only inspection; periodic sampling is an explicit
`-Dthc.retentionProbe.sampleTargets=true` control. No runtime workaround or
relaxed benchmark assertion was applied.

The profile log and endpoint records are under `retention-diagnostic/`; the
21 MB HotSpot compilation XML and `javap` captures stay in the raw directory.
The follow-up tools JAR hash is
`ecc14d632966731fe6cd1f8f19eb4cde4a46addfcc977b2186c5df9b41a88c85`.

### 3. Large extraction/audit memory and time: retained evidence, separate owner

The Pandoc owner's prior genuine capture produced 154 units, 2,508 modules and
153 bundles: 522,486,482 compressed bytes, approximately 24.3 GB expanded Core.
The old combined acquisition/replay/audit command ran 3,872.898 s before an
audit serialization OOM; that combined time cannot be attributed to extraction
alone. A bounded old audit took 399.33 s and peaked at 60,063,172 KiB before
`json.dumps` exhausted its bound. The owner's streamed-report repair then
completed the audit in 383 s with 49,915,648 KiB peak RSS and an 18,966,696,406
byte report. Strict rejection remained visible (563 missing globals, 1,318
issues); no guest was launched. These costs are much larger than the scalar
export screens, but do not establish a before/after extraction regression.

This worker has not repeated full Pandoc acquisition, parsed the 18 GB report
into memory, or edited its owner's replay/export changes. Exact receipts are
in the sibling `pandoc-proxy-resume-01a0cdeb/build/PROXY-PANDOC-RECEIPT.md`;
the evidence is the `f1692d20` capture and streamed audit, not its ongoing
third capture at `caac6970`. Acquisition, serialization, archive handling and
audit need separate future attribution before optimizing this pipeline.

### 4. Loading and allocation: measurable remaining costs, not repaired here

Default scalar bytecode loading is 0.174 s / 31.0 MB of calling-thread allocation
in the reference, 0.323 s / 48.0 MB in the baseline and 0.300 s / 48.1 MB after
the poll repair. Map loading is 1.273 s / 1.481 GB in the reference, 1.478 s /
1.510 GB in the baseline and 1.382 s / 1.485 GB after repair. These are individual
screens: smaller time/allocation deltas need repetition and source attribution.
The poll change does not purport to fix loader allocation. Phase allocations
exclude Graal compiler threads; phase heap occupancy is not retained heap size.

The separate endpoint diagnostic records 79,761,059,104 calling-thread bytes
for 12,032 warmed native-checked reference calls, versus 95,089,286,840 repaired
current bytes: approximately **6.629 MB versus 7.903 MB per call**. These
totals include the small endpoint JSON/reflection overhead, exclude compiler
threads, and are not a timing benchmark. During those intervals the whole JVM
records 145 / 200 collections and 112 / 146 ms of collection time respectively.
The approximately 1.274 MB/call additional allocation needs source attribution;
the poll repair has not removed it. The instrumented control is separate and
must not substitute for the uninstrumented allocation comparison.

### Benchmark launch correction

The original benchmark scripts omitted `--add-modules=jdk.incubator.vector`,
which production's generated launcher already supplies. Current Map AST
compilation exposed this as `NoClassDefFoundError` in `VectorLayout.read`.
Repeating the whole baseline/reference matrix with the production flag makes
AST pass, while bytecode's oversized-code failure remains. The committed
screens use that corrected flag. Earlier missing-module failures remain under
`build/performance-regressions/screens/`, not silently replaced by a passing
run. The benchmark, Map comparison, graph and library-check commands now carry
the production flag.

## Semantic checks

Both default and dense handoffs pass:

- `GuestThreadsTest`: 14 tests, including the new nested-entry/context-isolation
  poll-cell lifetime check; `CallMaskSegmentsTest`: 7; `HostEntryCompilationTest`: 2.
- `LiveAsyncNativeTest`: 4; `ThreadAsyncNativeTest`: 10;
  `AsyncStrictEntryNativeTest`: 1. Fresh Haskell native/Core fixtures were built.
  These cover delivery into running compiled loops, repeated interruptions,
  shared thunk resumption, blocking MVars, masks and foreign callback permission.
- Genuine Map: all 18 native rows before and after explicit compilation, on
  AST and bytecode; zero unsupported traps. The separate phase diagnostic
  checks the first call after compilation and its compiled-entry increment.

That is 76 distinct focused test executions across the two modes, plus the
four Map configurations. Existing comparison-harness tests pass (9 cases),
edited shell scripts pass `bash -n`, and `git diff --check` is clean.
Logs and native-result rows are retained under `checks/` and `screen-candidate/`.

## Preparation measurements and limits

Fresh worktree plugin build plus scalar export: 8.43 s, 7.41 s user CPU, 0.82 s
system CPU, 542 MiB peak RSS. Repeating the same forced scalar export with the
built plugin takes 0.82 s and 198 MiB peak RSS. The old worktree's corresponding
screens are 2.86 s / 437 MiB and 0.58 s / 201 MiB. Source changes in these scalar
fixtures are license comments; exported metadata and plugin revisions differ.
The old two-module export totals 570,337 bytes, versus 554,645 current bytes.
These distinguish a cold local plugin build cache from a warm one; shared
Cabal/toolchain and filesystem caches were not cleared. Map source/export/audit/native preparation with preexisting
verified source downloads takes 22.54 s and 642 MiB peak RSS. A first attempt
failed fetching absent boot sources inside the network sandbox; that failure is
retained, and pinned local source copies supplied the successful run.

Gradle build wall time was recorded, but GNU time around its launcher does not
account accurately for detached Gradle/Kotlin daemon CPU or peak memory. Those
numbers must not be reported as total JVM build cost.

Controlled rotated fresh-process runs will follow in a coordinated timing
window with recorded host load. Pandoc's third full capture started at 11:39
local time and stays unpaused. The reference/repaired/native Map throughput
screen using the existing nine-process rotated harness stopped at the repaired
runtime's retention failure described above. It checks every 16-input checksum
and rejects compilation or deoptimization during measurement. Warmed
allocation/GC and residual throughput costs remain distinct from the proven
graph/initial-compilation repair. The endpoint allocation diagnostic above
provides an initial warmed allocation observation, not a completed throughput
or allocation optimization campaign.

## Reproduction and raw evidence

The new `thc.PhaseProbeKt` diagnostic is built by `./gradlew toolsJar`. It prints
JSON lines for context creation, loading, first result, 200 checked training
calls, explicit compilation, first compiled result, and context close. Each
phase records calling-thread allocation, heap occupancy and GC observations.
The surrounding `/usr/bin/time -v` records process wall/CPU time and peak RSS.
Throughput is measured separately with the existing `thc.ProbeKt`.

Example, after exporting `examples/THC/Fixtures.hs` and building the native oracle:

```sh
java --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xss2m -Xmx4g \
  -XX:+UseCompactObjectHeaders -Dthc.traceCompilation=true \
  -Dthc.backend=bytecode -Dthc.handoffSlabs=false \
  -cp 'build/install/thc/lib/*:build/diagnostics/thc-tools.jar' \
  thc.PhaseProbeKt build/core/THC.Prim.json,build/core/THC.Fixtures.json \
  sumLoop 10000 50005000
```

Raw commands, build logs, frozen baseline JARs and hashes, lifecycle JSON lines,
native outputs and GNU time records live under
`/home/ekmett/ai/agents/worktrees/thc/performance-regressions-01a0cdeb/build/performance-regressions/`.
Automatic compilation failure artifacts are under the same worktree's
`graal_dumps/`. The baseline is immutable; subsequent diagnostic tools and
candidate runtime builds do not overwrite that frozen distribution.

`screen-baseline/` contains the corrected-module matrix (including failing
JSON phase records); `screen-candidate/` contains repaired Map phases and
native checks plus the combined scalar output/time log. Large baseline failure
logs are `screens-vector/map-bytecode-{false,true}-baseline.log` in the raw
directory. The separate loop graph files are under
`graphs-baseline/parsed-TruffleHotSpotCompilation-2595[lambda_x,_acc]/graph-00000.json`
and `graphs-candidate/parsed-TruffleHotSpotCompilation-2592[lambda_x,_acc]/graph-00000.json`.

Focused test commands (same selection for `testDefault` and `testDense`):

```sh
./gradlew --offline --no-daemon --max-workers=2 installDist toolsJar \
  testDefault --tests thc.runtime.GuestThreadsTest \
  --tests thc.runtime.CallMaskSegmentsTest --tests thc.runtime.HostEntryCompilationTest \
  testDense --tests thc.runtime.GuestThreadsTest \
  --tests thc.runtime.CallMaskSegmentsTest --tests thc.runtime.HostEntryCompilationTest
cabal run thc-fixtures --offline -- live-async
cabal run thc-fixtures --offline -- thread-async
./gradlew --offline --no-daemon --max-workers=2 \
  testDefault --tests thc.runtime.LiveAsyncNativeTest \
  --tests thc.runtime.ThreadAsyncNativeTest --tests thc.runtime.AsyncStrictEntryNativeTest \
  testDense --tests thc.runtime.LiveAsyncNativeTest \
  --tests thc.runtime.ThreadAsyncNativeTest --tests thc.runtime.AsyncStrictEntryNativeTest
```

Heavy commands use the shared `tools/resource_run.py --build-dir OWNED_BUILD`
gate. Gradle requires ordinary local socket access; a restricted first launch
failed opening its wildcard lock socket and that log is retained as an
environment failure, not a test result.

The target-state diagnostic uses the 16 native rows retained by the existing
comparison harness, with the same runtime flags as the lifecycle probe:

```sh
java --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
  -Xss2m -Xmx4g -XX:+UseCompactObjectHeaders -Dthc.traceCompilation=true \
  -Dthc.backend=bytecode -Dthc.handoffSlabs=false -Dthc.diagnosticUnsupported=true \
  -cp 'build/install/thc/lib/*:build/diagnostics/thc-tools.jar' \
  thc.RetentionProbeKt build/map/modules.txt mapAggregate \
  bench/results/performance-regressions-20260926/throughput-screen/oracle.tsv
```

Run once with `-Dthc.retentionProbe.instrument=true` for the separately labeled
counter control. Private-field/reflection inspection is diagnostic-only and
specific to the pinned runtime, not part of the guest language API.
