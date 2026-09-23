# Scalar mailbox experiment, revision 2

This isolated revision succeeds the indexed separate-result-carrier prototype; its immutable report remains in `../noninline-call-abi-review`. Main THC is unchanged by this work. Source base, full patch, incremental patch, and exact hashes are included here. The running copy is `/private/tmp/thc-handoff-slabs`; the measured source snapshot is `work/accepted-mailbox-v2`.

## Protocol change

The synchronous Long-only result path now uses a context/thread-local primitive return register. A callee computes its result into a primitive local, publishes it immediately before returning the private completion token, and the caller immediately reads the register into a primitive local. Nested and tail calls compose because no guest callback occurs between publication and consumption. The register is never saved/restored, never holds a reference, and is never read after exceptional completion. This does not implement tuples/sums: those still need independently owned multi-component destinations.

Input staging currently evaluates and forces all operands before acquiring transport storage. Root entry and ancestor reentry copy its fields into durable locals/snapshots and release it before guest execution resumes. Consequently at most one input loan is live per context/thread. A flat per-physical-layout cache replaces the depth-by-layout input table. An explicit active identity—not pending-null—guards acquisition/release; generation guards handle stale caller cleanup after tail reuse. Free reference fields are still cleared. No generated payload array, root retention, mutable exposed frame-arguments packet, or shared result object was introduced. Direct operand-to-carrier evaluation or reentrant callbacks during transport would invalidate this one-loan invariant and need a new ownership design.

The scalar flag is now constant zero for typed entries and minus one for ordinary entries. The source retains the two body branches, but the compiler folds this constant mode and produces one residual worker call site. The prior dynamic result index inhibited that simplification. The mandatory public Truffle Object[]/Object ABI still exists; the actual call uses the shared empty array, primitive results use the completion token, and ordinary outer entry boxes the final Long.

## Validation and exact cold-deoptimization finding

The scalar register alone with the old indexed input pool failed 2/183 existing compiled-entry assertions; values and the seven handoff tests passed. The flat mailbox recovered the ancestor test, leaving 182/183. The remaining captured-self case was a different failure from the earlier unconditional pool-null deopt: it compiled to 590 graph nodes / 3024 bytes. Its incoming remaining>=1 counted-loop speculation invalidated on the first postcompile zero-depth call. HotSpot explicitly recorded `profiledPERoot trap_bci=0 loop_limit_check make_not_entrant` in `work/scalar-mailbox-deoptimization.log`, line 33. The guard moved before the compiled-entry metric write, explaining the zero counter.

Diagnostic zero/one-depth calls across both captured environments, followed by explicit recompilation, kept the complete original 0/1/2/1000/1001 sequence valid and increased compiled entries from zero to 4010. The test now retains all cold value/capture/self-reentry/bounce checks, recompiles after the cold mix, and strengthens steady-state requirements: every depth in both environments must execute compiled code, and the target must remain last-tier valid. It does not require a speculative deoptimization to happen. Original failure XML, exact graph, and diagnostic logs remain preserved in `work/scalar-mailbox-first`, `work/scalar-mailbox-regression-graphs`, and `work/scalar-mailbox-deoptimization.log`.

The full enabled suite passes 183/183. Default control validation is recorded separately in validation-summary.json. This is an explicit reviewed strengthening of a profile-sensitive compilation test, not suppression of a semantic failure.

## Matched measurements

Same standalone harness and machine as revision 1: pinned Graal 25.3.4.1, compact headers, explicit last-tier worker/entry compilation, one shared host loop warmed for 1,000,000 calls, 9 samples of 50,000 top calls, two fresh JVM orders, wide values outside Long caches, instrumentation disabled. Every measured target remains last-tier valid. Typed runs allocate one input carrier during warmup, zero result carriers, and no further carrier growth. Reported bytes are exact across all samples; ns are each process's median and include host-loop cost.

| Workload | Control B/call | Mailbox B/call | Control ns (two orders) | Mailbox ns (two orders) |
|---|---:|---:|---:|---:|
| 12-deep scalar recursion, residual | 520 | 16 | 90.80 / 89.30 | 77.89 / 77.84 |
| Same recursion, guest inlining enabled | 224 | 16 | 27.58 / 28.00 | 74.66 / 76.31 |
| Trivial inline worker | 16 | 16 | 10.89 / 10.60 | 17.15 / 12.14 |
| 4 Long args, unchanged payloads, residual | 880 | 16 | 76.12 / 84.52 | 82.25 / 81.95 |
| 8 Long args, unchanged payloads, residual | 1152 | 16 | 108.08 / 107.73 | 101.26 / 104.93 |
| 4 Long args, freshly computed payloads per hop, residual | 1456 | 16 | 103.83 / 122.98 | 84.30 / 84.49 |
| 8 Long args, freshly computed payloads per hop, residual | 2496 | 16 | 188.28 / 204.94 | 104.00 / 107.71 |

This gives a repeatable residual scalar improvement in the small recursion and fresh-producer regimes, with approximately 45–47% lower time and 99.36% lower allocation in the eight-argument producer case. The remaining 16 bytes are the ordinary outer Long return. Four unchanged arguments show no stable improvement; eight unchanged arguments show only a small difference amid noise. Inlining remains a substantial regression, so there is no default rollout or automatic selective-inlining policy claim.

Graphs confirm each scalar/wide typed residual worker has one real callBoundary, zero late Object[] allocation sites and zero late Long allocation sites; the ordinary outer entry has one Long site. Controls retain one packet per worker/entry; update8 worker has ten late Long sites. Typed graphs retain one cold grow and one allocate boundary. The typed inline worker has no residual Java call, but its outer entry retains state traffic and cold capacity calls. These are synthetic scalar diagnostics, not Map or reference-write-barrier evidence.

## Limits

AST direct supported scalar-Long roots only; existing bytecode tests are differential legacy controls. Results with multiple components, reference-return transport, public debugging adapters, and bytecode handoff are not implemented. Generic/interpreter argument construction still stages Object[] before copying into dense fields; compiled measured paths eliminate it. Layout interning is per-language physical vector, mutable generated fields are identical in fallback, and class/cache lifetime remains context/thread bounded. The one-loan mailbox invariant depends on current staged operands. No public PE-time per-call decision tells this code whether a DirectCallNode will remain residual, so the feature stays opt-in and inline controls remain necessary.

Reproduction: restore public base commit `a53aee40c84c039cb12d1d17f018d00453e713ca`, replace src/compiler/scripts/tools with complete-source-overlay.tar.gz, and verify complete-overlay-manifest.json. This contains the exact frozen caller-demand source used in measurements, not main 2844d48’s later default-off gate. The handoff feature itself remains default-off. The overlay deliberately excludes generated build fixtures; use the repository preparation scripts for those. Both enabled and default suites pass 183/183.
