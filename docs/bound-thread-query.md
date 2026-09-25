# Original bound-thread support query

THC's original `ghc-internal:rtsSupportsBoundThreads` adapter reports false.
This is a negative capability query, not `isCurrentThreadBound#` and not an
implementation of `forkOS`, bound-thread scheduling, or foreign thread-local
state affinity. THC can run concurrent guest threads without providing those
GHC bound-thread guarantees. No GuestThreads registry state is changed.

GHC 9.14.1 `rts/Threads.c` returns true exactly for `THREADED_RTS`; its
`HsBool` is `StgInt` (`rts/include/HsFFI.h`), not `CInt`. The original source-pure
Bool import exports as unsafe ccall, one State# argument, and the logical
`(# State#, Int# #)` result. Both backends validate the closed original
certificate and the actual void carrier before writing one typed long zero.
Defined/colliding foreign heads and forged stored State representations fail.

A negative query selects real nonthreaded paths in IO.FD and Conc.IO, including
unsafe descriptor calls and `waitRead#` / `waitWrite#`. It does not implement
those paths or suppress their strict closure obligations. Linux event-manager
initialization also has independent unconditional paths. This slice alone does
not establish ordinary hello-world or complete Handle/IO support.

The structural test `CoreBoundThreadForeignTest` checks exact certificates,
carrier rejection and immediate first-installed typed AST/bytecode execution.
It is not the native original-import proof. The genuine native/pre/post proof
is a separate full-Core fixture: the same Haskell source must show support zero
without `-threaded` and one with it. A forkIO child's current-bound query is
false in both, while native threaded main is bound. THC must match the negative
capability regardless of the exporter GHC's link mode.

The imported pure Bool may be cached in a CAF or folded after warming. A compiled
projection of that value is not evidence that the foreign call executes again
on every compiled invocation. Tests retain the original imported declaration
and typed operation evidence separately from first-installed guest target and
counter checks; no settling calls or recompilation are permitted.

Implementation checkpoint status: source only, unvalidated. The dedicated
native fixture and complete validation receipt will be recorded before any
support claim or integration.
