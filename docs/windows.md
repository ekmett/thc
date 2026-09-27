# Native Windows builds

The Windows path builds the JVM runtime, the GHC plugin, the driver, and native
fixtures with **GHC 9.14.1**, **cabal-install 3.16.0.0**, and
**GraalVM 25.3.4.1 / JDK 25**. Run it in native PowerShell, not WSL.
The current bootstrap selects GraalVM **Community** 25.3.4.1 (JDK 25.0.4.1)
and the stock x64 Windows GHC bindist. Neither version is downgraded.
Python 3.12+ is still needed by existing generators and strict Core audits.

## Select tools and build

Use a short, user-owned prefix; GHC archives and Cabal products have deep paths.
The bootstrap verifies pinned upstream SHA256 values, reuses existing archives
and installations, and sets environment variables only in the current shell.
It does not install services, change execution policy, or modify the machine PATH.
Install a real Python interpreter first; the Microsoft Store alias does not work.

~~~powershell
$env:THC_PYTHON = 'C:\path\to\python.exe'
. ./scripts/bootstrap-windows.ps1 -Prefix 'C:\path\to\thc-tools'
Invoke-ThcTool $env:CABAL @('update')
./scripts/windows.ps1 -Action Build -Jobs 4
./scripts/windows.ps1 -Action Test -Jobs 4
~~~

Choose Jobs after inspecting available CPU and memory. Four is a conservative
default; the initial 16-core/128-GiB Windows worker used eight. Runtime, Haskell,
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

~~~powershell
./compiler/export.ps1 examples/THC/Fixtures.hs
$modules = 'build/core/THC.Prim.json,build/core/THC.Fixtures.json'
$env:THC_BACKEND = 'ast'
./build/install/thc/bin/thc.bat $modules sumLoop 100
$env:THC_BACKEND = 'bytecode'
./build/install/thc/bin/thc.bat $modules sumLoop 100
~~~

Both calls print 5050. For a Cabal executable, use:

