# THC VM producer

This component owns the HotSpot, Graal and SubstrateVM integration that lets
Jam collect Java objects and implement generalized weak pointers. The producer
links the pinned Jam collector through its hosted collection API. HotSpot and
SubstrateVM reserve x28, so the collector uses its TLS context.

The THC application still consumes the historical Jam-branded providers pinned
in `etc/jam-graalvm.json`. A THC-branded build has its own Java, native and
package ABI and needs a matching consumer and independent qualification.

## Build the native adapter

Use CMake 4.4+, Ninja, Clang 23 with C++26 module support, and Python 3.10+.
On macOS arm64, use the Xcode SDK and matching libc++ and libc++abi. The native
Windows x86-64 build uses clang-cl and the Visual Studio 2022 SDK. Enable
Win32 long paths for the Windows toolchain and source checkout.

[CMakeLists.txt](CMakeLists.txt) pins Jam, Native, Work and Hint by commit.
For offline builds, supply the corresponding exact checkouts through CMake's
`FETCHCONTENT_SOURCE_DIR_*` overrides. The platform setup scripts in
[`tools/ci`](tools/ci) describe the toolchains and system packages used by the
producer. Run them only in a suitable build environment.

From this directory, select the toolchain with `JAM_CMAKE`, `JAM_NINJA`, and
`JAM_CXX`, or install it under `.toolchains` as expected by the wrappers. Set
`JAM_LIBCXX_PREFIX` when using an external libc++ installation. Then run:

```sh
bash tools/build_native.sh
```

On Windows, run `pwsh -NoProfile -File tools/build_native.ps1`. The native
wrapper builds the adapter and runs the standalone CTest suite. The output
is `build-jam` by default. `THC_VM_NATIVE_BUILD` selects another directory;
`JAM_JOBS` controls parallelism. A standalone configure enables
`THC_VM_BUILD_TESTS` by default.

The installed CMake package is `thcVM`, with targets `thc::vm` and
`thc::vm-static`. The installed Jam dependency must also be discoverable.
The generated `thc-vm-static-libraries-<configuration>.txt` lists the
adapter and transitive static archives used in packaging, including platform
compiler runtime archives where needed. The package carries dependency notices.

## Build and check GraalVM and Native Image

Use a separate worktree per concurrent producer build. The runtime scripts
share the upstream, HotSpot and JNI bridge outputs within a worktree. They
require Git, `patch`, the OpenJDK build tools and headers, and a boot JDK 24
or 25. Linux packaging also uses `patchelf`. On Windows, use native Python
and PowerShell with the Cygwin/OpenJDK tools selected by the platform setup.
[config/source-pins.json](config/source-pins.json) records the pinned LabsJDK
archive, Graal and mx commits, and upstream patch provenance.

After building the native adapter, run the managed build with the same native
output directory and configuration. For example, on Linux or macOS:

```sh
export JAM_BOOT_JDK=/absolute/path/to/boot-jdk
export JAM_BUILD_FLAVOR=release
python3 tools/build_runtime.py graalvm
python3 tools/check_runtime.py graalvm
python3 tools/check_runtime.py substratevm
```

The build acquires and checks upstream sources, prepares the patch and overlay
trees, builds LabsJDK, and packages GraalVM with Native Image in `build/graalvm`.
`jam-graalvm` and `jam-substratevm` are CMake targets for the same distribution.
Keep `JAM_BUILD_FLAVOR` consistent through the build and checks. `fastdebug`
retains VM assertions and `release` is optimized. `THC_VM_NATIVE_CONFIG`
selects the adapter configuration when required. For CTest integration,
`THC_VM_TEST_RUNTIMES='graalvm;substratevm'` registers checks for an existing
runtime.

Qualify the actual package on its intended platform. Run the adapter suite
and both runtime checks with the package's launchers and libraries, then
verify the intended consumer against that same package. Record the source
revisions, platform and toolchain, archive digest, installed identities and
check results before changing a consumer pin.

## Runtime ABI

The Java API is `thc.vm` in `lib/thc/thc-vm.jar`. The matching JNI bridge
is `libthc_bridge` and the adapter exports `thc_vm_*` from `libthc-vm`.
Windows also places the DLLs beside the launcher. Use the matching libraries
and manifest from the same producer output.

```sh
build/graalvm/bin/java -Xshare:off -Xms256m -Xmx256m \
  -XX:+UnlockExperimentalVMOptions -XX:+UseJamGC -jar application.jar
```

Use equal initial and maximum heap capacities, compressed oops, ordinary
object headers and eight-byte object alignment. Native Image selects the
collector with `--gc=jam`. The collector registration is built into the VM.

`thc.vm.Weak` associates a key with a value and optional finalizer. The value
may refer back to the key without keeping the association alive. Pending
finalizers can be run by the calling thread through `Weak.pump()`.

Original code is licensed under **BSD-2-Clause OR Apache-2.0**. Imported
sources and patches retain their terms. See [LICENSE.md](LICENSE.md) and
[NOTICE.md](NOTICE.md).
