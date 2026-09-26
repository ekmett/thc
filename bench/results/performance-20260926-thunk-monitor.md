# Thunk monitor footprint — 2026-09-26

This is a small allocation cleanup, not a resolution of the larger warmed Map
regression. The only runtime change relative to `16d4aafe` is using each internal
`Thunk` as its own monitor instead of allocating another object. `CallSegment`
is unchanged. All claim, publication, wait/notify, exception and continuation
state transitions still use the same per-thunk `monitor` accessor.

The thunk is not a host-interoperable object. Public scalar entry arguments are
integers; the exported entry object does not expose its underlying thunk.
Closure inspection exposes guest payload references, not the monitor. The
runtime has no guest Java-monitor operation. Host code deliberately reflecting
into internal runtime objects is outside this interface.

## Allocation, not a timing claim

Same host, frozen Map Core/native oracle, GraalVM 25.3.4.1 / JDK 25.0.4.1,
bytecode/default handoff, 4 GiB heap, 2 MiB stack, compact object headers.
Other workers were active: no controlled wall-time or throughput claim is made.

Two fresh pairs ran in opposite order. Each used the same diagnostic binary,
200 training calls, explicit compilation, then 12,032 native-checked calls over
the same sixteen-input cycle. Calling-thread allocation includes the existing
endpoint snapshots; it excludes compiler-worker allocations.

| Run order | Baseline bytes/call | Self-monitor bytes/call |
| --- | ---: | ---: |
| Baseline, candidate | 7,903,000.746 | 7,894,904.399 |
| Candidate, baseline | 7,903,000.606 | 7,894,904.735 |

The reduction is approximately **8,096 bytes/call (0.102%)**. The instrumented
10,000-input workload evaluates 1,012 fresh thunks per call, consistent with
saving one eight-byte compact-header monitor per thunk.

The existing Instrumentation shallow-size agent confirms the footprint:

| Header mode | Baseline thunk + separate monitor | Self-monitor thunk |
| --- | ---: | ---: |
| Compact | 32 + 8 = 40 bytes | 32 bytes |
| Ordinary | 40 + 16 = 56 bytes | 32 bytes |

`javap` confirms no monitor field or additional `Object` construction remains.
These are shallow sizes, not retained-heap or allocation-frequency estimates.

One cold phase pair measured load allocation 1,485,262,320 → 1,484,641,752 bytes
and first-result allocation 26,486,832 → 26,495,968 bytes. These single-process
startup samples are not evidence of a startup improvement. Allocation for the
first compiled result was 7,919,624 → 7,911,528 bytes, again an 8,096-byte saving.

## Verification and limits

- 75 focused tests per handoff mode passed: thunk dispatch/retention, real
  concurrent readers, owner blackholes, guest/host/async failures, saved and
  cross-thread continuations, masks, carrier-local state, and STM boundaries.
- The added test consumes three spurious notifications while the evaluator is
  blocked, checks the owner/state/empty answer, then observes exactly one
  publication to both threads. Monitor identity is stable and per-thunk.
- Fresh boxed-array and floating native fixtures passed the unchanged
  first-compiled-call/NaN retention checks; closure-image state checks also
  passed. These add three tests per mode, for **156 total test passes**.
- Frozen Map's eighteen oracle rows passed before and after explicit compilation
  on both backends and both handoff modes. All four exact first-compiled-call
  phase assertions passed, without a settling call or weaker counters.
- The first long-run pair failed the existing final retained-code assertion
  for both revisions after all native results passed. Both reversed-order runs
  passed that same assertion. This variation does **not** establish a retention
  fix; the original failures remain recorded.
- An initial MapCheck invocation used an older diagnostic ABI and failed before
  guest execution. Rebuilding that diagnostic against the current runtime fixed
  the linkage; the complete four-mode run then passed. Failed evidence was kept.

Baseline runtime SHA-256:
`d6f17cbcced2596250865beff08b0422e8a7ef93738b349f6fba787e2407287a`.
Candidate runtime SHA-256:
`9736f45bfaa7e990f97853a50077ff3bcf6c945fa3398c0d9c3f6e3b0f572ee0`.
