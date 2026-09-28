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
. ./bin/bootstrap-windows.ps1 -Prefix 'C:\path\to\thc-tools'
Invoke-ThcTool $env:CABAL @('update')
./bin/windows.ps1 -Action Build -Jobs 4
./bin/windows.ps1 -Action Test -Jobs 4
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
./bin/export-core.ps1 src/examples/THC/Fixtures.hs
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

The public Haskell driver locates the selected GHC's native ghc-pkg.exe companion.
For PowerShell export it prefers pwsh when available, otherwise powershell.exe.
The selected shell's existing execution policy applies; THC never overrides it.
The Windows CLI fixture exercises companion discovery with GHC_PKG unset.

The driver sends GHC options through GHC's own response-file format. This
preserves lone dashes and drive-letter colons that Windows PowerShell's external
-File argument binder would otherwise reinterpret. External automation calling
export.ps1 should likewise pass one @response-file token for complex GHC options.

The Windows driver acquires the unchanged THC runtime library through the selected
compiler's actual Cabal registration and native build receipts. It keeps the
runtime's exact unit identity and passes a checked package manifest alongside the
application Core, avoiding the batch launcher's command-length limit.

Stock Windows GHC interfaces do not contain all executable Core needed by this
support library. On a cold cache, THC checks the GHC 9.14.1 submodule's selected
source files against `etc/ghc/9.14.1/windows-ghc-internal.json` and builds a
private source graph. The one release-generated header absent from upstream Git
is retained unchanged under `nih/pinned/ghc-9.14.1-generated`. Native
`hsc2hs` uses the selected Windows headers; GHC orders 211 modules and 24 boot
interfaces. The compiler-provided virtual `GHC.Internal.Prim` has no source body
in this graph. The selected x86_64 Windows compiler must use its GMP backend.
Compilation retains ordinary `-O2`, imported optimizations, source notes, Core
lint and complete-Core interfaces. The installed package database is unchanged.

Initialize the pinned submodules before building; source acquisition then works
offline. The first Core build can take several minutes. Core caches live under
`THC_CACHE_HOME` when set. Bundle inputs retain source hashes,
native target layout, generated-source hashes, compiler commands and graph order.

The complete source ZIP retains every compiled module. A separate checked runtime
ZIP selects complete modules without changing their bytes. `GHC.Internal.Conc.Bound`
stays archived because its original native `forkOS` export initializers need
callback registration. Loading that archive still rejects; this is not native
`forkOS` support. Supplied-module declarations require the exact complete unit
and module, and missing or conflicting providers are errors.

Windows application and runtime exports use `-fignore-interface-pragmas` after
their optimization flags. This keeps them from importing private optimized
workers from the stock compiler build into the separately rebuilt support unit.
Local optimization remains enabled; the support source graph itself uses normal
interface pragmas. This choice is recorded in cache inputs. Linux and macOS keep
their existing dynamic export path.

Without imported optimizations, the user's `main :: IO ()` can remain a thunk
producing an action. `src/driver/WindowsRunMain.hs` supplies a real Haskell host-entry
adapter, compiled by GHC with the application. Its opaque state-transformer
function leaves a genuine partial application with the existing strict IO entry
ABI. The original `Main.main` binding and its laziness remain unchanged; neither
the auditor nor the JVM accepts a weaker IO signature.

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
The Windows DLL uses the same original algorithm and C ABI wrapper. Its Java
FFM transport keeps one native image per backing array, copies back only the
touched ranges, retains owners through calls, and confines temporary native
memory to the invocation. Existing range, alignment, memcpy-overlap, native
authority, and foreign-call cleanup boundaries remain in force. This is not a
performance claim. The MD5 bridge works with IOAccess.NONE. Package bitcode uses
the pinned Windows Sulong lookup correction described below, while MD5 retains
its native transport.

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

## Native array fixtures

~~~powershell
./bin/windows.ps1 -Action ArrayTest -Jobs 4
~~~

This runs the existing Int, Int8, Int16, Int32, Double and Float/Word array
producers with native GHC, then their AST/bytecode tests in both handoff layouts.
The full Test action includes them too. Windows exports use `export.ps1` and
GHC response files; Unix exports retain `export.sh`. Manifests fingerprint the
selected exporter and the actual native executable, including Windows `.exe`
names. Native expectations, independent models and first-installed-call checks
remain unchanged. Evidence lives in the six `build/*-arrays` directories and
the default/dense test reports.

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
Extended namespace queries beginning with \\?\ are passed verbatim, including
forward slashes. Native positive and invalid-name controls cover this distinction.
Evidence is under build/windows-directory and the default/dense JUnit reports.

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

The full Test action and native Windows CI include this test. The producer uses
the hash-pinned GHC 9.14.1 submodule sources, rebuilding three unchanged declaration
interfaces with full Core and Core Lint. These bounded declaration fixtures use
`-fignore-interface-pragmas`; the complete production graph retains normal O2
and imported pragmas. A GHC session reads the actual FCallIds; a second session
typechecks consumers and requires exact type equality before specializing them.
The same genuine calls execute natively for expected results. No replacement
foreign declarations, generated Core or THC-derived expected results are used.

