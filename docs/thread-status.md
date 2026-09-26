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

Each context snapshots the available CPU capacity before guest pinning. Ordinary
carriers share zero-based capability indices round-robin; `forkOn#` selects an
index modulo that count and requests native CPU affinity on a best-effort basis.
The locked result records that request, not OS acceptance. More guest threads do
not increase the capability count. Weak carrier references let retained thread
identities preserve their assigned capability without retaining dead Java threads.
See [scheduling and affinity](thread-scheduling.md) for platform support and
`fork#`'s inherited-affinity reset. Masking, Java-thread binding, capability
locking, and physical CPU affinity remain separate concepts.

`ThreadId#` equality remains Java thread ID plus context identity. A live host
carrier outside all guest entries is in foreign execution, and subsequent host
calls reuse its identity and capability. Nested callbacks temporarily restore
running status and restore the enclosing foreign/blocking status on return.
A forked guest thread publishes its terminal outcome when its action unwinds.
For host carriers, termination is observed through the weak Java thread reference
and uses the last guest outcome. Host exceptions outside guest execution do not
become Haskell exceptions.

The existing asynchronous mailbox remains scoped to active guest registration:
an outer host return settles pending sends, and a send outside every guest entry
is a completed no-op. This patch does not queue exceptions across unrelated
future host calls. Foreign calls nested inside an active guest entry retain their
mailbox and defer delivery until a real guest continuation cut.

The Haskell fixture checks native running, masked-running, finished, died,
MVar-take, and MVar-read statuses. Its predicates consume all three result fields
while allowing GHC's capability assignment to differ from THC's managed model.
Kotlin checks retained identity, context isolation, actual MVar waits, capability
allocation, strict tuple rejection, and the first invocation after compilation of
the actual guest call graph. Run:

```
cabal run exe:thc-fixtures --offline -- thread-status
./gradlew --no-daemon test --tests thc.runtime.GuestThreadStatusTest --tests thc.runtime.ThreadStatusNativeTest --tests thc.runtime.GuestThreadsTest --tests thc.runtime.ThreadAsyncNativeTest
```
