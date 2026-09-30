# Native Windows builds

The Windows path builds the JVM runtime, the GHC plugin, the driver, and native
fixtures with **GHC 9.14.1**, **cabal-install 3.16.0.0**, and
**GraalVM 25.3.4.1 / JDK 25**. Run it in native PowerShell, not WSL.
The current bootstrap selects GraalVM **Community** 25.3.4.1 (JDK 25.0.4.1)
and the stock x64 Windows GHC bindist.
Python 3.12+ is still needed by existing generators and strict Core audits.

## Select tools and build

Use a short, user-owned prefix; GHC archives and Cabal products have deep paths.
The bootstrap verifies pinned upstream SHA256 values, reuses existing archives
and installations, and sets environment variables only in the current shell.
It does not install services, change execution policy, or modify the machine PATH.
Install a real Python interpreter first; the Microsoft Store alias does not work.

Pinned source hashes require unmodified upstream bytes. Initialize submodules
with line-ending conversion disabled; the root attributes do not apply inside them.

~~~powershell
git -c core.autocrlf=false submodule update --init --depth 1
$env:THC_PYTHON = 'C:\path\to\python.exe'
. ./bin/bootstrap-windows.ps1 -Prefix 'C:\path\to\thc-tools'
Invoke-ThcTool $env:CABAL @('update')
./bin/windows.ps1 -Action Build -Jobs 4
./bin/windows.ps1 -Action Test -Jobs 4
~~~

Choose Jobs after inspecting available CPU and memory. Four is a conservative
default. Runtime, Haskell,
and Fixtures actions build the respective slices. Runtime still needs GHC
headers and Clang for its original C resources. An existing exact-version
installation can be selected directly with JAVA_HOME, GHC, GHC_PKG, CABAL,
THC_CLANG, and THC_PYTHON; bootstrap is optional. CABAL_DIR and GRADLE_USER_HOME
can point to task-local caches.

PowerShell helpers treat native exit codes as authoritative. An ordinary
compiler message on stderr is not a failed native command. Failed commands
still stop the workflow.

## Native export and launch

The official Windows GHC is a vanilla/static compiler. The exporter loads the
real Cabal-registered plugin archive; it does not invent a Unix shared-library
manifest or request an unavailable dynamic library way.

Installed-package discovery and helper probes select real `.hi` interfaces on
Windows and `.dyn_hi` interfaces on Unix. Acquisition views on Windows copy the
selected interface bytes into a private package database while retaining the
original compiler libdir and `ghc-pkg.exe`. They do not manufacture dynamic
interfaces or grant thin stock interfaces complete-Core status.

Pinned dependency builds follow that same selected way: Windows builds vanilla
libraries with shared libraries disabled and preserves the installed unit IDs,
dependency graph and module inventory. The selected compiler libdir and ordered
private package databases travel together through probes, compiler replay and
native linking. Cache identities include that view; a different private database
does not reuse a bundle from the original global database.

After building `driver-tests`, use `--installed-hydration-only` for helper
protocol/ownership controls and `--installed-view-only` for package-view and
dependency-mutation checks against the selected native installation. Set `GHC`,
`GHC_PKG` and `THC_TEST_DRIVER` to the matching native tools. `THC_TEST_SCRATCH`
selects test evidence storage; `TEMP`, `TMP`, GHC's `-tmpdir`, and Cabal's
`--builddir` must also be redirected when using a separate build drive.
`THC_CABAL_BUILD_DIR` selects the Cabal build directory for the driver's interface
helper and the `windows-driver` fixture's driver lookup. It does not redirect
the Windows plugin registry, which currently reads the checkout's
`dist-newstyle` plan. Keep that plan built with the same pinned compiler.
When running JVM tests against a separate Cabal build, set `THC_FIXTURES` to
that build's `thc-fixtures.exe`. The CBD model tests use the real Haskell encoder;
an older executable from another build directory is not a valid substitute.

~~~powershell
./bin/export-core.ps1 t/fixtures/core/Fixtures.hs
$modules = 'build/core/THC.Prim.Test.json,build/core/Fixtures.json'
$env:THC_BACKEND = 'ast'
./build/install/thc/bin/thc.bat $modules sumLoop 100
$env:THC_BACKEND = 'bytecode'
./build/install/thc/bin/thc.bat $modules sumLoop 100
~~~

Both calls print 5050. For a Cabal executable, use:

~~~powershell
cabal run thc -- run completed --project-dir t/fixtures/run-pure `
  --thc-root "$PWD" --dist-dir "$PWD/build/run-package"
~~~

The positional target and `--project-dir` / `--project-file` flags are resolved
by Cabal, just as on other hosts. On Windows, an accepted simple executable
then uses the existing native Windows exporter pipeline internally; there is
no separate selector syntax.

The driver selects `ghc-pkg.exe` alongside GHC and uses `pwsh` when available,
otherwise `powershell.exe`. The selected shell's execution policy applies.

Stock Windows interfaces lack some executable Core needed by the runtime
library. THC builds the required support Core from the pinned source submodules
without changing the installed package database. Initialize those submodules
before an offline run. The first build can take several minutes; set
`THC_CACHE_HOME` to choose the cache directory. The selected x86_64 GHC must use
its GMP backend. Native `forkOS` remains unsupported.

## Original Win32 directory scans

The native Windows slice recognizes the original Win32 **2.14.2.1** declarations
of FindFirstFileW, FindNextFileW, FindClose and GetLastError used by directory
**1.3.10.0**. It runs on Windows x86_64 through the explicit NativeIO context
factory, also selected by the file-enabled command-line launcher.
An arbitrary embedding context, including one with file/native access enabled,
does not acquire this fixed host-filesystem authority.

~~~powershell
./bin/windows.ps1 -Action DirectoryTest -Jobs 4
~~~

Search handles belong to one context and close explicitly or at context disposal.
The caller's result buffer remains readable after `FindClose`. Calls retain the
actual Windows error code and validate buffer capacities before effects.

Absolute paths and context-relative queries are supported. Drive-relative
`C:foo` queries need directory's absolute `furnishPath`; extended namespace
queries beginning with `\\?\` pass through verbatim. Scans do not change the
process working directory. Running the original `System.Directory` code still
requires complete Core for its dependencies; these calls do not implement the
rest of the Windows filesystem API.

## Original Windows code pages and errors

The original ghc-internal declarations of GetACP, GetConsoleCP, GetCPInfo,
IsDBCSLeadByteEx, MultiByteToWideChar, WideCharToMultiByte, GetLastError,
maperrno, maperrno_func, base_getErrorMessage and LocalFree run on Windows x86_64
in both backends. They require native access without granting guest filesystem
access. Directory calls and encoding calls share the context's captured Windows
last-error slot. The original errno slot remains separate.

~~~powershell
./bin/windows.ps1 -Action CodePageTest -Jobs 4
~~~

Win32 supplies the actual code pages, conversion flags, fallback characters and
localized error text; ANSI conversion is not replaced with UTF-8. Buffers retain
their capacity and mutability checks, including native partial-failure writes.

Messages allocated by `base_getErrorMessage` must be released with `LocalFree`.
Aliases become invalid after release, and context disposal frees remaining
messages. Interior, stale, cross-context and wrong-allocator frees are rejected.

## Original disabled-libdw finalizers

The original `libdwPoolRelease` and `backtraceFree` function labels use a native
Windows DLL built from the pinned `USE_LIBDW=0` RTS sources. JDK FFM calls the
unchanged empty C bodies with native authority and `IOAccess.NONE`, preserving
context ownership and managed, pinned, literal and native storage lifetimes.
This resolves their Sulong `KERNEL32.dll` dependency lookup without granting
guest filesystem access. It does not enable native DWARF stack inspection.

~~~powershell
./bin/windows.ps1 -Action LibdwTest -Jobs 4
~~~

See the [finalizer contract](c-finalizers.md) for ownership and lifetime rules.

## Package and path considerations

Use relative GHC output paths inside a Unicode working directory if the native
compiler cannot preserve those characters in absolute command-line paths.
THC's helper protocol uses UTF-8 independently of the host code page.

The bundled Sulong patches allow system-DLL lookup without granting guest
filesystem access. Its bitcode target is MSVC; native GHC helpers use MinGW.
Arbitrary MinGW package bitcode cannot be assumed ABI-compatible. See the
[Sulong build reference](../tools/sulong-windows/README.md) when changing that
integration.

## Current boundaries

Native allocations preserve context ownership, bounds and allocator identity.
`LocalFree` allocations use their own deallocator; C allocation failures use
`errno`, not `GetLastError`. These services do not provide general file/Handle IO.

Focused platform checks are available with `./bin/windows.ps1 -Action ACTION`:
`WindowsServicesTest` checks directory and code-page services in both handoff
modes; `MallocTest` checks allocation and returned pointers. These supplement the
ordinary `Test` action.

- Stock GHC interfaces may lack complete installed-library Core. Run
  `./bin/windows.ps1 -Action CheckCore` for the selected installation; see
  [complete Core](ghc-core.md) when it is unavailable.
- The project path supports a single simple executable without internal-library
  or build-tool dependencies. Benchmark and test-component capture is unsupported.
- POSIX stdio/stat/termios/signal ABIs and Linux providers are unavailable.
  General Windows native IO, arbitrary CAPI/Sulong libraries, enabled-libdw stack
  inspection and GMP remain outside this platform contract.
- Sulong bitcode uses its MSVC target; the native GHC helpers use MinGW.
  Arbitrary MinGW package bitcode cannot be assumed ABI-compatible.
- Pinned source hashes require original LF bytes. Restore converted source bytes
  rather than changing hashes to accept them.
