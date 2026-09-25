# Managed descriptor wait primops

`waitRead#` and `waitWrite#` use GHC 9.14.1's exact
`Int# -> State# s -> State# s` contract. THC currently admits descriptors
opened by its Linux x86_64 native file provider. The managed wait retains the
original logical descriptor identity across a resumable async cut, while each
physical poll request owns a private duplicated lease. Closing the descriptor,
replacing its number with `dup2`, or disposing the context wakes the wait;
reusing the number cannot redirect the suspended operation.

An invalid or closed descriptor raises the unchanged lazy
`ghc-internal:GHC.Internal.Event.Thread.blockedOnBadFD` closure. The plugin's
interface closure, the strict auditor, and runtime linking retain this implicit
RTS dependency. There is no substitute `IOException` or Java exception value.
The state argument is validated before the wait starts. Opaque embedding
streams have no native readiness contract, and this partial capability does not
claim arbitrary host descriptors or platforms. The native oracle uses GHC
9.14.1's non-threaded select I/O manager: its threaded manager does not implement
these raw primops. The full fixture is Linux-only because of THC's current
native descriptor provider; THC's managed runtime still supports concurrent
guest threads.

The ordinary Linux `FileWaitPrimitiveTest` checks first-installed AST and
bytecode execution on a real native-backed file, exact lazy payload identity
for invalid and closed descriptors, and retention of compiled code after
profiling the error path. The first cold async capture must originate in compiled
code; Graal may deoptimize while learning its handler and continuation types.
The test recompiles that learned path before checking subsequent compiled waits. The
separate full-Core fixture uses the selected GHC's original installed Core,
strict pre/post audits, and a native pipe oracle. It is explicit rather than
part of stock-GHC fixture preparation:

```sh
cabal run exe:thc-fixtures --offline -- file-wait
./gradlew --no-daemon fileWaitFullCoreTest
```

For a separate installed Core view containing rebuilt interface annotations,
set `THC_INSTALLED_CORE_GHC` and `THC_INSTALLED_CORE_GHC_PKG` to its compiler and
package-tool wrappers. Acquisition still uses the production package discovery,
interface reader, and bundle builder. The helper and native oracle are built
with the ordinary `GHC`/`GHC_PKG`; the Core view is not a native ABI replacement.

The manifest is removed before preparation. A rejected strict audit leaves its
report and logs but no success receipt. The full-Core result should be claimed
only after the selected Linux GHC and compiled JVM gates pass.
