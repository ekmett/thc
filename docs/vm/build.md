# Build and run

For an existing runtime, use the [prebuilt packages](distribution.md). The
commands below build from source.

jam-vm builds two things: a native jam backend and a patched JDK. The backend
uses C++26 modules. The JDK uses its normal C++14 toolchain and calls the
backend through [a C header](https://github.com/ekmett/jam/blob/main/vm/src/adapter/jam_vm.h).

Build on macOS 26 arm64, Linux x86_64 or Windows 11 x86_64. See
[supported configurations](status.md) for the current limits.

## CMake targets

The root build produces the C++ library by default. Enable the runtime adapter
with `-DJAM_BUILD_VM=ON`. The adapter requires the default TLS heap context;
`JAM_CONTEXT_X28` is incompatible with the runtimes, which reserve that register. Consumers link `jam::jam` or `jam::vm`; the names below
are build targets, passed to `cmake --build`.

| Target | Builds |
| --- | --- |
| `jam` | C++ collector library |
| `jam-vm` | Shared C ABI adapter |
| `jam-jdk` | Patched HotSpot JDK and a relocatable package |
| `jam-graalvm` | Patched LabsJDK, Graal compiler and Native Image distribution |
| `jam-substratevm` | The same GraalVM distribution, including SubstrateVM |

The runtime targets fetch and prepare pinned sources when requested. They need
the platform tools below, including a boot JDK. Neither configuring Jam nor
building the adapter fetches a JDK. Runtime outputs live in `vm/build/jam-jdk`
and `vm/build/graalvm`; external source trees and tool caches stay under `vm/`.
Run runtime builds serially: they share prepared sources and bridge outputs.
Set `JAM_BUILD_FLAVOR=release` for optimized runtimes or `fastdebug` for VM
assertions. Local scripts default to `fastdebug`; keep the variable set through
building, packaging and checking. The packaged JVM reports its flavor, which
must match the selection.

With those tools configured, from the repository root:

```sh
cmake -S . -B build -G Ninja -DJAM_BUILD_VM=ON -DCMAKE_CXX_COMPILER=clang++
cmake --build build --target jam-jdk
cmake -S . -B build -DJAM_VM_TEST_RUNTIMES=jdk
ctest --test-dir build -L '^jdk$' --output-on-failure
```

Use `graalvm` or `substratevm` for the other CTest labels. Register several with
`-DJAM_VM_TEST_RUNTIMES='jdk;graalvm;substratevm'`. These checks require the built
runtime; CTest does not fetch or build it. Native checks are always available
when `JAM_BUILD_TESTS=ON`; `ctest --test-dir build -L '^vm$'` selects the adapter.
The runtime checks also build their Java/JNI or Native Image test fixtures.

The scripts below remain useful for individual build stages. Run them from
`vm/`. Their native wrapper configures the same root CMake project, with output
in `vm/build-jam`. `JAM_NATIVE_BUILD` selects another adapter output directory
for runtime scripts; CMake's runtime targets set it automatically.

## Tools

Install Git, Python 3, `patch`, and the normal OpenJDK platform build tools.
On macOS that includes Xcode and its SDK. On Debian or Ubuntu, install the
compiler and development headers with:

```sh
sudo apt-get install build-essential autoconf m4 zip unzip \
  libx11-dev libxext-dev libxrender-dev libxrandr-dev libxtst-dev libxt-dev \
  libcups2-dev libfontconfig1-dev libasound2-dev libfreetype-dev libnuma-dev \
  patchelf binutils
```

The tested toolchains use:

| Tool | Version or requirement |
| --- | --- |
| C++ compiler for jam | LLVM 23.1.2 |
| C++ library on Darwin | libc++ 22.1.8, both headers and runtime, from Homebrew `llvm@22` |
| C++ library on Linux | libc++ 23.1.2 from the LLVM distribution |
| C++ library on Windows | MSVC 14.44 from Visual Studio 2022 |
| CMake | 4.4.3 |
| Ninja | 1.12 or newer |
| Autoconf | 2.72 |
| GNU M4 / Make | 1.4.20 / 4.3 or newer |
| Boot JDK | JDK 24 or 25; the recorded build uses GraalVM Community 25.3.4.1 |
| HotSpot compiler | Apple Clang 21, GCC 11 or MSVC 19.44, compiling as C++14 |

The Darwin build pairs LLVM 23's compiler with libc++ 22's headers and runtime.
Linux uses the compiler and libc++ from the same LLVM 23 distribution.
Keep the selected headers and runtime together.

The Unix build scripts default to tools under `.toolchains/`. Override their
locations for your installation:

```sh
export JAM_CMAKE=/absolute/path/to/cmake
export JAM_NINJA=/absolute/path/to/ninja
export JAM_CXX=/absolute/path/to/clang++
export JAM_AUTOCONF=/absolute/path/to/autoconf
export JAM_M4=/absolute/path/to/m4
export JAM_MAKE=/absolute/path/to/gmake
export JAM_BOOT_JDK=/absolute/path/to/jdk-25
```

On macOS, use a JDK bundle's `Contents/Home` directory. `JAM_LIBCXX_PREFIX`
defaults to `/opt/homebrew/opt/llvm@22` there. On Linux, set it to the LLVM
distribution directory containing `include/c++/v1` and the C++ runtime under
`lib/`. `JAM_JOBS` defaults to eight for the JDK and native backend, and three
for GraalVM. The native test runner expects `ctest` next to the selected `cmake` binary.

## Prepare the sources

```sh
git clone https://github.com/ekmett/jam.git
cd jam/vm
python3 tools/fetch_sources.py --full
python3 tools/prepare_jdk.py
```

The [manifest](https://github.com/ekmett/jam/blob/main/vm/config/source-pins.json) records the source revisions and
archive hashes. Jam comes from the parent directory; CMake fetches native at
the single revision pinned in the root build.
Preparation extracts OpenJDK into `upstream/jdk25` and applies the
[HotSpot patch](https://github.com/ekmett/jam/blob/main/vm/patches/hotspot-jam.patch).

The JDK preparation script refuses to replace an existing source directory.
Run it once in a fresh checkout. Repeated builds use the prepared sources.

## Build the backend and JVM

Set `JAM_BOOT_JDK` before building HotSpot. Runtime checks build their JNI fixtures
against the JDK being tested.

```sh
bash tools/build_native.sh
bash tools/build_hotspot.sh
```

The first command builds `build-jam/` and runs the native tests. The second
configures and builds the selected JDK flavor with `jamgc`, `epsilongc` and `serialgc`.
The other collectors remain available in the build; Jam no longer depends on
Serial's block-offset-table utility.

Use the completed image's `bin/java`; the path helper selects the host platform:

```sh
export JAM_JAVA="$(python3 tools/platform_paths.py)/bin/java"
"$JAM_JAVA" -Xshare:off -Xms256m -Xmx256m \
  -XX:+UnlockExperimentalVMOptions -XX:+UseJamGC -Xlog:gc \
  -jar application.jar
```

Startup logging should identify `Using Jam`. Both heap limits must be equal.
The collector validates compressed oops, ordinary object headers, eight-byte
object alignment and its reserved address windows before allocating objects.
`-Xshare:off` makes the lack of archived Java heap support explicit.

`JamYoungSize` selects usable nursery bytes; zero chooses one quarter of the
heap. `JamPromoteEvery` selects the interval between whole-nursery promotion
attempts, defaulting to three minors. `JamWorkers` selects jam's worker count;
the recorded VM tests use four. HotSpot object scanning currently runs on the
VM thread while jam's copy work can run in parallel.

## GraalVM

For Truffle languages, build the GraalVM variant. It pairs LabsJDK with Graal
25.3.4.1, the release used by thc. Both the VM and compiler need the Jam patch;
putting stock libgraal beside a Jam-enabled JDK is not sufficient.

Starting from a fresh checkout, with the tools above configured:

```sh
python3 tools/fetch_sources.py --graal
python3 tools/prepare_jdk.py --graal
python3 tools/prepare_graal.py
bash tools/build_native.sh
bash tools/build_hotspot.sh --graal
bash tools/build_graal.sh
```

This builds `build/graalvm/`, including patched libgraal, the Jam backend and
the weak API. Use it as `JAVA_HOME` and select Jam as above. The compiler uses
Jam's exact remembered slots for old-to-young stores. Compressed oops remain enabled.

The build uses the pinned `mx` checkout and keeps downloaded build dependencies
in `.toolchains/mx-cache/`. `JAM_GRAAL_OUTPUT` selects another output directory;
the packaging step refuses to overwrite an existing installation.

The distribution also includes the SubstrateVM adapter. Select it with
`native-image --gc=jam` when building a native executable. See
[Native Image](native-image.md) for heap sizing, deployment and the weak API.

## Development checks

```sh
bash tools/check_vm.sh
python3 tools/check_gc_registration.py
build-jam/jam-generational-test --require-simd
python3 tools/check_patches.py
```

`check_vm.sh` uses `JAM_JAVA` when set, with `javac` next to it; `JAM_JAVAC`
overrides that choice. Without overrides it uses the host platform's selected
JDK image. These checks exercise the public API, Java reference behavior,
barriers and generation transitions. Generated logs stay local and are ignored
by Git. `check_patches.py` reconstructs the HotSpot sources from the patch.

## Packaging

The GraalVM build already packages its runtime. To package the plain JDK:

```sh
jdk="$(python3 tools/platform_paths.py)"
python3 tools/build_bridge.py --java-home "$jdk"
python3 tools/package_jdk.py --java-home "$jdk" --output build/jam-jdk
```

The output is a JDK home that can be moved out of the checkout. `lib/jam/`
contains the backend, JNI bridge, Java API and C++ runtime libraries. The
packager rewrites native library paths relative to the installation; on macOS
it also signs the modified binaries for local use. It preserves upstream
licenses under `legal/`. The runtime prefix must contain `LICENSE.TXT` covering
its bundled C++ libraries. Linux packages keep glibc and other OS libraries as
system dependencies, so deploy on a compatible architecture and glibc version.
The [Linux toolchain script](https://github.com/ekmett/jam/blob/main/vm/tools/ci/setup_linux.sh) fetches the runtime
licenses from the matching LLVM source revision.

For the weak API, put `lib/jam/jam-vm.jar` on the application's class path and
`lib/jam` on `java.library.path`. See [integrating thc](thc-integration.md) for
registration and finalizer pumping.

## Windows

Install Git, Python 3.10 or newer, and Visual Studio 2022 with the C++ build
tools and Windows SDK. Enable Win32 long paths from an elevated PowerShell:

```powershell
Set-ItemProperty 'HKLM:/SYSTEM/CurrentControlSet/Control/FileSystem' `
  -Name LongPathsEnabled -Type DWord -Value 1
```

Start a new shell after changing the setting. The Graal dependency cache
contains paths longer than the legacy 260-character limit. Use a checkout
path without spaces. From PowerShell,
the dependency script installs the remaining tools into a directory you choose:

```powershell
$env:JAM_CI_TOOLS = "$PWD/.toolchains/windows"
./tools/ci/setup_windows.ps1 hotspot
python tools/fetch_sources.py --full
python tools/prepare_jdk.py
./tools/build_native.ps1
./tools/build_hotspot.ps1
```

Jam uses `clang-cl` and the MSVC C++ library. HotSpot uses MSVC directly.
Cygwin supplies OpenJDK's shell and build tools; the resulting JVM is a native
Windows program. In a new PowerShell session, restore the tool environment with
`. "$env:JAM_CI_TOOLS/env.ps1"`.

Package the JDK before moving it or running it outside the build environment:

```powershell
$jdk = python tools/platform_paths.py
$env:Path = "$PWD/build-jam;$env:Path"
python tools/build_bridge.py --java-home $jdk
python tools/package_jdk.py --java-home $jdk --output build/jam-jdk `
  --runtime-license "$env:JAM_CI_TOOLS/Microsoft-Build-Tools-License.docx" `
  --runtime-license "$env:JAM_CI_TOOLS/Microsoft-Redistribution.md"
./build/jam-jdk/bin/java.exe -Xshare:off -Xms256m -Xmx256m `
  -XX:+UnlockExperimentalVMOptions -XX:+UseJamGC -jar application.jar
```

The package puts Jam and its runtime DLLs in `bin/`, and keeps the Native Image
inputs and Java API in `lib/jam/`. The MSVC redistribution documents and LLVM
compiler-runtime license travel with the package. For a manually installed
toolchain, set `JAM_MSVC_REDIST` to its `x64/Microsoft.VC143.CRT` directory and
`JAM_COMPILER_RUNTIME_LICENSE` to the matching compiler-rt license.

For GraalVM, start in a fresh checkout:

```powershell
./tools/ci/setup_windows.ps1 graal
python tools/fetch_sources.py --graal
python tools/prepare_jdk.py --graal
python tools/prepare_graal.py
./tools/build_native.ps1
./tools/build_hotspot.ps1 -Graal
python tools/build_graal.py
```

Use `build/graalvm/bin/java.exe` for the JVM and
`build/graalvm/bin/native-image.cmd --gc=jam` for native executables. Windows
class paths use `;` between entries.

## Rebuilding

HotSpot's collector registration is compiled into `libjvm`. A stock JVM cannot
discover Jam through JNI, JVMTI or `-agentpath`. Once the adapter is present,
an ABI-compatible backend change can rebuild just the library. Changes to VM
registration, barriers or the host contract require a HotSpot rebuild. Module
BMI files are build inputs; they are not runtime dependencies.


## CI

Native collector and guest bridge checks run on pushes to `main` and pull
requests. Full HotSpot, GraalVM and SubstrateVM qualification runs daily at
07:23 UTC, on manual dispatch, or on a pull request carrying the
`runtime-validation` label. Use manual dispatch to qualify a release.
Pushes do not cancel scheduled or manually dispatched qualification runs.

Full qualification uses the same runtime targets above, with separate Graal
consumers sharing one built distribution per platform. CI defaults
to `release`; manual dispatch also offers `fastdebug`. Artifact names include
the flavor so the two cannot be confused.


## Editing the Graal integration

Jam's added Java files live in [`vm/src/graal/`](https://github.com/ekmett/jam/tree/main/vm/src/graal),
with paths relative to the Graal source root. Edit them directly. The
[`graal-jam.patch`](https://github.com/ekmett/jam/blob/main/vm/patches/graal-jam.patch)
contains only changes to existing upstream files. Preparation combines both.

From `vm/`, run `python3 tools/prepare_graal.py` after an edit. It updates a
previously prepared tree only when that tree still matches its recorded state;
unexpected local edits are preserved. `--check` verifies without updating it.

To work inside `upstream/graal25/` instead, stage any new files there, then run
`python3 tools/export_patches.py --graal` from `vm/`. That writes additions back
to `src/graal/` and upstream edits back to the patch. Do not edit both copies at once.
