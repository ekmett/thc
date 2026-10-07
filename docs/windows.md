# Native Windows builds

During fixture triage, smoke, driver, directory and codepage fixture lanes are
[quarantined](fixture-quarantine.log). `bin/windows.ps1 -Action Test` runs the
remaining portable and direct ABI checks and reports these exclusions. Explicit
requests for the quarantined lanes stop before building. Native Windows execution
of this quarantine change has not yet been verified.

The Windows path builds the JVM runtime, the GHC plugin, the driver, and native
fixtures with **GHC 9.14.1**, **cabal-install 3.16.0.0**, and
**JAM-patched GraalVM 25.3.4.1 / JDK 25**. Run it in native PowerShell, not WSL.
Select the separately acquired pinned Windows JAM package before bootstrap.
Bootstrap preserves `JAVA_HOME`, checks its release identity, and installs the
x64 Windows GHC bindist and Cabal. Runtime builds verify the complete JAM package;
stock GraalVM fails these checks. Full JAM runtime execution remains unqualified
on Windows. Package identities and remaining distribution
requirements are documented in [JAM runtime integration](jam-runtime.md).
Python 3.12+ is still needed by existing generators and strict Core audits.

## Select tools and build

Use a short, user-owned prefix; GHC archives and Cabal products have deep paths.
The bootstrap verifies pinned upstream SHA256 values, reuses existing archives
and installations, and sets environment variables only in the current shell.
It does not install services, change execution policy, or modify the machine PATH.
It selects its own `CABAL_CONFIG` under the prefix and uses HTTPS Hackage with
signed repository metadata and Cabal's standard Hackage trust keys. Existing
default HTTP configurations are upgraded without replacing other repositories
or explicit trust keys; disabled Hackage verification is rejected. A fresh CI
runner still needs immutable JAM package acquisition before the CI migration can
use this setup; the currently retained producer artifacts are qualification inputs.
Install a real Python interpreter first; the Microsoft Store alias does not work.
Bootstrap selects the first interpreter on PATH outside the user App Execution
Alias directory; `THC_PYTHON` overrides discovery. It checks Python 3.12+ afterward.
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
$env:JAVA_HOME = 'C:\path\to\jam-package\graalvm'
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

`Test` defaults to `-TestGroup All`, which currently runs the admitted Runtime
checks. Hosted Windows runs Runtime hourly, with a 45-minute cold-build job
budget. The quarantined Driver group is neither scheduled nor offered for manual
workflow dispatch; an explicit local Driver request fails before building.

~~~powershell
./bin/windows.ps1 -Action Test -TestGroup Runtime -Jobs 4
~~~

Runtime builds its prerequisites, runs the package-native and lock suites, and
checks tuple arithmetic, bit primops, signed-narrow arithmetic, arrays, allocation
and the native ABI. Signed-narrow uses its existing native/model suite in both
backends and handoff modes without pinned-Core acquisition. A shared Gradle
invocation retains the focused test reports for both handoff modes.
Runtime also selects two typed scalar-bitcast methods: raw Float/Double
bits survive the first installed call in both backends, wrong carriers are
rejected, and handoff storage is released. This model check needs no generated
fixture and excludes the exhaustive NaN loop. A direct node check additionally
rejects boxed operand execution while preserving signed NaN payload bits.
The native-GHC bitcast corpus
remains outside this selection; the CMake fixture graph does not support Windows.

Two fixture-free thunk checks cover opted-in speculative work starting before
demand, demand sharing the original thunk, and one evaluation with the same
published result after interpreted and first compiled hints in both backends.
The disabled queue (the default zero capacity) leaves work unforced without
admitting guest concurrency. These bounded checks do not qualify the whole
concurrency suite or GHC-derived programs.

Three additional spark lifecycle checks defer guest failure until demand while
unrelated work continues, resume the same thunk after worker cancellation without
replaying its effect, and stop claimed work and discard queued hints on context
disposal. Each checks AST and bytecode in both handoff modes. These lifecycle
checks do not establish compiled execution.

A fixture-free async check delivers the first external request to a concurrent
loop that has executed installed code before request publication. AST and bytecode
parents retain saved operands through delivery and resumption, accept a later
request, and leave another context's compiled target valid on the shared engine.
The exact method runs in both handoff modes. This synthetic check does not
qualify the whole async suite, native GHC exports or polling performance.

