# Demand and inlining

Removing the remaining hot argument thunk in the Map example did not make the
workload faster. It exposed another recursive call to the inliner, and the
result depended on how the inliner treated that call.

The missed demand is in `insertWith.go`: the fourth argument to `balanceR_`
is a recursive insertion. GHC's demand signature permits the caller to evaluate
that argument before entering `balanceR_`, although the ordinary entry contract
does not promise that the argument arrives evaluated. These are different facts.
A hand audit found one such hot delayed argument among the 52 reachable bindings;
the other delayed argument sites belong to cold error paths.

There were three separate Linux experiments:

| Experiment | Control | Candidate | Native GHC |
| --- | ---: | ---: | ---: |
| Manual fourth-argument entry mark, Agnostic | 1.598564 ms | 1.754686 ms | 1.343481 ms |
| Same manual input, Agnostic to Default policy | 1.705624 ms | 1.591272 ms | 1.341866 ms |
| Structured caller-demand certificates, Default on both sides | 1.589148 ms | 1.593387 ms | 1.343328 ms |

The [manual probe](../bench/results/castlemeadow-manual-demand/) regressed by
9.77%. It changed only two copies of one entry-metadata bit and was a diagnostic
intervention, not the implementation to ship. The
[policy comparison](../bench/results/castlemeadow-demand-policy/) then held that
input and runtime fixed: Default took 6.70% less time than Agnostic. This does
not establish that one policy is generally better for Haskell.

The [caller-demand experiment](../bench/results/castlemeadow-caller-demand/)
uses a separate structured certificate on each application. Its control strips
only those certificates from the same Core, using the same runtime JAR. It does
not strengthen the callee's entry contract or claim that a lifted formal was
already in weak head normal form. The permission is used only when the call
itself is evaluated, and only when GHC's entire demand-signature arity is supplied.
PAP prefixes, absent arguments, coercion positions and `lazy` barriers remain
conservative. The prototype passed 176 tests and the full Map oracle on both
interpreter backends before the Linux comparison.

Across the 18-input correctness preflight, argument-thunk evaluations fell from
16,408 to zero; three unrelated top-level thunk evaluations remain. These are
instrumented correctness counters, not allocations counted during timing.
With Default held fixed, elapsed time was 0.267% higher in the candidate. That
is a small difference alongside the recorded variation, and supplies no
throughput win from the demand change.

The [matched allocation and actual graphs](../bench/results/castlemeadow-caller-diagnostics/)
show 6,742,507 to 6,705,107 allocated bytes per workload: 37,400 bytes less,
or 0.555%. Both actual worker graphs retain the hot insertion call inlined.
The selected worker loses all eight thunk/capture allocation pairs, while its
static packet and boxed-Long sites increase. Those site counts are not dynamic
allocation counts; the exact counter establishes the small overall reduction.

Caller-demand lowering remains opt-in with `-Dthc.callDemands=true`. Exported
proofs are retained and validated by default, while the existing evaluation
choices remain in place until the performance regression is resolved. The
[compiler documentation](../compiler/README.md) records the certificate and
comparison options. The final gated implementation passes 177 tests; a separate
[Linux release check](../bench/results/castlemeadow-caller-release-smoke/) passes
all 144 oracle results across both backends, both option settings and before/
after compilation. That check measures correctness, not another performance result.

Each row has its own serialized three-process comparison, with five two-second
windows per process. JVM warmup is at least 45 seconds and 30,000 workloads.
Both configurations use compact headers and class-owned layouts on the
same i9-12900K Linux host; source notes are enabled and timing instrumentation is
disabled. All 45 windows per row passed their result and compilation-event
checks. Full commands, hashes, preflights, raw windows and portable verifiers
are retained in the linked bundles. Rows are separate experiments, not a single
continuous comparison.

The demand information is useful, but fewer thunks alone do not settle the cost
of a call. The next experiment uses separate typed argument and result storage
across calls that remain after inlining. Allocation and compiled-graph evidence
for that transport must be measured independently.
