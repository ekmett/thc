# Sulong global mutability and initialization

The pinned Sulong `LLVMGlobalContainer` speculates that a single-context global
is constant for its first few writes. A compiled C read-modify-write can therefore
invalidate its caller on the first installed call, even though LLVM declares the
global mutable.

`declared-mutability.patch` passes the existing `globalIsReadOnly` flag from
`InitializeSymbolsNode` to an additive container constructor. Declared mutable,
non-thread-local globals use the existing fallback storage immediately; their
reads and writes remain compilable and do not create constant assumptions.
Readonly globals retain upstream speculation. The no-argument constructor,
including its use for thread-local globals, retains the upstream policy.

The global-mutability patch changes those two classes. Global initialization values,
managed pointer carriers, native conversion, disposal, context ownership and
synchronization are unchanged. There are no extra guest calls, write-count
warmups, forced native conversions or compilation/inlining exclusions. This does
not change the first-write policy for TLS or claim arbitrary racy C accesses are
safe.

`initialization-latch.patch` marks `LoadModulesNode.hasInitialised` as
compilation final. The root-owned latch changes from false to true only after
transferring to the interpreter and adopting its initialization child. This lets
a cold compilation deoptimize before calling the null child, and later
compilations specialize the latch and populated child together. The generated
child still looks up the current context and its separate initialization state;
context state is not marked compilation final. Initialization order, invalidation
and synchronization are preserved for every LLVM consumer on the shared host.

The existing `src/gradle/windows-sulong.gradle` task names now build one pinned
artifact on every host. Windows additionally applies its existing optional-cwd
locator patch; other hosts retain the original locator bytes. Both source and
binary archives are hash-checked, and `verifyWindowsSulongSelection` checks the
complete entry inventory, byte identity outside the replaced class families,
and selection of exactly one runtime artifact. Upstream license and manifest
attributes are retained. No installed JDK or dependency-cache artifact is edited.

Upstream `org.graalvm.llvm:llvm-language:25.3.4.1`:

- Sources SHA256: `716562a9c6cbe9201f53bc972d9eafbb1a692a9d9aebb9f177a541c5e0913f66`
- Binary SHA256: `c835fc80abdc818b7487c9e0b9ecd6c7cd59a7bbb7ac7041d6e2cda2be4b9335`

The focused runtime check is:

```sh
./gradlew --max-workers=2 --continue \
  testDefault --tests thc.runtime.PackageNativeForeignTest \
  testDense --tests thc.runtime.PackageNativeForeignTest
```

Windows additionally uses the [optional-cwd lookup correction](../sulong-windows/README.md).