Win32 supplies the actual ANSI/DBCS/Unicode conversion tables, flags, fallback
characters and localized error text. The implementation does not replace ANSI
conversion with UTF-8. Buffer transport preserves shared allocation identities,
validates capacities and mutability before effects, and holds native borrows
and managed owner locks through publication. The C headers report CPINFO's
20-byte allocation while GHC's Storable representation uses its 18 field bytes;
only those fields are copied to the guest allocation. Null sizing outputs and
native partial failure writes retain their original meaning.

FormatMessageW allocations returned by base_getErrorMessage have an explicit
LocalFree owner in the context's native allocation registry. Interior, stale,
cross-context and wrong-allocator free/realloc/finalizer requests reject.
Existing aliases become invalid after release; context disposal releases any
remaining owners, and active borrows prevent premature release. The GHC error
mapping retains its ordered table, including the first ERROR_INVALID_HANDLE
entry, and its fallback ranges.

Native evidence under build/windows-codepages covers 263 error mappings,
CP1252 and CP932, UTF-8 and UTF-16 supplementary characters, invalid input/flags,
short buffers, null sizing, same-buffer rejection, custom/default characters and
localized messages. Pre/post-Tidy Core passes 22 strict entry audits. Both
backends run interpreted and check every first compiled call in both handoff
forks, with provenance, authority, bounds, state, ownership and context controls.

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

The full Test action and Windows CI include this slice. The native GHC producer
checks eight original calls, eighteen C-finalizer observations and genuine
function/data labels. Both JVM handoff forks exercise AST and bytecode, including
first-compiled-call counters, storage preservation, authority, context ownership
and expired native allocations. The existing Linux-only malloc finalizer test
remains skipped on Windows. Evidence is under build/libdw-unavailable and the
default/dense JUnit reports. See [the finalizer contract](c-finalizers.md).
The Test action prepares the directory, code-page and libdw fixtures first, then
runs their selected classes together in one default/dense invocation so later
suites do not overwrite earlier XML reports.

The separate full dictionary fixture keeps its original boxed `Int -> Int`
helpers and adds genuine GHC-compiled `Int# -> Int#` adapters for the scalar
launcher's host ABI. Its native oracle executes those same adapters. For example:

~~~powershell
cabal run exe:thc-fixtures --disable-shared -- windows-bridge
$proof = Get-Content build/windows-bridge/provenance.json -Raw | ConvertFrom-Json
$env:JAVA_OPTS = '-Xmx24g'
$env:THC_BACKEND = 'ast'
./build/install/thc/bin/thc.bat ($proof.modules -join ',') roundTripScalar -17
~~~

This prints `-17`. The complete support graph currently needs a large heap. Run these JVMs serially
when using the example's 24-GiB heap limit.
All four scalar helpers match their native oracle in interpreted CLI execution
on both AST/bytecode and default/dense handoff modes.

## Native component inventory checks

The driver canonicalizes all component output roots before assigning native
objects or discovering home interfaces, including mixed separator and `.`
aliases. A more-specific component root retains ownership of its artifacts.
Discovered module names only become Haskell inventory after the selected GHC
reads each real binary interface and checks its unit, module and way.

With the pinned native toolchain on PATH, the focused checks are:

~~~powershell
cabal test driver-tests --disable-shared -fdevelopment -j2 --test-options=--native-recipe-only --test-show-details=direct
cabal test driver-tests --disable-shared -fdevelopment -j2 --test-options=--installed-hydration-only --test-show-details=direct
~~~

The recipe tests use an explicit `component café λ` directory, including the
absolute canonical interface path in both JSON request and response. Helper
pipes carry UTF-8 bytes independently of the host code page; hydration controls
also exercise concurrent pipe draining, cancellation and invalid UTF-8 rejection.
These are driver and binary-interface checks, not full-Core or JVM execution.

Native GHC 9.14.1 on the tested Windows host replaces `λ` in an absolute
`-outputdir` argument with `?`. The real-interface fixture therefore compiles
with relative `-outputdir dist` inside the Unicode working directory, then
verifies the full absolute Unicode path through the helper. Arbitrary Unicode
compiler command-line arguments remain a separate upstream toolchain boundary.

## Sulong system-DLL lookup without guest IO

The pinned Windows Sulong PE locator queries the guest current directory before
native DLL fallback. With `IOAccess.NONE`, that optional path query throws and
prevents loading `KERNEL32.dll`. The Windows Gradle build applies a small,
hash-pinned upstream-source patch that skips the denied cwd search and continues
the original global/native lookup. It grants no guest filesystem access and
leaves native authority, C-owned pointer carriers and context ownership intact.
Linux/macOS retain the original locator. All hosts share the separate
[declared-global-mutability correction](../tools/sulong-globals/README.md),
composed into the same pinned Sulong artifact.

