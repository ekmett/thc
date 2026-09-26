# Installed Core byte transport — 2026-09-26

Follow-up to [bounded hydration](performance-20260926-installed-hydration.md),
using `d2752553` as the two-worker text-transport control and public `a5a54c6d`
as the original serial control. Full-Core helper stdout now stays a strict
`ByteString`, rather than becoming a linked-list `String` and then being
UTF-8 encoded again for Aeson. The helper command, JSON protocol, validation,
Core serialization, registration checks and cache format are unchanged.

The subprocess bracket retains empty stdin, the existing 180-second timeout,
child termination/reaping, and concurrent stderr draining. Both output streams
still reject invalid UTF-8, even when the helper exits successfully. Only the
bounded diagnostic failure path turns bytes into text.

## Frozen-input allocation screen

The same diagnostic probe, exact Cabal package IDs, helper and original
`containers-0.8-inplace` interfaces were used throughout. Eight fresh processes
ran in this order: serial text, two-worker text, serial bytes, two-worker bytes,
then the reverse order. OS file caches were warm. Other workers were active:
wall times are a shared-host screen, not a quiet throughput claim.

Each process returned the same 35 modules and 80,987,478 serialized bytes, with
identical ordered owner/module-size/module-SHA inventory digest:
`357ee5b5ea522fb10f34a33cffbf58a4197619d65e131f4a715389fff767a930`.
Input inventory, helper and registration provenance digests also matched.

Parent-driver RTS statistics below are medians of the two runs per variant.
GB and MB are decimal. Allocation includes probe setup, input hashing and
result hashing, identically in all variants; helper allocations are separate.

| Transport / workers | Allocated GB | GC-copied GB | Maximum live MB | Parent CPU seconds | Hydration seconds |
| --- | ---: | ---: | ---: | ---: | ---: |
| Text / 1 | 9.473 | 6.810 | 1248.8 | 5.386 | 21.556 |
| Bytes / 1 | 6.072 | 2.362 | 318.2 | 2.479 | 18.255 |
| Text / 2 | 9.474 | 6.479 | 1095.1 | 4.469 | 17.452 |
| Bytes / 2 | 6.072 | 2.238 | 304.0 | 2.493 | 16.883 |

The two-worker comparison removes 3.403 GB of parent allocation (35.9%) and
reduces maximum live data by about 72%. Parent CPU falls by 44% in this screen;
hydration wall time falls only 3.3%, since fresh helper work remains dominant
and overlaps parent processing. The serial byte control confirms that the
allocation reduction comes from transport, not additional subprocess overlap.

GNU time's maximum-process RSS remains about 2.87–2.93 GiB across all variants,
dominated by helper work. That statistic is neither parent-only RSS nor the
simultaneous sum of parent and children. Reduced parent live data must not be
reported as a comparable reduction in total acquisition peak RSS.

## Verification

All nine focused subprocess controls pass under `-fdevelopment`, including
the existing ordered-failure, cancellation and bounded-backlog tests. Added
controls cover Unicode payloads, EOF stdin, a concurrent 1 MiB stderr stream,
and rejection of malformed UTF-8 on either stdout or stderr. These are process
orchestration controls, not executable Core semantics.

Fresh `thc-fixtures interface-core` production passed all 21 native rows and
the existing full/thin/no-source/identity/way/foreign controls. Its real private
cache controls passed unchanged, including retained Core, annotation, foreign
payload and dependency mutations, changes after the initial probe, omitted
inventories, corrupt archives/indexes, and preservation after failed refresh.
The wrapper observed the same 78 helper probe/load calls as the text control.

Fresh default and dense runtime forks passed all 30 selected tests:
`HandoffTest` (nine per mode) and `InterfaceCoreNativeTest` (six per mode).
These include recovered original bodies against the native oracle in AST and
bytecode, first-installed-code entry and retained validity, original CBV
metadata, strict admission, archived foreign rejection, and linked CAPI calls.
No runtime implementation, compiler setting, or test assertion changed.

No compiler or Pandoc closure was recaptured for the allocation screen, and no
installed interface or shared package cache changed.
