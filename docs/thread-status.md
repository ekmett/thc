<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Managed thread status

`threadStatus#` consumes `ThreadId#` and `State# RealWorld`, returning exactly
`(# State# RealWorld, Int#, Int#, Int# #)`: execution status, capability number,
and capability-lock flag. AST and bytecode store the three physical results in
separate machine-integer slots. The void state field has no physical slot.

The GHC 9.14.1 contract comes from `compiler/GHC/Builtin/primops.txt.pp`,
`rts/PrimOps.cmm:stg_threadStatuszh`, `rts/include/rts/Constants.h`, and
`GHC.Internal.Conc.Sync`. Status 0 means runnable or running, 1 means an MVar
put/take wait, 2 means waiting on another evaluator's black hole, 10 means foreign
execution, 12 means waiting for throwTo delivery, and 14 means reading an empty
MVar. Terminal overrides are 16 for normal completion and 17 for an uncaught
guest exception. A runtime implementation failure remains a diagnostic error;
it is not relabeled as a Haskell exception.

Each context snapshots available CPU capacity before guest pinning and initializes
its separate logical capability count from it. Ordinary platform threads receive
logical indices round-robin; unmounted, unlocked Loom threads can migrate between
HECs. `forkOn#` selects modulo that count and maps to eligible CPUs for a best-effort
affinity request. Original `setNumCapabilities` changes the logical count. Platform
hosting normalizes retained indices on shrink without repinning carriers. Loom
normalizes parked and queued routes immediately, and mounted routes when they unmount.
The locked result records that request, not OS acceptance. More guest threads do
not increase the capability count. Weak carrier references let retained thread
identities preserve their observable capability without retaining dead Java threads.
See [scheduling and affinity](thread-scheduling.md) for platform support and
`fork#`'s inherited-affinity reset. Masking, Java-thread binding, capability
locking, and physical CPU affinity remain separate concepts.

Original `rts_getThreadId`, `eq_thread`, and `cmp_thread` operate on these
context-owned identities, not native GHC TSO pointers. They return the logical
Word64 identity, a Word8 equality result, and an Int32 unsigned-identity ordering
result respectively. A retained completed identity remains comparable until its
context closes. A matching numeric ID does not admit a fabricated identity;
foreign-context and expired carriers fail before the operation.

These boxed imports retain their exact declared and normalized GHC nominal
types and concrete levity. The admission is associated with the original call
inventory and survives lazy module assembly; a symbol name and `BoxedRep` alone
do not grant an override. `rts_setMainThread` similarly consumes a live owned
`Weak# ThreadId` whose key is a canonical `ThreadId#`. Finalization or context
close expires its liveness capability without retaining a permanent Java-thread
snapshot. `reportStackOverflow` validates that identity before diagnostic output.
Allocation-limit enable/disable imports remain archive-only: passive JVM
allocation accounting does not implement GHC's allocation-limit enforcement.

In platform hosting, a live host carrier outside all guest entries is in foreign
execution, and subsequent host calls reuse its logical identity and capability.
Nested callbacks temporarily restore running status and restore the enclosing
foreign/blocking status on return. Loom creates a fresh virtual-thread identity
for each outer public entry; nested entries retain it. Foreign reverse entries
use distinct bound identities on their native origin thread in either mode.
A forked guest thread publishes its terminal outcome when its action unwinds;
a Loom outer entry does so when that guest entry ends. The carrier cache and
Loom scheduler release the completed identity even if Java retains the carrier.
An independently retained `ThreadId` still keeps its weak registration live and
remains comparable. `mkWeakThreadId` therefore supports observers that do not
keep completed workers alive; see the [runnable example](../src/examples/WeakThreads.hs).
For platform host carriers, termination is observed through the weak Java
thread reference and uses the last guest outcome. Host exceptions outside guest execution do not
become Haskell exceptions.

The existing asynchronous mailbox remains scoped to active guest registration:
an outer host return settles pending sends, and a send outside every guest entry
is a completed no-op. The runtime does not queue exceptions across unrelated
future host calls. Foreign calls nested inside an active guest entry retain their
mailbox and defer delivery until a real guest continuation cut.