~~~powershell
cabal run thc -- run completed --project-dir test/fixtures/run-pure `
  --thc-root "$PWD" --dist-dir "$PWD/build/run-package"
~~~

The positional target and `--project-dir` / `--project-file` flags are resolved
by Cabal, just as on other hosts. On Windows, an accepted simple executable
then uses the existing native Windows exporter pipeline internally; there is
no separate selector syntax.

The driver sends GHC options through GHC's own response-file format. This
preserves lone dashes and drive-letter colons that Windows PowerShell's external
-File argument binder would otherwise reinterpret. External automation calling
export.ps1 should likewise pass one @response-file token for complex GHC options.

The Gradle distribution is relocatable; regression tests
copy it and its Core inputs to paths containing spaces and invoke the actual
batch launcher from a different working directory.

## What the test command proves

The Haskell fixture producer exports real example Core, runs a native GHC
executable for 133 expected rows, audits all 19 entries strictly, and requires a
missing-entry negative control to fail. It also recompiles the unchanged,
hash-pinned GHC.Internal.CString source with simplified Core in a private
vanilla-interface overlay. The strict interface helper reads that real Core;
the installed GHC libraries are never modified.

The existing MD5 oracle compiles pinned original GHC C and compares 558 cases
(2232 context rows), five defined alias cases, and an independent digest model.
The Windows DLL uses the same original algorithm and C ABI wrapper. Its Kotlin
FFM transport keeps one native image per backing array, copies back only the
touched ranges, retains owners through calls, and confines temporary native
memory to the invocation. Existing range, alignment, memcpy-overlap, native
authority, and foreign-call cleanup boundaries remain in force. This is not a
performance claim. Windows Sulong's PE dependency lookup requires guest file
access even for system DLL lookup; the MD5 bridge works with IOAccess.NONE.

Gradle windowsSmokeTest and windowsDenseSmokeTest run the selected smoke,
runtime, bytecode, request, frame, CString, MD5, and Windows distribution tests.
They exercise AST and bytecode with default and dense handoff modes, including
existing compiled-entry checks. No retry, warmup, exclusion, or relaxed counter
has been added to a compiled-call proof. The native driver lock suite checks
cross-process exclusion and exception cleanup. A separate Haskell producer runs
the public driver against native GHC completion on AST/bytecode and default/dense
handoff modes, with both package and build paths containing spaces.

Evidence is written below build/windows-smoke (unique command logs, input and
artifact hashes), build/managed-md5-native (old attempts retained),
build/windows-launcher, build/windows-driver, build/test-results, and build/reports/tests. Fixture
provenance is checked by the JVM tests. The separate Native Windows smoke CI
workflow uses this same command and uploads evidence on failure as well as success.
It runs on pushes to `main`, pull requests targeting `main`, and manual dispatch.
Runs use GitHub-hosted `windows-2025` machines with the pinned toolchain; no
Windows runner service or secrets are required on a development PC. An active
run finishes while newer commits wait, avoiding cancellation starvation during
frequent integrations. The CI result is informative, not a manual-merge gate.

## Original Win32 directory scans

The native Windows slice recognizes the original Win32 **2.14.2.1** declarations
of FindFirstFileW, FindNextFileW, FindClose and GetLastError used by directory
**1.3.10.0**. It runs on Windows x86_64 through the explicit NativeIO context
factory, also selected by the file-enabled command-line launcher.
An arbitrary embedding context, including one with file/native access enabled,
does not acquire this fixed host-filesystem authority.

~~~powershell
./scripts/windows.ps1 -Action DirectoryTest -Jobs 4
~~~

This action builds the runtime and native Haskell tools, prepares the real
Windows fixtures, then runs testDefault and testDense from one compilation.
The full Test action and Windows CI include it. The script holds an exclusive
lease on this checkout's build directory; independent checkouts remain independent.

The producer verifies the upstream Win32 archive SHA256 and builds its unchanged
sources with complete Core using Cabal's standard Setup driver. This avoids the
Win32 → hsc2hs → process → Win32 solver cycle while retaining the installed native
hsc2hs and pinned GHC. Typed Haskell consumers are specialized with FCallIds read
from those genuine interfaces; no foreign declaration or Core is synthesized.
Both pre-Tidy and post-Tidy exports pass strict audits. The same specialized
calls execute natively for the oracle; System.Directory.listDirectory supplies
an additional independent public-library comparison.

The Windows headers are authoritative for WIN32_FIND_DATAW size, alignment,
filename offset and UTF-16 capacity. A native C build probe and the Haskell
hsc2hs oracle independently derive the layout. GHC's Bool FFI result uses Int#;
the native transport preserves the actual 32-bit BOOL ABI. FFM captures
GetLastError immediately after each call, before Java can overwrite it.

Search handles are opaque identities owned by one context. Buffer validation
and native borrows precede acquisition or advancement. Search operations,
FindClose and context disposal are serialized. Unlike a Unix dirent view,
WIN32_FIND_DATAW belongs to its caller and remains readable after FindClose.
Ordinary names, BMP and supplementary Unicode, empty directories, missing and
non-directory paths, wildcards, EOF/repeated EOF, invalid storage/state, stale
handles and cross-context misuse have focused tests on both backends.

The supported native declarations are a bounded library boundary. Full
System.Directory closure export still requires complete Core for its installed
dependencies. This slice does not implement the remaining Windows filesystem
API. Absolute paths (including extended paths furnished by directory) and
context-relative queries are supported; drive-relative C:foo queries require
directory's absolute furnishPath. No scan changes the process working directory.
Evidence is under build/windows-directory and the default/dense JUnit reports.

## Current boundaries

This is a bounded native Windows gate, not full parity with Linux/macOS or the
repository's entire fixture/test suite.

* The stock GHC 9.14.1 bindist has no complete installed-library Core: the
  initial native probe found 532 incomplete interfaces. Run
  ./scripts/windows.ps1 -Action CheckCore to test a selected installation.
  The command deliberately fails when Core is unavailable. Local CString
  recompilation does not make that compiler a full-Core installation.
  See [the compiler build requirements](ghc-core.md).
* The Unix project-capture/shared-plugin/provider pipeline is not ported by
  this checkpoint. A single Cabal executable without internal library/build-tool
  dependencies uses the Windows exporter/launcher path after Cabal resolves the
  target. Benchmark and test-component capture on Windows remains unsupported.
* POSIX stdio/stat/termios/signal ABIs and Linux native providers are explicitly
  skipped on Windows, with incompatible resources excluded from packaging.
  General Windows native IO, arbitrary CAPI/Sulong libraries, libdw finalizers,
  and GMP are not certified here. Missing capability errors remain visible.
* Source hashes are over repository LF bytes. The attributes file pins LF
  source checkout; the batch wrapper retains CRLF. For an older checkout with
  CRLF-converted pinned sources, restore repository bytes before building.
  Never change a pinned hash to bless a converted file.
* Linux/macOS code paths are retained; this worker's validation runs only on
  native Windows. CI workflow execution on GitHub is separate from local proof.
