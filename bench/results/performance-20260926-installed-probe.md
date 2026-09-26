# Transaction-local installed probe reuse — 2026-09-26

Relative to public `f492fb68`, `prepareInstalledBundle` now keeps one successful
exact-inventory probe for its own transaction. The initial helper probe remains
fresh. A subsequent validation can reuse that result only after rediscovering
the complete registration closure and streaming SHA-256 over every raw
interface and the helper. Fresh probes have matching before/after snapshots;
both paths recheck registrations immediately before returning evidence.

State is discarded after the transaction. No individual row is reused across
different inventories: the helper validates retained-Core providers against
the complete request. There is no new persistent index schema or process-global
cache, no mtime-only shortcut, and no change to source-content checks, archive
validation, original Core, atomic publication, or ordinary-acquisition fallback.

## Verification

The strict development build passes all nine focused subprocess controls.
Fresh `thc-fixtures interface-core` production passes the 21 native rows and
all existing full/thin/no-source/identity/way/foreign/cache controls. Added real
interface checks establish exact reuse without another helper call, invalidation
on retained-Core or helper-byte changes, restoration, and isolation between
transactions. Every existing warm-cache control now asserts exactly one helper
probe and zero hydration. The expanded fixture records 23 loads and 35 probes,
including five additional direct memo controls.

Existing retained-Core/annotation/foreign/dependency mutations, initial-probe
mutation, omitted dependency/module inventories, corrupt ZIP/index, missing or
thin interfaces, source changes/absence, and failed-refresh preservation all
remain tested. Fresh default and dense forks also pass all 30 selected runtime
tests: `HandoffTest` and `InterfaceCoreNativeTest`, including original native
results, strict admission, first-installed AST/bytecode entry and retention.
No runtime code, compiler setting, or assertion was weakened.

## Complete warm bundle screen

The same two real interface-fixture modules, private registration database,
96,895,760-byte immutable helper, compiler, layout recipe and host were used.
Each fresh process first primed its own private bundle cache, then measured two
complete warm calls through production `prepareInstalledBundle`. Baseline and
candidate diagnostics were compiled against their respective driver sources
with the same exact Cabal package IDs. No installed compiler or shared cache
was modified; no containers/Pandoc application was recaptured.

Separate execve-only traced controls confirmed two helper probes per warm call
before, one after, and zero Core hydration in both cases. They used the actual
helper executable, not a lightweight wrapper that would understate hashing
cost. Traced timings are excluded below. Untraced fresh-process order was
baseline, candidate, candidate, baseline:

| Process | First warm validation seconds | Second warm validation seconds |
| --- | ---: | ---: |
| Baseline 1 | 7.092 | 7.485 |
| Candidate 1 | 4.674 | 4.640 |
| Candidate 2 | 4.737 | 4.721 |
| Baseline 2 | 6.954 | 6.860 |

Median warm validation was 7.023 → 4.697 seconds (33.1% lower). This is a
**shared-host screen, not a quiet throughput or whole-application startup
claim**. All six traced/untraced processes preserved the same original owner
and module inventory digest:
`ab32364e3a3d4a7c6dd237c40980c18368155f806c3090a3d4f58c702d5df1a5`.

Whole-process user+system CPU medians, including cache priming and both warm
calls, were 22.05 → 14.765 seconds. Parent-only allocation rose from 1.703 to
3.036 GB because of the extra streamed hashing. Parent maximum live data was
about 133.3 MB in both variants. Maximum-process RSS rose from 354 to roughly
450 MiB; this is a reported memory cost, not a memory reduction. This statistic
is not simultaneous process-tree RSS.

Parent-only allocation omits the eliminated helper work. Two separate profiles
of the exact 512-interface inventory helper both allocated 11,460,949,256 bytes,
with 123,072,584-byte maximum live data and identical response bytes. Thus each
avoided helper call removes substantially more allocation than the added parent
hashing, although this component comparison is not a measured whole-process-tree
allocation total. Larger parent RSS/fragmentation and existing lazy file-digest
retention remain separate follow-up targets.

## Isolated transaction cross-check

A separate frozen-input comparison uses the original 588-interface containers
closure without hydrating Core or reading bundles. Two consecutive fresh
probes took 8.178 and 9.853 seconds total in the two baseline processes; the
candidate transaction took 5.695 and 6.205 seconds. Its second validation alone
took 0.736 and 0.928 seconds. Raw-input and probe-result digests matched across
all runs. Host load varied, so the complete warm-bundle results above are the
more directly relevant screen, not an extrapolation from these phase timings.

The reproducible Haskell diagnostics are
[InstalledProbeTransaction.hs](../../tools/InstalledProbeTransaction.hs) and
[InstalledBundleProbe.hs](../../tools/InstalledBundleProbe.hs). The first uses
`-DEXACT_PROBE` only when compiling against the new transaction API. The second
accepts an execve trace for count controls or `-` for uninstrumented timing;
it checks original bundle identity on every warm call. An initial queued shell
harness was edited as it started and failed parsing before measurements. That
failed attempt was retained and excluded; the completed run used a frozen
script and new evidence directory.
