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
claim arbitrary host descriptors or platforms. The selected macOS GHC 9.14.1
threaded I/O manager reports these primops unavailable, so the full native
fixture and JVM check are Linux-only.

The ordinary Linux `FileWaitPrimitiveTest` checks first-installed AST and
bytecode execution on a real native-backed file, exact lazy payload identity
for invalid and closed descriptors, and retention of compiled code. The
separate full-Core fixture uses the selected GHC's original installed Core,
strict pre/post audits, and a native pipe oracle. It is explicit rather than
part of stock-GHC fixture preparation:

```sh
cabal run exe:thc-fixtures --offline -- file-wait
./gradlew --no-daemon fileWaitFullCoreTest
```

The manifest is removed before preparation. A rejected strict audit leaves its
report and logs but no success receipt. The full-Core result should be claimed
only after the selected Linux GHC and compiled JVM gates pass.