The packaged pointer bridge is compiled for Sulong's actual MSVC LLVM target,
using Clang memory builtins on Windows to avoid an extra SDK dependency. The
native GHC helpers retain their MinGW ABI, and the cbits manifest records the
separate bitcode targets. Arbitrary MinGW package bitcode still requires a
compatible producer; the loader's ABI verification remains enabled.

See the [patch provenance and focused checks](../tools/sulong-windows/README.md).
`verifyWindowsSulongSelection` verifies that only the intended upstream class
families change and that the runtime selects exactly one patched artifact. The first
build downloads the pinned source classifier; subsequent builds work offline.
This addresses library loading, not general Windows native IO or full-Core
exporter parity.

## Current boundaries

Native allocation uses a small Windows DLL compiled by the selected native
Clang. Its `malloc` and `free` bind to one CRT, and it copies C `errno` into a
call-local output before returning to the JVM. The producer runs a real native
probe for pointer/size_t (64-bit), int/long (32-bit), CRT module identity, and
allocation failure; the runtime checks the packaged DLL hash and exported ABI.
This does not use `GetLastError` for C allocation failures. The existing context
ownership, ordered borrows, bounds, allocate/copy/retire realloc and disposal
rules are shared with Linux; LocalFree allocations keep their own deallocator.

Run `./bin/windows.ps1 -Action WindowsServicesTest -Jobs 2` to build both native
directory/code-page oracles and test both services plus the ABI parser controls
in one Gradle invocation with both handoff modes.

Run `./bin/windows.ps1 -Action MallocTest -Jobs 2` to build the pinned native
GHC oracle and distribution and run the allocation/returned-pointer/descriptor
checks in `testDefault` and `testDense` together. It also runs portable Linux/macOS
stdio ABI parser controls; these model receipts do not establish native POSIX
execution on Windows. The full Windows Test action
includes this slice too. To acquire only the native oracle, use
`cabal run thc-fixtures --disable-shared -- native-addresses`.
Both runtime backends and first-installed-entry checks are exercised by that
class. Its two termios tests still require the POSIX provider. These allocation
checks do not establish Windows POSIX stdio or complete installed-library Core.

Address bulk copies retain their complete range, overlap, pointer-cell and
ordered-borrow checks behind a shared compilation boundary. Expanding these
storage and cleanup paths exceeded the pinned Windows JIT's native code
installation limit, including a control using pinned storage without malloc.
The regression checks require successful first compilation and the first
installed entry on both backends; compiler limits and assertions stay unchanged.

The generated stdio receipt has a separate Windows descriptor profile. It
records real CRT errno/seek constants and LLP64 widths for transfers through
context-owned streams. `_read`/`_write` have 32-bit counts and results; this profile
does not admit them as the existing POSIX size_t/ssize_t foreign declarations.
There are no fabricated fcntl, *at or siginfo fields: requests for those POSIX
capabilities reject explicitly. Native file/Handle IO remains a separate limit.

This is a bounded native Windows gate, not full parity with Linux/macOS or the
repository's entire fixture/test suite.

* The stock GHC 9.14.1 bindist has no complete installed-library Core: the
  initial native probe found 532 incomplete interfaces. Run
  ./bin/windows.ps1 -Action CheckCore to test a selected installation.
  The command deliberately fails when Core is unavailable. Local CString
  recompilation does not make that compiler a full-Core installation.
  See [the compiler build requirements](ghc-core.md).
* The `windows-bridge` native oracle and all four strict dictionary-helper audits
  pass with the code-page/error declarations and native libdw labels above.
  The scalar adapters preserve the original boxed helpers and the launcher's
  primitive ABI. Automatic foreign exception conversion and compiled execution
  of the complete dictionary remain separate, unestablished boundaries.
  Native/export/audit evidence, including earlier failures, is retained under
  build/windows-bridge; the large checked graph audits have a 300-second process
  bound. No compiled-call assertion is relaxed by that acquisition budget.
* The Unix project-capture/shared-plugin/provider pipeline is not ported by
  this checkpoint. A single Cabal executable without internal library/build-tool
  dependencies uses the Windows exporter/launcher path after Cabal resolves the
  target. Benchmark and test-component capture on Windows remains unsupported.
* POSIX stdio/stat/termios/signal ABIs and Linux native providers are explicitly
  skipped on Windows, with incompatible resources excluded from packaging.
  General Windows native IO, arbitrary CAPI/Sulong libraries, enabled-libdw
  stack inspection and GMP are not certified here. Missing capability errors
  remain visible.
* Source hashes are over repository LF bytes. The attributes file pins LF
  source checkout; the batch wrapper retains CRLF. For an older checkout with
  CRLF-converted pinned sources, restore repository bytes before building.
  Never change a pinned hash to bless a converted file.
* Linux/macOS code paths are retained; this worker's validation runs only on
  native Windows. CI workflow execution on GitHub is separate from local proof.
