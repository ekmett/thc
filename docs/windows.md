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
Pinned libraries also need `sh.exe`, `sed` and the other Unix utilities used by
their original configure scripts. Bootstrap reuses Git for Windows' `usr/bin`
when no shell is already on PATH. This shell runs configure; GHC, Clang, Cabal
and the resulting binaries remain native Windows tools. With manually selected
tools, add the installed shell's utility directory to the current process PATH.

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
Pinned dependency exports use that same registered plugin unit and private
registry database. GHC's external plugin-library flag loads a DLL and cannot
load the vanilla archive; Unix keeps its direct shared-library loading path.
Windows `ghc-internal` is the bootstrap exception: its complete source graph
compiles with `-fwrite-if-simplified-core` without loading a plugin that imports
the unfinished home unit. The existing installed-interface helper then exports
the genuine rebuilt Core and foreign metadata.
Other pinned Windows packages use Cabal's complete `--make -no-link` invocation after
checking the full source graph, avoiding a new static-plugin link per module.

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
Windows configure receives the selected GHC's recorded host triplet so the
original upstream scripts select their Windows branches and type fallbacks.

After building `driver-tests`, use `--installed-hydration-only` for helper
protocol/ownership controls and `--installed-view-only` for package-view and
dependency-mutation checks against the selected native installation. Set `GHC`,
`GHC_PKG` and `THC_TEST_DRIVER` to the matching native tools. `THC_TEST_SCRATCH`
selects test evidence storage; `TEMP`, `TMP`, GHC's `-tmpdir`, and Cabal's
`--builddir` must also be redirected when using a separate build drive.
Use `--package-native-only` for captured C providers, actual ABI witnesses,
immutable Core/cache controls and strict missing-provider negatives. Its captured
producer uses the native exporter and vanilla interfaces on Windows; C `intptr_t`
matches the declared Haskell `Int` on Win64. The compiler-process-state archive
subcheck requires `ghc-9.14.1-inplace` and explicitly skips that subcheck for a
stock bindist; ordinary native-provider checks still run.
`THC_CABAL_BUILD_DIR` selects the Cabal build directory for the driver's interface
helper, the Windows CString fixture's helper, and the `windows-driver` fixture's
driver lookup. It does not redirect the Windows plugin registry, which reads the checkout's
`dist-newstyle` plan. Keep that plan built with the same pinned compiler.
When running JVM tests against a separate Cabal build, set `THC_FIXTURES` to
that build's `thc-fixtures.exe`. The CBD model tests use the real Haskell encoder;
an older executable from another build directory is not a valid substitute.
Set `THC_COMPACT` to the matching build's `thc-compact.exe` for strict CBD audits.
Build that executable too; the ordinary Windows `Build` action builds all components.

~~~powershell
./bin/export-core.ps1 t/fixtures/core/Fixtures.hs
$modules = 'build/core/THC.Prim.Test.cbd,build/core/Fixtures.cbd'
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

The original ghc-internal declarations of GetConsoleCP, GetCPInfo,
IsDBCSLeadByteEx, MultiByteToWideChar, WideCharToMultiByte, GetLastError,
maperrno, maperrno_func, base_getErrorMessage and LocalFree run on Windows x86_64
in both backends. They require native access without granting guest filesystem
access. Directory calls and encoding calls share the context's captured Windows
last-error slot. The original errno slot remains separate.
GetACP uses ordinary package linkage and requires its original native provider;
extracting its declaration's Core alone does not supply that provider.
The code-page fixture derives its native adapters from the genuine interfaces,
compiles them for Sulong's MSVC target, and links a PE companion from the
package-declared libraries and original C archive members. Native boundary
checks compare GetACP and all 263 `maperrno_func` rows with GHC, including the
first call after compilation. Context-owned error state, conversion and
allocation operations retain their existing runtime boundaries.

~~~powershell
./bin/windows.ps1 -Action CodePageTest -Jobs 4
~~~

