# Bounded installed-interface hydration — 2026-09-26

Relative to public `a5a54c6d`, cold acquisition now overlaps at most two helper
subprocesses. `THC_INSTALLED_CORE_JOBS=1` is a serial control; the configurable
bound is 1–64. This does not parallelize package publication, weaken validation,
or change the interface helper, Core schema, provenance, ZIP format or caches.

Responses are consumed in registered-module order. A slow early response bounds
both running and completed-but-unconsumed work. The first inventory-order failure
is retained, and failure or caller cancellation terminates outstanding helper
processes. Each successful payload is strictly serialized in its worker before
publication; decoded Aeson trees are not retained until package completion.
The final registration and cross-interface owner checks remain unchanged.

## Verification

`cabal test driver-tests -fdevelopment --test-options=--installed-hydration-only`
passed all seven focused subprocess controls: bounded work/backlog, ordered byte
identity, early missing Core, ordered failures, caller cancellation, changed
registration, invalid bounds and empty inventory. These test orchestration,
not executable Core semantics.

The existing `thc-fixtures interface-core` producer also passed, including its
21 native rows, full/thin interfaces, removed source targets, identities, ways,
foreign artifacts, original CBV metadata, and strict audits. Added direct checks
compare original serial/parallel Core bytes, owner and module order, and the
exact first missing interface. Existing cache controls passed unchanged: warm
reuse, corrupt ZIP/index, source changes/absence, missing/corrupt interfaces,
retained Core/foreign/annotation mutations, dependency mutations, post-probe
mutation, and omitted dependency/module inventories. Failed refresh preserves
the existing bundle. These controls observed 78 probe/load calls in a private
fixture cache; no shared package store or installed compiler was modified.

## Cold screen

The same small Haskell probe was compiled against the public serial
implementation and candidate, using the same GHC 9.14.1 Cabal-resolved package
IDs. It calls the production acquisition function directly, without a THC
bundle cache, ZIP work, audit, or guest execution. Every interface helper process
is fresh; OS file caches are warm and are not cleared.

Same immutable helper and installed `containers-0.8-inplace` interfaces on one
host. Other workers were active, so this is a **shared-host screen, not a
controlled throughput claim**. Three fresh pairs used opposite orders:

| Pair/order | Serial hydration seconds | Two workers seconds |
| --- | ---: | ---: |
| Serial, parallel | 19.862 | 19.099 |
| Parallel, serial | 21.127 | 18.655 |
| Serial, parallel | 20.186 | 18.416 |

Median hydration time was 20.186 → 18.655 seconds (7.6% lower). Whole-process
wall medians were 20.89 → 19.33 seconds; user+system CPU medians were
21.56 → 22.36 seconds. GNU time's maximum-process RSS was 2.89–2.92 GiB serial
and 2.87–2.93 GiB parallel. This RSS statistic is **not** the simultaneous sum
of parent and helper memory. More workers may raise actual concurrent memory.

All six runs returned the same 35 ordered modules and 80,987,478 payload bytes.
Canonical owner/module-size/module-SHA inventory digest:
`357ee5b5ea522fb10f34a33cffbf58a4197619d65e131f4a715389fff767a930`.
Input inventory and registration provenance digests also matched across all six
runs. No full compiler/Pandoc recapture was used for this screen.

The probe is [InstalledHydrationProbe.hs](../../tools/InstalledHydrationProbe.hs).
Its arguments are `GHC GHC_PKG THC_INTERFACE REGISTERED_UNIT`; compile the same
source against each revision's `THC.Driver.Installed` with that revision's exact
Cabal-resolved package IDs, and wrap each executable with the host time/resource
tool. An initial diagnostic build using ambiguous package names failed before
any measurement; it was retained and corrected to exact package IDs.

This modest overlap does not fix repeated warm inventory probes or the large
transient text transport cost. Both remain separate investigation targets.
