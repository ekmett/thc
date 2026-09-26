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
cabal run thc -- run test/fixtures/run-pure/run-pure.cabal --exe completed --thc-root "$PWD" --dist-dir "$PWD/build/run-package"
~~~

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

## Current boundaries

This is a bounded native Windows gate, not full parity with Linux/macOS or the
repository's entire fixture/test suite.

* The stock GHC 9.14.1 bindist has no complete installed-library Core: the
  initial native probe found 532 incomplete interfaces. Run
  ./scripts/windows.ps1 -Action CheckCore to test a selected installation.
  The command deliberately fails when Core is unavailable. Local CString
  recompilation does not make that compiler a full-Core installation.
  See [the compiler build requirements](ghc-core.md).
* Project-directory builds and the Unix shared-plugin/provider pipeline are
  not ported by this checkpoint. A single Cabal executable without internal
  library/build-tool dependencies uses the Windows exporter/launcher path.
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