A fixture-free context lifecycle check uses pinned Truffle's actual JVM
preinitialization in an isolated process. It retains the same language and context
holder while replacing preparation authority with the runtime Env, checks neutral
carrier cells and second-carrier invalidation, and rejects failed startup without
restoring preparation authority. The runtime and rejection cases run in both
handoff modes. This check does not qualify Native Image heap capture, executable
generation, hosted Core preparation or native library bundling.

Two fixture-free reusable AST checks run after the preparation context closes.
Cold AOT-compiled `myThreadId#` targets observe each fresh runtime context's
identity under platform and Loom hosting. Narrow 8/16-bit address indexing checks
signed values, element offsets and runtime allocations, then rejects reads after
free without compilation. Both methods run in both handoff modes. These checks
do not qualify bytecode, Windows Text, GHC export or Native Image execution.

Two fixture-free global-initializer checks retain execution outside the lowering
lock and the first evaluator's masking state. A suspended AST global read resumes
the existing bytecode-yield initializer and publishes its completed value once
without replaying effects. Both methods run in both handoff modes; these checks
do not qualify compiled execution, exported Core or Native Image execution.

Fixture-free weak checks cover identity-only registrations and actionless raw
`MutVar#` and `MVar#` keys with distinct values. The cycle check requires actual collection
of value-to-key cycles while live keys retain their original values, including
first compiled make and dead dereference calls on AST and bytecode.
Shared controls retain dropped registrations under live keys until detach and
check actual context close detaches values and invalidates handles without
running Haskell actions. Callback attachment promotes collectible registrations
to explicit lifetime; ordinary distinct-key values and all actions remain
strongly retained until explicit finalization or context close. Callbacks run
newest-first once. A retained MVar request, including a cancelled request, keeps
its key and conditional value alive until the request is dropped; the
[raw MVar ownership controls](https://github.com/ekmett/thc/pull/1139) also retain
the cell's transfer, cancellation and no-replay checks. Selected checks run in
both handoff modes. General ephemerons, automatic Haskell/package finalizers
and the native weak corpus remain outside this qualification.

The [canonical owned-malloc retirement path](c-finalizers.md) is checked on
native Windows. After actual JVM key collection, storage retires without a
managed guest GC request or weak operation; borrowed storage remains valid until
borrow completion. Companion controls retain first compiled make/GC execution
on AST and bytecode, identity/raw `MutVar#`/`MVar#` lifetimes, pending/cancelled MVar
requests, promotion/newest-first callbacks, one-HEC Loom progress, free/realloc
aliases, borrow cleanup failures and context ownership/cancellation in both
handoff modes. Qualification covers only canonical owned MALLOC retirement;
Windows LocalFree, arbitrary Haskell/package callbacks, general ephemerons and
abandoned-context reclamation remain outside it. GHC/exporter parity is not
established.

Three fixture-free empty-join controls check ordered zero-width effects, no
destination writes on failure, logical arity and state-contract rejection.
The typed-host/raw-entry route covers local joins, same-frame empty capture,
lazy tuple leaves and loan recovery on AST and bytecode, including the existing
first compiled entry check. Public `EntryValue.compile` and native GHC empty-join
fixtures are not qualified by these checks.

An ignored scalar `State#` tuple-field check preserves producer order on AST and
bytecode, including the first installed wide call, guest-throw suppression,
recovery, loan release and rejection of a proofless non-`Unit` carrier.
This synthetic check does not qualify GHC export.

A direct AST local-join check clears private tuple-result scratch references
after copying and preserves an aliased destination in both handoff modes.
It does not qualify bytecode or compiled execution.

A fixture-free sum check with unknown levity preserves an unforced pointer's
identity through results, arguments, PAPs, captures, case and constructor fields
on AST and bytecode in both handoff modes, including first installed calls and
released loans. It does not qualify genuine GHC Core or export.

Five fixture-free tuple representation checks cover parsed metadata ownership
and refinement, logical shape identity with context-isolated layouts, loan
release after mismatched results, scalar singleton-reference rejection and
unknown metadata without inferred aggregate shapes. The whole class runs in
both handoff modes; these shared model checks do not qualify backend execution,
compiled calls or GHC export.

Two public CBD diagnostic controls use the selected Haskell model encoder.
Strict and diagnostic loading leave an unsupported cold definition lazy until
demand; diagnostic mode preserves the first installed call before the cold trap
and rejects invalid host shapes, including unused formals. The whole class runs
on AST and bytecode in both handoff modes. These synthetic models do not qualify
original GHC exports or installed-Core acquisition.

One managed-stack rejection check leaves destination bytes and pointer identity
unchanged for raw/native-exposed, short, wrong-width, partial-pointer and
nonwritable storage; an unknown key returns zero. Its synthetic layout uses the
host's platform and GHC way, including Windows vanilla. Only this method runs
in both handoff modes with live AST frames. It does not qualify bytecode guest
execution, compiled calls or native GHC frame equivalence.

A separate managed-stack layout check rejects changed ABI identity and malformed
tables-next-to-code, descriptor width and overlapping IPE fields before writing
destination bytes. It uses the same synthetic boundary and runs as one exact
method in both handoff modes.

Two public-manifest loader checks use model CBDs from the selected Haskell
encoder. Cold references leave other units unopened; first demand decodes the
binding once and subsequent calls reuse it. A bad demanded unit fails without
touching the missing third unit. Runtime passes its matching encoder through
`THC_FIXTURES` and selects only these two methods on AST and bytecode in both
handoff modes. They require no GHC Core acquisition or package native-library
producer and do not qualify the Linux native-label tests.

One public-entry PAP model checks the remaining Word32 input proof after a wide
argument has been supplied. Invalid unsigned bounds fail before forcing the
entry; zero and the maximum Word32 value retain their unsigned results. The
exact method runs on AST and bytecode through loose CBD and indexed manifests
in both handoff modes. This check does not qualify explicit compilation or the
retained-GHC interface provider, which is currently Linux/macOS only.

Two native-byte-array checks cover native-access permission at context
initialization and an AST allocation retained after context close. Retained
storage stays readable; the closed address registry rejects recovery and
pointer-bearing storage rejects raw-segment exposure. Both handoff modes run
without generated fixtures. This ownership check does not cover bytecode or
explicit compilation.

The launcher-policy check covers native backing by default, an explicit heap
override and unchanged embedding defaults on AST and bytecode in both handoff
modes.

The creation-policy check covers allocation, pinning, resize, aliases and compact
copying under heap/native policy on both backends and handoff modes, including
first AST compiled-call evidence without qualifying bytecode compiled-body
execution.

One shared memory-model check covers ordering within an allocation, aliases and
checked offsets, rejecting comparisons across owners; it does not qualify
backend or compiled execution.

One bytecode continuation check resumes a caller after two child suspensions,
memoizes the child's guest failure and preserves its payload on later demand
without replaying either prefix. It does not qualify compiled execution.

A fixture-free [GHC BCO](ghc-bco.md) spark check shares the original updating AP
and resumes cancelled workers without replaying completed effects on AST and
bytecode, with explicit platform and Loom hosting in both handoff modes.
Related continuation controls retain captured application cuts, updates, pending
apply and overapplication arguments. These checks do not qualify BCO JIT
execution, the whole BCO suite or GHC/exporter parity.

One fixture-free literal check preserves Int8/Word8 values with absent or unknown
proofs on AST and bytecode, rejects malformed metadata and out-of-range values,
and keeps ordinary machine literals nonnarrow. It does not qualify compiled
execution or exported Core.

Registered plugin lookup accepts Cabal build directories reached through directory
junctions and checks containment against the resolved `dist-newstyle/build` root.
Keep native fixture outputs physically inside the checkout's `build` directory:
their strict provenance checks reject redirected artifact paths.

For `thc run`, `--with-ghc` takes precedence over GHC, then PATH. The corresponding
package tool uses `--with-ghc-pkg`, GHC_PKG, then compiler-relative discovery.
Windows selects the pinned versioned binaries beside unversioned bindist
launchers when available, preserving wide arguments and one canonical tool
identity through Cabal support acquisition and Core export.

PowerShell helpers treat native exit codes as authoritative. An ordinary
compiler message on stderr is not a failed native command. Failed commands
still stop the workflow.

## Spark hosting checks

The four sharing and lifecycle methods above also run under Loom on native
Windows in both handoff modes, retaining their AST and bytecode cases. Only the
sharing method establishes first compiled hint execution. Cancellation checks
managed admission, the worker's hosting policy, acknowledgement, termination and
resumption without replaying its effect; it also runs under explicit platform
hosting. This does not qualify the whole concurrency suite or a parallel speedup.

With the pinned tools selected, run these fixture-free checks sequentially in
an owned checkout with no other build running. Preserve each hosting's XML before
the next invocation replaces the ordinary task reports:

~~~powershell
. ./bin/windows-common.ps1
$sparkChecks = @(
    'thc.ThreadedThunkTest.sparkedWorkRunsBeforeDemandAndFirstCompiledHintsShareTheOriginalThunk',
    'thc.ThreadedThunkTest.sparkedGuestFailureIsDeferredAndDoesNotStopUnrelatedWork',
    'thc.ThreadedThunkTest.cancellingSparkWorkerLeavesTheSameThunkResumableWithoutReplayingItsEffect',
    'thc.ThreadedThunkTest.disposingSparkContextStopsClaimedWorkAndDiscardsUnstartedHints'
)
$savedToolOptions = $env:JAVA_TOOL_OPTIONS
try {
    foreach ($hosting in @('loom', 'platform')) {
        $env:JAVA_TOOL_OPTIONS = "$savedToolOptions -Dpolyglot.thc.ThreadHosting=$hosting".Trim()
        $selection = if ($hosting -eq 'loom') { $sparkChecks } else { @($sparkChecks[2]) }
        $arguments = @('--no-daemon', '--max-workers=4', '--continue')
        foreach ($mode in @('testDefault', 'testDense')) {
            $arguments += @($mode, '--rerun')
            foreach ($test in $selection) { $arguments += @('--tests', $test) }
        }
        Invoke-ThcTool "$PWD/gradlew.bat" $arguments
        foreach ($mode in @('testDefault', 'testDense')) {
            $reportPath = "build/test-results/$mode/TEST-thc.ThreadedThunkTest.xml"
            Copy-Item -LiteralPath $reportPath -Destination "build/test-results/$hosting-$mode.xml"
        }
    }
} finally {
    if ($null -eq $savedToolOptions) { Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue }
    else { $env:JAVA_TOOL_OPTIONS = $savedToolOptions }
}
~~~

## Native export and launch

The word-floating, scalar-bitcasts, bit, signed-narrow and explicit64 producers
each prepare their plugin, genuine CBD and native integer-bit oracle independently.
The first three export pre/post-tidy Core; signed-narrow and explicit64 export one
Core stage. After bootstrap, run these groups and their tests in both handoff modes:

~~~powershell
$tools = Get-ThcGhc
$env:GHC = $tools.Compiler
$env:GHC_PKG = $tools.PackageTool
$env:GHC_ENVIRONMENT = '-'
foreach ($group in @('word-floating', 'scalar-bitcasts', 'bit', 'signed-narrow', 'explicit64')) {
    Invoke-ThcTool $env:CABAL @('run', 'exe:thc-fixtures', '--offline', '--disable-shared',
        '-fdevelopment', "--with-compiler=$($tools.Compiler)",
        "--with-hc-pkg=$($tools.PackageTool)", '--', $group)
}
Invoke-ThcTool ./gradlew.bat @('--no-daemon', '--max-workers=4', '--continue',
    'testDefault', '--tests', 'thc.runtime.WordFloatingTest',
    '--tests', 'thc.runtime.ScalarBitCastTest',
    '--tests', 'thc.runtime.BitPrimopsTest',
    '--tests', 'thc.SignedNarrowPrimopsTest',
    '--tests', 'thc.runtime.Explicit64PrimopsTest',
    'testDense', '--tests', 'thc.runtime.WordFloatingTest',
    '--tests', 'thc.runtime.ScalarBitCastTest',
    '--tests', 'thc.runtime.BitPrimopsTest',
    '--tests', 'thc.SignedNarrowPrimopsTest',
    '--tests', 'thc.runtime.Explicit64PrimopsTest')
~~~

Shared fixture subprocesses select the existing PowerShell exporter on Windows,
passing compiler flags through GHC response files. `THC_PYTHON` selects the real
interpreter for existing audit scripts; Unix export selection is unchanged.

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
Acquisition uses the selected Cabal registration's complete module and C/C++
inventory. The genuine wired Core owner remains `ghc-internal`; its actual
registered unit ID is retained separately for dependencies. The runtime
projection excludes only the declared native-registration module and records
the complete source bundle's hash.
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

The Core cache resolver uses forward slashes for Windows roots, including
`THC_CACHE_HOME`, so slash and backslash spellings select the same identity.
Distinct roots remain distinct; existing entries are not moved or relabelled.

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
The code-page producer also acquires the registered `thc:runtime` component
through the driver's normal Windows acquisition path and records its package
manifest and CBD hashes. The consumers link the validated exception helpers
from that bundle; they do not depend on the separate polyglot fixture corpus.
Selected preparation builds its driver first; normal acquisition builds the
interface helper. It does not require an earlier whole-project build.

~~~powershell
./bin/windows.ps1 -Action CodePageTest -Jobs 4
~~~

Win32 supplies the actual code pages, conversion flags, fallback characters and
localized error text; ANSI conversion is not replaced with UTF-8. Buffers retain
their capacity and mutability checks, including native partial-failure writes.
The fixture compares conversions and errors for explicit code pages with native
GHC results. It does not compare console code-page numbers across processes:
the producer and test JVM can have different associated consoles.

Both safe and unsafe `WideCharToMultiByte` declarations retain their exact
argument widths. Safe calls release guest scheduling admission during native
execution, and asynchronous delivery after the call does not repeat the
conversion.

Windows file and Handle opening remains unsupported. Ordinary imports require
the package's native artifacts alongside its Core.

Messages allocated by `base_getErrorMessage` must be released with `LocalFree`.
Aliases become invalid after release, and context disposal frees remaining
messages. Interior, stale, cross-context and wrong-allocator frees are rejected.

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
a foreign CPU target. Loaded package companions use DLLs built from the package's
declared libraries and original C archive members.
Native authority does not grant the guest current-directory or file access.

Installed C adapters use the x64 MSVC LLVM ABI. Their native dependencies link
against the original package archives and the matching GHC's registered native
libraries. Haskell execution, scheduling and exceptions remain owned by THC.
Configured MinGW C products retain their source, header and object observations
in the ordinary bundle provenance. They execute through the existing private
PE companion, alongside MSVC LLVM adapters; their MinGW LLVM is not admitted
to Sulong or relabeled as MSVC LLVM.

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
`NativeLinkTest` runs the owning Haskell native acquisition tests, including a
real registered Windows C archive, independent GHC oracle, MSVC adapter target,
private DLL proof and unchanged executable Core segments. `Test` runs it too.
`WindowsServicesTest` checks directory and code-page services in both handoff
modes; `MallocTest` checks allocation and returned pointers. These supplement the
ordinary `Test` action.

`Test` also prepares the original tuple-arithmetic fixture with the native
PowerShell exporter, pre/post-tidy Core audits and a fresh GHC executable oracle.
It runs the whole `thc.runtime.TupleArithmeticTest` and `thc.runtime.WordCarryTest`
classes in both handoff modes. They check AST and bytecode results against native GHC and an independent
integer model, including the original first compiled call controls. With those
fixtures prepared, run the owning checks directly:

~~~powershell
./gradlew.bat --no-daemon --max-workers=4 --continue testDefault --tests thc.runtime.TupleArithmeticTest --tests thc.runtime.WordCarryTest testDense --tests thc.runtime.TupleArithmeticTest --tests thc.runtime.WordCarryTest
~~~

- Stock GHC interfaces may lack complete installed-library Core. Run
  `./bin/windows.ps1 -Action CheckCore` for the selected installation; see
  [complete Core](ghc-core.md) when it is unavailable.
- Ordinary foreign calls in either Core backend require the `THC.Exception`
  support bundle, which Windows project acquisition prepares automatically.
  `thc-fixtures.exe windows-driver` checks native-matching completion in both
  backends and handoff modes, with strict verification on its first run.
- Exception audits record `libdwPoolRelease` and `backtraceFree` addresses reached
  through GHC's default exception annotations as requiring resolution when their
  expressions are evaluated. An accepted audit does not certify their native
  availability. The optional Libdw backend is unsupported by THC; retain missing
  symbol errors when those paths execute and do not restore retired overrides.
- The project path supports a single simple executable without internal-library
  or build-tool dependencies. Benchmark and test-component capture is unsupported.
- POSIX stdio/stat/termios/signal ABIs and Linux providers are unavailable.
  General Windows native IO, arbitrary CAPI/Sulong libraries and GMP remain
  outside this platform contract.
- Sulong bitcode uses its MSVC target; the native GHC helpers use MinGW.
  Arbitrary MinGW package bitcode cannot be assumed ABI-compatible.
- Pinned source hashes require original LF bytes. Restore converted source bytes
  rather than changing hashes to accept them.
