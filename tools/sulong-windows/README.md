# Windows Sulong native boundaries

The pinned `llvm-language:25.3.4.1` Windows PE dependency locator queries the
guest current working directory before falling back to native DLL loading.
`IOAccess.NONE` denies even that public filesystem path resolution. The exception
aborts loading `KERNEL32.dll`, despite the separate native-access grant.

`optional-cwd.patch` catches only `SecurityException` around this optional search
location, then continues the original global/native lookup. Absolute paths,
same-directory lookup and all actual loading/permission checks remain upstream
code. No filesystem permission, DLL name allowlist, pointer representation or
context ownership is changed.

`native-cleanup-dependency.patch` declares the LLVM provider's dependencies on
both `nfi` and `internal/nfi-native`. The NFI frontend owns the native errno cell;
libffi owns the native calling context. Both must outlive LLVM's per-thread and
global cleanup calls. The pinned `internal/nfi-llvm` provider already depends on
LLVM, which can pull LLVM ahead of the other internal languages during ordering.
Without the actual native dependencies, reverse disposal can destroy either NFI
owner before a remaining LLVM cleanup call. The correction changes Windows
registration metadata only; it retains the original allocations, frees, native
libraries and ABI. It does not skip cleanup or catch native failures.

`src/gradle/windows-sulong.gradle` follows the existing pinned Truffle patch
workflow. It validates both Maven source/binary archives and composes this
Windows-only class corrections with the shared
[declared-global-mutability patch](../sulong-globals/README.md) in one runtime
artifact. Java is retained because this is the upstream Sulong binary boundary.
The task preserves the upstream manifest, module descriptor, POM dependencies,
license and every unrelated JAR entry. `verifyWindowsSulongSelection` rejects
duplicate stock/patched selection and checks unrelated entries byte-for-byte.
The installed JDK and Gradle cache artifacts are unchanged. Linux/macOS use the
shared global-mutability correction but retain the original Windows locator and
provider registration.

The packaged pointer bridge is genuinely compiled by the selected Clang for
`x86_64-pc-windows-msvc19.33.0`, the pinned Sulong Windows target. Its Windows
memory operations use Clang builtins, which emit the same LLVM memory intrinsics
without requiring a separate MSVC SDK. Native GHC DLLs retain their
MinGW target. On Windows, `llvm-dis` beside the selected Clang (or selected by
`THC_LLVM_DIS`/PATH) validates every emitted bitcode target before the cbits
manifest records it. Non-Clang builds retain the standard `string.h` path. No
bitcode target metadata is rewritten and Sulong's ABI check remains enabled.
This does not port arbitrary MinGW package bitcode to Sulong's MSVC ABI.

Upstream artifacts from Maven Central (`org/graalvm/llvm/llvm-language/25.3.4.1`):

- Sources SHA256: `716562a9c6cbe9201f53bc972d9eafbb1a692a9d9aebb9f177a541c5e0913f66`
- Binary SHA256: `c835fc80abdc818b7487c9e0b9ecd6c7cd59a7bbb7ac7041d6e2cda2be4b9335`
- Original `WindowsLibraryLocator.class` SHA256: `9431ffde9555bf2260801e54b48161aa8d57e7cb1187feb7925eb193771be6fb`

The first build resolves the pinned source classifier, after which the task works
offline. Focused native Windows validation:

```powershell
./gradlew.bat --no-daemon --max-workers=2 --continue testDefault --tests thc.runtime.NarrowReturnedPointerTest --tests thc.runtime.WindowsSulongLibraryLookupTest testDense --tests thc.runtime.NarrowReturnedPointerTest --tests thc.runtime.WindowsSulongLibraryLookupTest
```