Win32 supplies the actual code pages, conversion flags, fallback characters and
localized error text; ANSI conversion is not replaced with UTF-8. Buffers retain
their capacity and mutability checks, including native partial-failure writes.

Both safe and unsafe `WideCharToMultiByte` declarations retain their exact
argument widths. Safe calls release guest scheduling admission during native
execution, and asynchronous delivery after the call does not repeat the
conversion.

Windows file and Handle opening remains unsupported. Ordinary imports require
the package's native artifacts alongside its Core.

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
The fixture also compiles GHC-derived scalar adapters for the four ordinary
disabled-libdw calls. These execute the original DLL through package linkage;
the native boundary check uses addressable, context-owned storage and verifies
the first compiled calls and unchanged location bytes.

~~~powershell
./bin/windows.ps1 -Action LibdwTest -Jobs 4
~~~

See the [finalizer contract](c-finalizers.md) for ownership and lifetime rules.

## Package and path considerations

Select the pinned `ghc-9.14.1.exe` and `ghc-pkg-9.14.1.exe` binaries for Unicode
paths. The bindist's unversioned launchers lose characters outside the host code
page; the bootstrap and Windows build entry point select the versioned binaries.
THC's helper protocol uses UTF-8 independently of the host code page.

The bundled Sulong patches allow system-DLL lookup without granting guest
filesystem access. Its bitcode target is MSVC; native GHC helpers use MinGW.
Arbitrary MinGW package bitcode cannot be assumed ABI-compatible. See the
[Sulong build reference](../tools/sulong-windows/README.md) when changing that
integration.
Package metadata accepts x86_64 MSVC LLVM on Windows and rejects MinGW LLVM or
a foreign CPU target. Loaded package companions use `.dll` and Windows NFI
loading syntax. Their temporary files are scheduled for deletion at JVM exit,
matching the existing native runtime provider; Windows locks loaded DLL files.
Native authority does not grant the guest current-directory or file access.

## Current boundaries

To preserve a failing native link's inputs before acquisition removes its
temporary directories, enable the pinned linker's input reproducer:

~~~powershell
$env:LLD_REPRODUCE = Join-Path $PWD 'build/windows-native-link.tar'
./bin/windows.ps1 -Action Test -Jobs 4
~~~

Each link overwrites the archive. When the command stops on a link failure,
the archive retains that link's actual inputs and `response.txt`. Extract it
into a separate directory and run `lld-link.exe '@response.txt' /verbose /errorlimit:0`
from the extracted archive root to inspect archive-member
selection and unresolved references. This only links; do not load the output
DLL as a runtime provider. Windows CI preserves this archive with its logs.
Remove the diagnostic setting afterward with `Remove-Item Env:LLD_REPRODUCE`.

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
- Ordinary foreign calls in either Core backend also require the genuine
  `THC.Exception` bundle. The standalone code-page and disabled-libdw boundary
  checks pass independently of that bundle. On the development baseline,
  `thc-fixtures.exe windows-bridge` builds the native oracle and exports pinned
  Core, then fails acquisition on five physical RTS references:
  `errorBelch`, `debugBelch`, `getProcessElapsedTime`, `_assertFail` and `barf`.
  The public `windows-driver` fixture hits the same link boundary. Full Core
  provider execution and a fresh public-driver receipt remain unqualified;
  retained stale receipts must be regenerated, not accepted by editing hashes.
- The project path supports a single simple executable without internal-library
  or build-tool dependencies. Benchmark and test-component capture is unsupported.
- POSIX stdio/stat/termios/signal ABIs and Linux providers are unavailable.
  General Windows native IO, arbitrary CAPI/Sulong libraries, enabled-libdw stack
  inspection and GMP remain outside this platform contract.
- Sulong bitcode uses its MSVC target; the native GHC helpers use MinGW.
  Arbitrary MinGW package bitcode cannot be assumed ABI-compatible.
- Pinned source hashes require original LF bytes. Restore converted source bytes
  rather than changing hashes to accept them.
