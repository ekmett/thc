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

## Explicit full-Core fixture

Select GHC 9.14.1 with installed library Core, then prepare the single named
fixture and select its named Gradle group (under the host's shared resource
gates where required):

```sh
cabal run exe:thc-fixtures --offline -- bound-thread-query
./gradlew --offline --no-daemon --max-workers=2 test --tests thc.runtime.CoreBoundThreadForeignTest
./gradlew --offline --no-daemon --max-workers=2 boundThreadQueryFullCoreTest
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew --offline --no-daemon --max-workers=2 boundThreadQueryFullCoreTest
```

The genuine suite is separate from stock/thin default fixture groups, like the
original iconv suite. Explicit selection with absent/stale fixtures fails; it
does not skip or replace library definitions. The producer uses ordinary public
imports and the existing pre/post compiler exporter. Boot definitions come from
the production `Installed`/`Project.prepareInstalledBundle` provider with actual
selected Cabal compiler ABI/platform, registered package dependency closure,
complete interface Core, preserved archive obligations, and installed RTS layout.
Thin interface fragments are recorded only as diagnostic exports, not overlaid
on the complete originals. Missing full Core is an error, with no pinned fallback.
Both native command streams, package bundles, generated Core, strict audits and
local transitive exporter/driver/auditor inputs are hashed. The installed GHC
executable, interfaces and libraries are not hashed. No copied source inventory
or fake FFI declaration is added.

## Validation boundary

The identical-source native controls passed in both RTS modes, including eight
signed 64-bit projection rows per mode. Support/current-bound/forkIO-bound/
runInBoundThread-success/action-ran were respectively
`(False,False,False,False,False)` without `-threaded` and
`(True,True,False,True,True)` with it. Independent structural tests passed both
default and dense handoff modes: AST and bytecode each retained the actual guest
target on its first installed call with exactly one compiled entry, zero traps
and clean handoffs.

The genuine installed pre/post audits each supplied 66,217 bindings, reached one
consumer binding, recognized the original query, and found no missing globals.
Both nevertheless rejected the same independent global registration obligation:
`GHC.Internal.Conc.Bound` has Core schema 2 with `execution=not-linked`. Its native
foreign registration is not implemented by this query adapter. Complete original
bundles and that rejection are preserved; no successful fixture manifest is
written, and the genuine full-Core JVM suite has not been run. The producer keeps
the successful native receipt separately and attempts both strict audits before
reporting failure. This is partial validation, not complete original-module,
hello-world, or bound-thread support.
