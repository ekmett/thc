# Windows Sulong optional cwd lookup

The pinned `llvm-language:25.3.4.1` Windows PE dependency locator queries the
guest current working directory before falling back to native DLL loading.
`IOAccess.NONE` denies even that public filesystem path resolution. The exception
aborts loading `KERNEL32.dll`, despite the separate native-access grant.

`optional-cwd.patch` catches only `SecurityException` around this optional search
location, then continues the original global/native lookup. Absolute paths,
same-directory lookup and all actual loading/permission checks remain upstream
code. No filesystem permission, DLL name allowlist, pointer representation or
context ownership is changed.

`gradle/windows-sulong.gradle.kts` follows the existing pinned Truffle patch
workflow. It validates both Maven source/binary archives, compiles the one patched
upstream Java ABI class, and replaces the original runtime artifact only on
Windows. Java is retained because this is the upstream Sulong binary boundary.
The task preserves the upstream manifest, module descriptor, POM dependencies,
license and every unrelated JAR entry. `verifyWindowsSulongSelection` rejects
duplicate stock/patched selection and checks unrelated entries byte-for-byte.
The installed JDK, Gradle cache artifacts and Linux/macOS dependency selection
are unchanged.

The packaged pointer bridge is genuinely compiled by the selected Clang for
`x86_64-pc-windows-msvc19.33.0`, the pinned Sulong Windows target. Its Windows
memory operations use Clang builtins, which emit the same LLVM memory intrinsics
without requiring a separate MSVC SDK. Native GHC/MD5/libdw DLLs retain their
MinGW target. The cbits manifest records each bitcode artifact's target; no
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

The existing parameterized test retains both backends and first compiled-entry
assertions on real C-owned, unknown-bound storage. Windows-specific controls
also require guest cwd/file denial before and after loading, native authority,
independent C globals and context ownership. These are runtime transport models,
not GHC full-Core export evidence.
