# Build the VM integration

THC owns the HotSpot, Graal and SubstrateVM integration in `src/vm`.
The component links the pinned `jam::jam` collector and exports `thc::vm`
and `thc::vm-static`. The collector must use TLS (`JAM_CONTEXT_X28=OFF`):
HotSpot and SubstrateVM reserve x28.

| Target | Builds |
| --- | --- |
| `thc-vm` | Shared C ABI adapter |
| `thc-vm-static` | Static C ABI adapter |
| `jam-graalvm` | LabsJDK, patched Graal compiler and Native Image distribution |
| `jam-substratevm` | The same distribution, including SubstrateVM |

There is no standalone `jam-jdk` product. LabsJDK remains an input to GraalVM.
The named Graal/Substrate build targets are retained. Configuring or building
native adapters does not acquire JVM sources.

## Native adapter

Use CMake 4.4+, Ninja and Clang 23 with C++26 module support. On macOS use a
matching libc++/libc++abi toolchain and Xcode SDK. Windows requires clang-cl
and the Visual Studio 2022 MSVC SDK. The component pins Jam, Native, Work
and Hint in [CMakeLists.txt](../../src/vm/CMakeLists.txt); source overrides
such as `FETCHCONTENT_SOURCE_DIR_JAM` permit offline builds from those revisions.

```sh
cmake -S src/vm -B src/vm/build-jam -G Ninja \
  -DCMAKE_CXX_COMPILER=clang++ -DCMAKE_BUILD_TYPE=Release
cmake --build src/vm/build-jam --parallel 4
ctest --test-dir src/vm/build-jam -L '^vm$' --output-on-failure
```

`THC_VM_BUILD_TESTS` defaults on for a standalone configure. On macOS,
`THC_VM_RUNTIME` selects the matching libc++ runtime directory; configure
compiler include/link paths for that same toolchain. Scripts below set the
platform flags when `JAM_LIBCXX_PREFIX` points at the intended installation.

The generated `thc-vm-static-libraries-<configuration>.txt` lists the actual
adapter and transitive static archives. Packaging consumes this closure,
including the matching Clang builtins on Windows, rather than assuming the
adapter archive contains its dependencies. Dependency licenses are copied
beside the manifest.

Installing exports a `thcVM` CMake package. Consumers use
`find_package(thcVM CONFIG REQUIRED)` and link `thc::vm` or `thc::vm-static`;
the installed Jam dependency must also be discoverable.

## GraalVM and Native Image

Run the component scripts from `src/vm`. They require Python 3.10+, Git,
`patch`, Autoconf, GNU Make/M4 and a boot JDK 24 or 25, plus the usual
OpenJDK platform development headers. Linux packaging also uses `patchelf`.
The pinned source revisions and archive digests are in
[source-pins.json](../../src/vm/config/source-pins.json).

```sh
cd src/vm
export JAM_BOOT_JDK=/absolute/path/to/jdk-25
export JAM_BUILD_FLAVOR=release
python3 tools/fetch_sources.py --graal
python3 tools/prepare_jdk.py
python3 tools/prepare_graal.py
bash tools/build_native.sh
bash tools/build_hotspot.sh
bash tools/build_graal.sh
python3 tools/check_runtime.py graalvm --java-home "$PWD/build/graalvm"
python3 tools/check_runtime.py substratevm --java-home "$PWD/build/graalvm"
```

On Windows use `tools/build_native.ps1` and `tools/build_hotspot.ps1` with
the Cygwin/OpenJDK tools described by their bootstrap script. The native
wrapper uses `src/vm` as its CMake source directory on every platform.

`JAM_CMAKE`, `JAM_NINJA`, `JAM_CXX`, `JAM_AUTOCONF`, `JAM_M4`, and `JAM_MAKE`
select tools; otherwise scripts use the component's `.toolchains` directory.
`THC_VM_NATIVE_BUILD` and `THC_VM_NATIVE_CONFIG` select the adapter directory
and configuration. `JAM_JOBS` bounds build parallelism. Keep `JAM_BUILD_FLAVOR`
set consistently through preparation, build, checking and packaging;
`fastdebug` retains VM assertions and `release` is optimized.

Runtime builds share source and bridge outputs within a worktree; run them
serially. CMake's `jam-graalvm` target performs the same acquisition and build
steps. Register opt-in runtime checks with
`-DTHC_VM_TEST_RUNTIMES='graalvm;substratevm'`; CTest checks an existing runtime
and does not build it. CI production is an explicit manual workflow.

The original OpenJDK revision retained as `hotspot-patch-base` is solely the
provenance base for exporting the shared HotSpot patch. The explicit
`fetch_sources.py --hotspot-patch-base` research option acquires that archive;
it does not prepare, build or publish a standalone JDK.

## Running

```sh
export JAVA_HOME="$PWD/build/graalvm"
"$JAVA_HOME/bin/java" -Xshare:off -Xms256m -Xmx256m \
  -XX:+UnlockExperimentalVMOptions -XX:+UseJamGC -Xlog:gc \
  -jar application.jar
```

Keep the heap limits equal. `JamYoungSize`, `JamPromoteEvery`, and `JamWorkers`
remain collector options. The collector requires compressed oops, ordinary
object headers and eight-byte alignment. Native Image selects it with
`--gc=jam`. A stock JVM cannot dynamically load the collector integration.

The new Java API is `thc.vm` in `lib/thc/thc-vm.jar`, paired with
`libthc_bridge` and the `thc_vm_*` native ABI. Old Jam-branded releases remain
available, but are not interchangeable with this interface. Use a freshly
built and qualified THC runtime before changing consumer installation pins.
