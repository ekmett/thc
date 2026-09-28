# Installed Core and foreign artifacts

Complete Core acquisition and native foreign-export registration are different
operations. The selected GHC interface loader preserves original Core together
with `IfaceForeign`; it does not compile, link, initialize or register its C.

Ordinary modules with no foreign products remain Core schema 1.
An installed module with nonempty foreign products uses **Core schema 2**. Its
normal Core fields retain their meanings, with an additional `foreign` object:

- `schema: 1`, `execution: "not-linked"`;
- `stubs`: null or exact `header`, `source`, `initializers`, `finalizers`;
- `files`: original GHC language spelling, source contents and extension.

Each initializer/finalizer retains `isInitializer`, original `unit`, `module`
and `name`. Lists preserve ordering. Foreign-file source is GHC's original
string, including control characters; it is not a path to reopen or text to
execute. No C, object file, symbol or lifecycle entry is synthesized. The ZIP
and its generated-Core hash cover these fields as part of the module document.

Schema 2 records foreign artifacts; it does not by itself authorize execution.
Older schema-1 readers reject it. The checked ZIP reader verifies the archive
and module hashes, schema and foreign metadata for **every** supplied module.
Modules with a verified native link have their declared foreign calls checked
against that link. Verified typed export registration or static-import provenance
can instead establish the existing bounded managed execution path; the original
C remains `not-linked`. Without such evidence, an unlinked module with initializers, finalizers or extra
foreign files is rejected even when none of its Core bindings is reachable:
those artifacts may have startup or shutdown effects independent of a Core
entry. For other unlinked modules, the linker checks the complete reachable
closure of the requested entry roots and rejects any binding owned by one of
them. An unrelated archived binding may remain in the bundle without granting
its foreign calls executable status. Direct backend construction still rejects
unresolved archive metadata, and diagnostic mode does not bypass the boundary.
Unversioned synthetic backend inputs remain allowed only without foreign
metadata; a schema-1 document cannot hide a `foreign` field. The strict Core
auditor applies the same reachability and global-obligation boundary. This
does not change the ordinary source-plugin foreign-output contract.

For already-admitted Core, an unknown foreign symbol at the backend's final
foreign-call fallback becomes an explicit nonreturning operation. An unselected
alternative can therefore be prepared without granting that symbol an
implementation. Reaching it raises `UnsupportedCore` with the original
`Unsupported foreign call: SYMBOL` message: no C call or result write occurs.
Known-call ABI validation, archive admission, missing bindings and unsupported
primitive rejection retain their existing boundaries. This is independent of
diagnostic mode. Diagnostics report `foreignUnsupportedPolicy: "trap-when-reached"`,
list these messages in `deferredUnsupported`, and increment `unsupportedTraps`
only on execution. None of this implements native object linking.

## Package-owned C, C++ and CAPI calls

Native linkage is the default. `CoreForeignOverride` is the exception list for
calls that must operate on THC-owned state or cross a THC runtime boundary. Each
exception needs that concrete reason; ordinary library functions and data belong
to their linked library. An ABI or transport gap is work on the shared FFI path,
not a reason to add another per-symbol implementation.

Both backends select semantic overrides by declaration owner and symbol (including
the encoded owner of CAPI wrappers), before validating a generic package call.
Only the selected override's strict ABI validator runs. These overrides retain
THC heap/scheduler/stable-pointer state, context environment and working directory,
managed descriptors/handles, owned allocations and pointer-cell copy semantics.
An unrelated package's `malloc` or `getenv` declaration is an ordinary native
import, not an original-library override. Native-only arithmetic, text, checksums,
constants and memory operations require normal linkage; an old adapter's existence
does not grant an otherwise unlinked import an implementation.

Generic calls seed the context's current-thread errno into the LLVM/native call
and capture it immediately afterward, including zero. Capture occurs before
pointer reconciliation, result adaptation and foreign-call readmission. The
retained original `__hscore_get_errno` observes that same slot. No return-value
heuristic or symbol list determines whether errno is transported.

Generic Windows package linkage is not qualified by this path. Existing
last-error-writing Windows adapters remain semantic overrides because their
captured state is read by the retained `GetLastError`/`maperrno` operations.
Native-only `GetACP`, unlike those writers, now requires generic linkage; its
Windows acquisition path remains a platform gap rather than a managed fallback.

Original `free`, `realloc` and `&free` accept genuine returned C pointers without
inventing THC allocation ownership. Such pointers use the native allocator in
their original LLVM context; its normal lifetime/base-pointer contract applies.
Returned aliases with known THC backing still use the checked owner, allocator,
base address and borrow lifetime. Arbitrary numeric addresses do not acquire
deallocation authority. Successful native `realloc` returns a new external
carrier, preserving neither an invented extent nor managed alias invalidation.

The `thc-package-c-ffi-v1` profile acquires ordinary local and Cabal-store
packages without a package-name whitelist. It compiles the configured C/C++ sources
and GHC's genuine retained CAPI wrappers with Clang, then links their LLVM into
the Core bundle. Native Cabal compilation still uses the selected GHC and its
configured native compiler. LLVM acquisition is a sensible second compilation,
not a requirement to reproduce the native object's exact bytes.

Support covers static `ccall`/`capi` with a scalar, address or void result.
Unsafe and safe calls may take scalar arguments, `Addr#`, `ByteArray#` and
`MutableByteArray#`. Declared safety selects the runtime foreign-call protocol:
safe calls release Loom guest admission until readmission, while unsafe calls
reject guest reentry. Both use the shared pointer transport and its lifetime
rules. Interruptible native transport and general C callbacks still require
separate support.
Pointer results retain the runtime's pointer ownership/lifetime boundary.
Pure source imports and IO
imports both retain GHC's actual State-token worker ABI and original safety.
Callbacks, interruptible calls and additional foreign-file products retain
their explicit runtime boundaries. Native library calls are otherwise linked
by default, not admitted through a tested-symbol table. The producer
uses the component's GHC linker options and the selected Cabal registrations'
external libraries, search paths and linker options. Clang emits an embedded
LLVM shared library (ELF on Linux, Mach-O on Darwin); Sulong loads its ordinary
native dependencies. Components without native externals retain raw bitcode. Missing
symbols or libraries are native linker errors. Haskell archives and the GHC
RTS are not loaded as a substitute for executing their Core in THC.
Native dependencies also receive a separate native-only companion rooted by
the component's unresolved symbols, so ordinary static archives are materialized
as callable native code. The companion contains no component code: component
globals and constructors execute only in LLVM. Its bytes and digest travel in
the package link receipt and participate in loaded-library identity. Sulong's
existing file-backed native loader owns the companion's library handle. Reverse
references from a native archive into component-defined functions still require
an actual callback boundary; no duplicate native component or guessed shim is
created to satisfy them.
C++ `.cc`, `.cpp`
and `.cxx` sources retain their actual Cabal compiler arguments, including
`-optcxx` options, and replay through GHC's C++ compiler phase. Verified LLVM
constructor/destructor arrays remain intact through linking and trimming.
Sulong executes constructors once when the owning context loads the component;
normal context close executes registered C++ `atexit` handlers and module
destructors. Forced cancellation does not promise guest cleanup. No separate
THC initializer runner or process-global destructor registration is introduced.

The selected package's `extra-libraries` chooses libstdc++ or other native
libraries; symbol names do not choose them. `__cxa_atexit` and the DSO identity
use Sulong's context-owned runtime. Native library internals remain
process-shared, while package LLVM globals belong to their context. Assembly
sources remain unsupported. THC's explicit RTS and virtualized file-descriptor
or cwd adapters still apply where a call actually crosses those boundaries;
ordinary package C code is not blanket-virtualized.

The source capture runs while Cabal's unpacked sources and generated headers
still exist. `thc-interface --home-interfaces DIR` reads the exact just-emitted
home-unit interfaces before Cabal registration, with normal dependency package
databases and binary module-identity checks. No synthetic registration or
inferred foreign declaration is substituted. Import provenance additionally
retains alpha-bound state type variables; semantic byte-array carriers are
derived from their GHC types, not guessed from an unlifted boxed RuntimeRep.

`packageNativeLink` carries the unit, LLVM target/content digest, namespaced
entry ABI and retained `buildInputs` compiler/source/header observations. Header
content participates in the wrapper component identity; the final bitcode
digest covers linked C implementations too. Actual CAPI definitions supply
their C prototypes. Ordinary `ccall` header metadata is retained too; a header
name does not change the emitted symbol or manufacture a CAPI wrapper.
Same-unit inlined calls share the component link and resolve
against the complete unit's real declaration inventory, even when their own
module declares no imports. Libraries and mutable byte-array backing storage
remain context-owned; this profile does not authorize raw JVM addresses.

One C symbol may have distinct `AddrRep` and byte-array import variants when
their C ABI agrees. Each variant retains its original semantic carriers, typed
import proof and namespaced adapter; call sites select the exact Core shape,
and proving one declaration does not authorize another variant. Incompatible
C prototypes still reject. `ByteArray#`/`MutableByteArray#` variants that erase
to the same call shape remain ambiguous: the current descriptor cannot choose
a read/write policy from those erased representations alone.

Within each module, retained CAPI stubs and their adapters compile separately
from direct `ccall` adapters, just as GHC emits those calls outside the CAPI C
stub. A CAPI header can declare opaque struct pointers and narrow C parameters
for neighboring `ccall` functions; combining those declarations with the exact
GHC caller prototypes in one C file would create spurious conflicting-type
errors. Separation preserves both the original callee definition and the
emitted caller ABI, without rewriting either declaration or guessing a header
prototype. Both translation units and their real header dependencies contribute
to the native component identity.

Direct caller translation units include only `HsFFI.h` for the emitted scalar
types, not the broad `Rts.h` header. This also prevents unrelated libc
declarations such as `FILE*` prototypes from colliding with opaque `Addr#`
caller signatures. The original `fdopen`/`fclose` shape is exercised natively
and uses the same default library-linking path. The mixed-header fixture compares an existing
managed buffer passed as an opaque struct pointer and 8/32-bit argument-boundary
behavior against native GHC with exact-width Haskell arguments. The retained
machine-word caller variant uses an explicit x86_64 Linux argument bridge when
the verified LLVM caller and actual C definition establish ordinary C integer
register slots. The bridge truncates i64/i32 arguments to the definition's
i8/i16/i32 width and preserves its sign/zero-extension attributes. Original
Haskell import metadata remains unchanged. The native GHC oracle includes
negative and high-bit values, including signed/unsigned 16-bit parameters.
The original LLVM definition lines, bridge source/hash and linked input hash
are retained in build inputs. No return conversion, pointer conversion,
variadic or non-C convention adaptation is inferred. Unrecognized signatures
keep the original adapter.

A genuine native pointer returned by package C code receives a context/lifetime
tag and may be forwarded to another package call without projecting it as byte
storage. This does not grant guest byte reads or writes. Arbitrary numeric
`int2Addr#` values remain unforwardable. Returned aliases of known malloc,
pinned or immutable native-image storage retain their existing backing for
lifetime validation; synchronous calls hold the same ordered malloc borrows.
Forwarding after a known malloc owner is freed, after registry closure or into
another context rejects. Ownership managed entirely inside an external C API
still follows that API's lifetime contract.
Alias recovery happens before releasing the returning call's original borrows.
No managed buffer is copied or pinned by this path. Non-native Sulong managed
pointer results still reject; forwarding those requires a separate alias-aware
managed-pointer implementation.

The checksum source provider recognizes retained `zlib.h` imports for `adler32`
and `crc32`, compiles unchanged upstream zlib 1.2.11 source with the package's
configured header, and rejects a header-version or source-hash mismatch. It
links a provider only when that symbol remains unresolved after package source
linking, preserving package-owned definitions. Source/header observations and
provider hashes remain in the component identity and `buildInputs`. This is
managed LLVM over the original buffers: no native zlib call, extra buffer copy,
or heap pinning is introduced. This override is needed for checksum imports
over unpinned Haskell byte arrays, which cannot be passed as native addresses.
Other zlib operations use the package's ordinary native library link and pointer
contract, rather than requiring additions to this bounded source provider.

The unchanged `digest-0.0.2.1` package now captures its two original C++ CRC32C
units and links the checksum providers. All six typed foreign adapters match
270 native Haskell observations over empty inputs, offsets, partial loops,
unsigned seeds and long blocks in interpreted and first-installed compiled
execution, in both handoff modes. This checks the common foreign adapters;
it does not claim whole Pandoc or whole-package Core execution.

The unchanged `primitive-0.9.1.0` source (Hackage cabal revision 1) imports its
memset functions with both signed and unsigned arguments. Acquisition retains
each typed adapter and requires the same configured C header for signedness
variants. That header supplies the callee prototype, including narrow argument
extension; the compiler converts each adapter's exact Haskell carrier. Different
widths, differing results and ambiguous array mutability still reject. Header
dependencies participate in the component hash. All 20 integer setters match
720 native observations over both mutable-array and address inputs, offsets,
zero/nonzero counts and signed/unsigned boundaries, interpreted and on their
first installed calls in both handoff modes. Writes affect the original managed
storage directly, without copying or pinning it.

Three small Core entry points call the original public `setByteArray` and
`readByteArray` APIs for Int16, Word16 and Int64. Their strict reachable audit
passes; 18 boundary observations match the actual native memory bytes in
interpreted and first-installed compiled AST/bytecode execution, with async
capture enabled and all installed targets retained, in both handoff modes.
This adds genuine public-API Core execution to the common-adapter checks above;
it does not claim whole-package or Pandoc execution.

Package C uses libc `getentropy` through the ordinary declared-ABI native link,
without a separate symbol admission rule. Original `splitmix-0.1.3.2` uses this through its unchanged safe
`splitmix_init :: IO Word64` import: its eight-byte buffer is local C stack
storage, not a copied Haskell array. Real native/pinned addresses remain
in-place; an ordinary unpinned heap buffer cannot be projected into libc.
The provider does not manufacture entropy, change libc status returns, or
replace the original package's error fallback. The separate temporary
safe-pointer policy does not relax native pointer projection restrictions.

The `getentropy` fixture captures the unchanged package through the production
GHC proxy and linker, checks the retained Core strictly, and records native
zero/short/256-byte/oversize/null status and guard-byte observations. The JVM
checks both backends and first installed compiled execution of the original
initializer, plus persistent pinned-buffer writes and unpinned rejection.
Random bytes are not compared bit-for-bit or claimed as an entropy-quality test.

```sh
THC_SPLITMIX_SOURCE=/path/to/splitmix-0.1.3.2 cabal run exe:thc-fixtures -- getentropy
cabal test driver-tests --test-options=--package-native-only
./gradlew --continue getEntropyDefault getEntropyDense
```

The same native-link path calls libc `wcwidth` with its declared ABI. This is
the native locale-sensitive implementation used by Tasty's original
`foreign import capi safe "wchar.h wcwidth" :: CWchar -> CInt`; no Unicode
width table or unconditional width-one substitute is introduced. Its `-1`
result is preserved, so Tasty's existing Haskell fallback remains responsible
for displaying an undefined-width character as width one.

The `wcwidth` fixture retains the original Tasty source/declaration and license,
then compiles that exact declaration and fallback in a small genuine GHC/CAPI
package. Native GHC observations in the C and C.UTF-8 locales cover ASCII,
controls, combining marks, wide characters and invalid code points. Both
backends compare the raw and fallback results before and from the first
installed compiled call, with a thread-local native locale restored after each
check. This focused declaration test is separate from running the whole Tasty
or AD test suite.

```sh
THC_TASTY_SOURCE=/path/to/tasty-1.5.4 cabal run exe:thc-fixtures -- wcwidth
./gradlew --continue wcwidthDefault wcwidthDense
```

The seven original `unix-2.8.8.0` wait-status CAPI declarations
(`WCOREDUMP`, `WSTOPSIG`, `WIFSTOPPED`, `WTERMSIG`, `WIFSIGNALED`,
`WEXITSTATUS`, `WIFEXITED`) execute wrappers compiled against the installed
`HsUnix.h` on Linux x86-64. They reuse the checked scalar foreign-call path
with the exact original unit, wrapper symbol, CInt width, and hidden
State/result tuple. The installed owner may end in `inplace` or lowercase
hexadecimal digits; each wrapper's encoded owner must match that same unit,
with its fixed index, module, and macro name. Raw macro results are preserved, including `WCOREDUMP`'s
`128` mask; there is no JVM status formula or guest process/fork emulation.
The context must permit native access.

The `unix-wait-status` fixture recovers genuine declarations from the installed
`System.Posix.Process.Internals` ordinary interface unfoldings, specializes typed consumers with those
FCallIds, and compiles the same calls with native GHC. Its 280 observations
cover exit/signal/stop encodings, flags, signed CInt edges, and outer Int
narrowing. Both backends compare pre/post-tidy Core interpreted and from the
first installed compiled call, with inlining enabled and disabled. Normal
fixture preparation and focused CI include the closed 72-file receipt. These
leaf checks do not claim full process management or a completed AD test run.

```sh
cabal run exe:thc-fixtures -- unix-wait-status
./gradlew --continue testDefault --tests thc.runtime.UnixWaitStatusTest \
  testDense --tests thc.runtime.UnixWaitStatusTest
```

Installed `text-2.1.3-inplace` also has three closed original-C adapters on Linux
x86-64: `_hs_text_memchr`, `_hs_text_measure_off` and `_hs_text_reverse`. Their exact unsafe
State-threaded declarations retain the `ByteArray#`, size/byte and signed
result carriers. They execute the unchanged upstream C over a read-only
Sulong buffer view of the existing heap or pinned allocation; ordinary heap
arrays are neither copied nor pinned. Full-width offset/length checks and
pointer-cell rejection precede C access. The measure operation retains text's
valid-UTF-8 precondition and negative available-character-count result.
Reverse retains the original valid-UTF-8 precondition and singleton State result.
It writes reversed code points into a distinct mutable array, starting at byte
zero. Checked writable output and read-only input views borrow both allocation
owners under the existing ordered locks; out-of-range, immutable, pointer-bearing
or aliased output storage is rejected before C access. Empty slices write nothing.

The original sources and license are under `nih/pinned/text-2.1.3`.
Their supported non-atomic configuration selects the original SSE/word/tail
code, avoiding a native CPUID/AVX dispatcher inside Sulong. This is not generic
installed-package C acquisition. The original portable OpenBSD `memchr` is
linked into this bitcode under a private name, so the heap buffer cannot fall
through to native libc. The full unsigned character-count domain preserves
the original C's signed-intermediate wrap behavior, including its non-ideal
result at `UINT64_MAX`; it is checked against the native library, not corrected
to an idealized text algorithm. The focused fixture calls the unchanged
installed declarations and native library over 520 search/UTF-8/reverse inputs; it is
separate from completion of the original AD upstream test suite.

```sh
cabal run exe:thc-fixtures -- text-cbits
./gradlew --continue testDefault --tests thc.runtime.TextCbitsTest testDense --tests thc.runtime.TextCbitsTest
```

The unchanged `erf-2.0.0.0` package has source-pure imports whose emitted
State-threaded calls are `safe`. Its four Float/Double entries retain that safety
through acquisition, ABI admission and call selection. Its declared libm
dependency follows the ordinary native link path, with the compiler, link
arguments and artifact digest recorded. This does not change managed-pointer
transport or the foreign-call scheduling boundary.

Safe scalar calls use the existing foreign extent: other Java guest threads and
JVM GC may progress, pending async delivery is deferred while in foreign code,
and `finally` restores the previous permission and masking extent even when
interop fails. AST and bytecode store the result before their resumable post-call
poll, so delivery never replays a completed foreign effect. Boundary controls
exercise concurrent scalar C calls, GC, deferred delivery on return, completed
result resumption and exceptional cleanup in interpreted and compiled entries.
This tranche does not claim interruptible native blocking calls, callbacks,
bound OS threads or native errno behavior.

Acquisition may retain unsupported package-native obligations in
`packageNativeArchive` (`thc-package-native-archive-v1`, `execution=not-linked`).
The original import declarations, emitted calls and foreign products remain
unchanged. A verified declaration inventory records the exact excluded emitted
signatures; supported imports in the same module can still receive their real
compiled adapters. Reachability rejects bindings containing an excluded call
before evaluation. Genuine incompatible declarations of one C symbol additionally
retain `conflictingImports`: the exact original emitted signature witnesses,
possibly from different modules of that component. Both widths remain excluded;
no result conversion or guessed callee prototype is manufactured. Compatible
safe/unsafe declarations and the existing same-C-ABI pointer/signedness adapters
are not conflicts. An unclassified non-static declaration inventory is honestly
module-wide. Ordinary unresolved LLVM declarations instead go to the native
linker with the package's declared libraries. Legacy unresolved-component
archives remain readable, but new acquisition does not manufacture partial
symbol closures to avoid normal native linking.

Only recognized unsupported cases enter this path. Malformed import metadata,
changed retained products, compiler failures, invalid LLVM, stale object receipts
and artifact/hash failures remain fatal. Initializers, finalizers and additional
foreign files retain their existing global admission checks. A published
acquisition manifest is still not a successful reachable audit or guest run.
The `package-native-archives` fixture group builds real mixed-import and
unresolved-component packages, including two modules declaring one symbol with
different result widths. Its native oracle performs the interruptible effect,
while `packageNativeArchivesDefault` and `packageNativeArchivesDense` verify
supported calls (also inside the conflicting module) and rejection before any
excluded effect in both runtimes. Set `THC_PACKAGE_NATIVE_SUPPORT` to an existing
genuine exception-runtime package manifest when preparing this fixture; it
reuses that runtime and its dependencies without re-exporting them. One shared
positive audit covers ordinary direct, indirect and initializer library calls.
The original network-3.2.9.0 capture exposed
this case for `recvmsg` and `sendmsg`: `Network.Socket.Buffer` declares `CInt`
results while `Network.Socket.ByteString.Internal` declares `CSsize` results.

Temporarily admitted safe pointer calls reuse the same synchronous foreign
extent and pointer transport as unsafe calls, without copies or automatic
pinning. Native access, context ownership, live StablePtr identities, allocation
borrows and result normalization before the call lease closes remain mandatory.
The existing safe post-return poll is retained: a pointer result is stored before
async delivery, and resumption does not repeat C's effect. Focused controls cover
aliasing, pinned offset identity, opaque returned addresses, StablePtr lifetime,
and interpreted/first-installed AST and bytecode return/resumption in both
handoff modes. This compatibility policy remains temporary; general retained
buffers, callbacks, interruptible calls and GHC capability handoff are not
implemented by admitting these declarations.

Four primitive bit-pattern entrypoints using the original erf package's public API
pass the strict reachable audit and match 88 native observations, including
signed zeros, subnormals, infinities and NaNs, in interpreted and first-installed
compiled AST/bytecode execution in both handoff modes. Both backends run with
async capture enabled. This is actual small-program Core execution, not a whole
Pandoc run.

Prepare the original package fixtures with the pinned toolchain and unchanged
acquired source trees, then run their two explicit test forks:

```sh
THC_DIGEST_SOURCE=/path/to/digest-0.0.2.1 THC_ERF_SOURCE=/path/to/erf-2.0.0.0 \
  THC_PRIMITIVE_SOURCE=/path/to/primitive-0.9.1.0 \
  cabal run exe:thc-fixtures -- package-native-originals
./gradlew --continue packageNativeOriginalsDefault packageNativeOriginalsDense
```

The Haskell producer retains original source hashes, compiler calls, typed Core,
LLVM acquisition, native observations, and changed-source/header negative
controls under `build/original-native`. The JVM rejects stale fixture inputs.
For the matching upstream primitive package description, use
`cabal get primitive-0.9.1.0 --index-state=2026-09-24T12:38:18Z`;
the pristine tarball's older base bound excludes GHC 9.14.1. No package source
patch or `allow-newer` override is needed with revision 1.

Select LLVM tools with `THC_CLANG`, `THC_LLVM_LINK`, `THC_LLVM_OPT` and
`THC_LLVM_NM`, or provide their ordinary executable names on `PATH`. The exact
Linux x86_64 `pc` vendor alias is normalized to Sulong's `unknown` spelling;
the observed native target remains in the recipe.

Core bundle cache keys include the selected LLVM tool paths and executable
contents, plus native include/SDK environment settings. Local component keys
also include the exact currently owned C translation-unit receipts, captured
source/header hashes and actual bitcode contents. Changed tools or a fresh C
capture cannot reuse an older bundle just because Cabal's native objects are
unchanged. Unrelated persistent receipts and dynamic-object twins do not add
translation units. Missing LLVM tools are recorded without requiring them for
pure-Haskell builds; acquisition diagnoses them when native code is needed.

## Legacy local package scalar C calls

Previously emitted `packageScalarLink` bundles remain readable. New ordinary
package acquisition uses the more general profile above; the restrictions in
this section describe the legacy producer only.

The `thc-local-scalar-ccall-v1` link profile covers a registered local Cabal
library component with one C translation unit and static, unsafe `ccall`
imports. Arguments and the single result must use `Int32Rep`, `Int64Rep`,
`FloatRep` or `DoubleRep`, with the original IO State argument/result retained.
It does not admit pointers, callbacks, safe/interruptible imports, native RTS
closures, initializers, destructors, global variables or additional native
libraries. This is a bounded source-component path, not arbitrary installed
Hackage cbits support.

The public runtime library separately opts into the
[`x-thc-runtime-shim: v1` compatibility profile](runtime-services.md#native-compatibility-shims-in-cabal-projects).
It records its native fallback products without linking them into the guest,
and validates every retained import and Core call against the exact reserved
runtime-service signatures. This is not an expansion of generic scalar-C support
or an exemption based on package name.

The selected GHC remains Cabal's native compiler. THC uses Cabal's resolved
flags, target platform and compiler version to select active native declarations.
Disabled optional C backends do not require native objects;
active declarations still require successful compiler receipts. The bounded
single-translation-unit profile applies to that configured component, not the
union of every conditional branch.

The saved native compiler recipe must explicitly select Clang (for example with `ghc-options: -pgmc
/absolute/path/to/clang`); acquisition does not replace a configured GCC.
Matching `llvm-link`, `opt` and `llvm-nm` must be available, or selected with
`THC_LLVM_LINK`, `THC_LLVM_OPT` and `THC_LLVM_NM`. The producer rejects unsupported C options
and LLVM constructs, requires the LLVM roundtrip to reproduce Cabal's actual
native object, and records source/header observations in the component cache
key. A target-specific native build and full-Core GHC 9.14.1 remain required.
Sulong requires the Linux x86_64 vendor spelling `unknown`, so the exact
`x86_64-pc-linux-gnu` triple is changed to `x86_64-unknown-linux-gnu` in the
emitted LLVM module. The hashed recipe retains its observed `nativeTarget`
and selected bitcode `target`; the adjusted bitcode must still reproduce the
identical Cabal native object before linking. No other target is normalized.

Typed `staticForeignImports` associations retain the original declaration,
normalization and emitted GHC ABI. A separate `packageScalarLink` carries the
component identity, target, bitcode digest and exact scalar entry signatures.
Calls resolve by the genuine emitted `(unit, symbol)` pair, including after
inlining. The producer internalizes the original C definitions and exports
component-hash entry names, so two components may use the same C spelling.
The JVM and strict auditor reject missing or conflicting ownership/ABI proof.
Sulong libraries belong to their THC context and require native access; this
profile makes no native errno or native callback claim.

The explicit `package-scalar-cbits` fixture group builds and runs two ordinary
Cabal projects and records their native results. `packageScalarFullCoreTest`
uses the unchanged acquired library bundles to check both compiled backends,
same-symbol component isolation, repeated imports and authority/carrier
rejection. It is separate from the stock/thin-GHC default test inventory and
fails when its real fixture has not been prepared.

Each call site caches a context-owned resolved function and adopted interop
libraries. Closing a context invalidates the shared lifetime assumption; cached calls
still check their context owner. AST and bytecode paths build one interop
argument array and write Long, Float or Double results directly to typed
destinations. Library loading and initial resolution remain behind boundaries.
Foreign entry/exit retain their existing masking boundaries and scope storage.
Compiled guest-entry validity does not establish Sulong inlining or
allocation-free foreign calls; those claims require separate graph evidence.

## Managed and native memory at the C boundary

THC needs both bitcode operating on managed buffers and bitcode calling real
host-native libraries, including mixed packages. These are not two mutually
exclusive runtime modes: native-enabled Sulong can handle managed interop
pointers and native pointers. Sulong's separate `--llvm.managed` sandbox mode
prohibits host-native calls; using managed buffer views does not enable that
mode. See [Sulong's native execution contract](https://www.graalvm.org/latest/reference-manual/llvm/NativeExecution/).

The practical boundary is storage, not symbol lookup. A JVM-backed managed
pointer is not a host machine address. The shared transport must preserve its
allocation identity, offset, aliases, alignment and lifetime. A native call
must not receive a temporary snapshot when it may retain a pointer, observe
aliases, or mutate state used by later calls. Nor can a general adapter infer
a buffer's required size or retention rules from an `AddrRep` alone.

Explicitly pinned arrays have stable native backing from allocation, accessed
by THC primops, Sulong and host C as the same allocation. Ordinary heap arrays
remain managed and reject native pointer projection, including after unsafe
freeze. Scoped copies remain useful for
explicitly bounded interfaces, such as the existing GMP limb provider, but
are not a universal FFI policy. Opaque guest objects need handles and a
separate re-entry contract, not pointer reinterpretation.

Managed package-C buffer views, native-backed pinned byte arrays and separately
owned native `malloc` addresses are implemented; general retained-buffer lifetime
contracts and native callbacks remain work.
The package-C acquisition path must eventually carry declared external native
dependencies as well as bitcode. This design direction is not a claim that
arbitrary mixed native packages already run.

## Package C/CAPI calls

The separate `thc-package-c-ffi-v1` profile extends the runtime link boundary
for ordinary package C code. It accepts static `ccall` and `capi`
entries with machine-word and 8/16/32/64-bit signed/unsigned integers,
`FloatRep`, `DoubleRep`, `AddrRep`, `ByteArray#` and `MutableByteArray#`
arguments, and numeric, opaque pointer or void results. Source-level pure imports still use
GHC's emitted State-threaded foreign worker. Unsafe calls and temporarily admitted
safe calls use the same carriers as described above. A CAPI value import is executed
through its original generated function wrapper.

`packageNativeLink` retains the complete component ABI and bitcode identity;
the original typed import declarations and CAPI source remain in the module.
Modules containing only inlined calls contribute no invented declarations:
their component's real import inventory must be present elsewhere in the
merged bundle. Byte-array arguments are classified from the original GHC
types; an arbitrary unlifted object is not accepted as byte storage.

The runtime writes numeric results directly into the lowered carriers and
preserves unsigned low bits at narrow C boundaries. Mutable managed buffers
remain shared across calls rather than being copied per invocation. Owned
native addresses are borrowed through synchronous return. Ordinary
`Foreign.StablePtr` arguments use persistent, context-owned opaque identities:
C may compare, store and return them across calls, and `deRefStablePtr` recovers
the original lazy referent. `freeStablePtr` releases both the root and any native
identity; context disposal releases remaining identities. Passing a token does
not expose JVM object layout or a GHC RTS heap pointer. Exact live pointer results
recover their StablePtr identity; unrelated native pointer results remain opaque
and unowned, without permission to read, write or free their target. Returning
arbitrary managed Sulong pointers is not yet supported. Storing a token does not
extend its lifetime after explicit free, and use after free remains invalid.

The fixture producer `cabal run exe:thc-fixtures -- stableptr-ffi` compares an
ordinary Haskell/C package with native GHC; `stablePtrFfiFullCoreDefault` and
`stablePtrFfiFullCoreDense` test both AST and bytecode, interpreted and compiled.
Focused tests execute the same C source through Sulong and an actual host-native
shared library, including context and lifetime controls. This does not implement
GHC's C `hs_deref_stable_ptr`/closure ABI or callbacks into guest Haskell.

The buffer-transport slice does not implement full GHC safe-FFI semantics,
interruptible calls, general retained buffers, foreign exports, initialization/finalization or arbitrary extra
native libraries. Within one call, aliases share their allocation transport and
a small C bridge produces Sulong's allocation-relative pointer, preserving C
pointer equality, distances, and backward access from an interior address.
Read-only and writable arguments to the same allocation share identity,
including an ordinary heap allocation's permitted raw byte-array aliases; a
permitted writable alias permits writes to that shared storage, while genuinely
immutable allocations remain read-only. The original Hashable XXH3 end-to-end
fixture remains integration work; focused C buffer tests alone are not evidence
that Hashable or Pandoc runs.

### Dynamic imports and native wrappers

Scalar/address `foreign import ccall "dynamic"` calls use the original FCall
argument/result representations as their NFI signature. A function-address
getter does not guess the eventual invocation ABI. Original `"wrapper"`
declarations retain their normalized callback type and exact emitted
`createAdjustor` helper/type-string association in import-proof schema 3,
including compact Core. The runtime uses public NFI `createClosure`, not GHC
RTS heap objects or generated C callback bodies.

Each context strongly retains the NFI closure and its guest StablePtr until
`freeHaskellFunPtr` or context disposal. Checked pointers reject foreign-context
and freed use; raw native bits do not extend a callback's lifetime. Safe calls
reenter through the existing guest-thread protocol and start unmasked; unsafe
reentry is rejected. Callback entry while managed pointer cells await native
writeback is rejected before guest execution. This does not introduce general
cross-thread callbacks, aggregate callback ABIs, or retained moving buffers.
The `dynamic-callback` fixture producer and `nativeCallbacksDefault` /
`nativeCallbacksDense` tasks exercise original Haskell declarations and C calls.

## Why compiling the stubs through Sulong is insufficient

The real GHC 9.14.1 `GHC.Internal.Conc.Bound` stub exports `forkOS_entry`. Its C
calls `rts_lock`, `rts_apply`, `rts_inCall`, `rts_checkSchedStatus` and `rts_unlock`;
it references `ghc_hs_iface->runIO_closure` and a native `StgClosure`. Its module
initializer calls `registerForeignExports` with native closure addresses.

THC's existing Sulong integration executes bounded original MD5 C over managed
buffer views. It does not implement GHC's closure ABI, RTS capabilities,
native GHC stable-pointer representation, callbacks into THC, foreign-export rooting or
bound-thread scheduling. Compiling the original C to LLVM would still leave
those obligations. Linking it to a host GHC RTS would invoke native Haskell,
not the THC closures, and is not a supported substitution.

A native C callback route still needs an explicit callback/registration ABI and
context lifetime model, with verified guest closure re-entry, roots, exception
and thread behavior. Whether that uses adapted Sulong stubs or a different
bridge requires a separate implemented slice. The current managed registration
path retains and checks initializer obligations without executing GHC's C stubs.
It does not provide a native GHC closure ABI.

## Typed annotations and ordinary acquisition

THC's implemented producer records typed declarations in GHC module annotations:
`foreign-export-associations` plus `foreign-export-registration` for static
exports, and `foreign-import-provenance` for supported static imports. The
selected interface reader consumes `md_anns`, resolves actual Core binders and
checks the complete retained foreign products against the stock-emitter proof.
An absent annotation is unknown, never a known-empty inventory. Existing
unclassified or rejected annotations do not become valid through regeneration.
See the [managed export contract](site/embedding.md).

For an ordinary full-Core installation lacking these annotations, project runs
can explicitly supply `--installed-core required --ghc-source DIR`. This bounded
producer accepts a matching configured GHC 9.14.1 native Linux stage1 tree with
the original GMP, Haskell2010 and NoImplicitPrelude ghc-internal configuration,
and the configured Haskell2010 Unix library. It recompiles only
`GHC.Internal.Conc.Bound`, `GHC.Internal.System.Posix.Internals`, and
`System.Posix.Files.PosixString`, `System.Posix.Process.Internals`, and
`System.Posix.Signals`, and only when their required annotation
is absent. Cabal's saved configuration supplies CPP flags, language settings and
the original dependency IDs. The selected compiler performs real code generation
with `-fwrite-if-simplified-core`; the `-fno-code` interface path loses annotations
and is not used here.

The static-import producer also recognizes a stock `ccall` address declaration
only after checking its typed binder, normalization and exact emitted address
literal, with no call, header, C source, initializer, finalizer or foreign file.
Such a declaration is omitted from the generated-stub call inventory, not given
a function ABI. Its original Core address remains subject to separate strict
address-label admission. For example, Unix's `&nocldstop` does not prevent proof
of its unrelated generated signal-set wrappers; unknown data/function addresses
still fail the strict audit when reachable.

The tree's dynamic interfaces must match the selected installation byte for
byte, and each target source must match its retained GHC self-recompilation
source fingerprint. Every retained `UsageFile` input, including generated CPP
headers and system headers, must still match its original fingerprint, resolved
from the configured tree's root. A changed header is rejected before compilation
or cache reuse; observing its new hash does not establish a matching build.
Regenerated input inventories must preserve these dependencies, allowing only
the selected RTS version-header copy with the same fingerprint and additional
libraries from the actual registered plugin dependency closure. Regenerated
interfaces must retain the same complete raw
foreign products. For Unix, the source is the retained configured hsc2hs output,
with its source fingerprint and original `.hsc` UsageFile checked; hsc2hs is not
rerun with guessed configuration. Composed private acquisition views change
only the selected units' interface search directories and retain both `.hi`
and `.dyn_hi`; native libraries, ABI fields and dependency IDs
remain unchanged. The project's native compiler and helper build continue to use
the original selected compiler. No installed files, JSON modules or ZIP members
are patched.

THC caches genuine outputs using source/configuration/header/interface contents
(including Hadrian's generated `hadrian/cfg/system.config` and both vanilla and
dynamic interfaces throughout the selected registered dependency closure),
the plugin and helper hashes, selected compiler information, and registrations.
Input observations are repeated before returning a view. Output hashes and
symlink inventories are checked on cache hits; publication is locked and atomic.
This is not support for an arbitrary source tarball, cross compiler, thin
interface installation, or unavailable generated configuration. Missing inputs
fail explicitly. Runtime capability admission remains a separate audit.

## Controls

The Haskell producer compares every serialized field against the actual binary
interface, using a real foreign export and a TH-added C file. A separate private
registration exercises production helper acquisition and checked ZIP transport.
Java verifies exact metadata preservation, unreachable archive admission and
reachable foreign-call rejection in AST and bytecode, with diagnostic mode both
enabled and disabled. Malformed/downgraded archives, finalizer-only records and
foreign-file contents have separate controls.
The original 21-row opaque/private/CBV fixture remains executable schema 1.

## What survives an installed interface

Without THC's producer annotations, the unmodified GHC 9.14.1 writer does **not** persist the typed association between a foreign-exported
C symbol and its Core binder. Hydration cannot recover an association that the
writer discarded. It does retain the binder's original external `Name`, type,
Core body and representation information, plus the separate raw foreign stub.

The `interface-core` Haskell fixture proves the distinction with one source,
unit and module compiled twice: `StablePtr (IO ()) -> IO ()` is exported as
`thc_interface_alias_a` and `thc_interface_alias_b`. After deleting the compiled
source target, it reads both real interfaces through the GHC API and checks:

- typed interface declarations and Haskell export lists are identical;
- hydrated external binder names, types, argument/result primitive reps and
  GHC-rendered C closure labels are identical;
- the actual foreign headers and C sources differ only in the external symbol;
- neither interface has an extensible field supplying the missing association.

`build/interface-core/foreign-association.json` records this negative proof.
`installed-bound-facts.json` also records the actual selected compiler's
`Conc.Bound` binder when complete Core is available; stock thin interfaces are
reported as unavailable, not silently substituted. The observed closure has
type `StablePtr (IO ()) -> IO ()`, with a **boxed** StablePtr argument; its
worker's `StablePtr#`/`AddrRep` convention is not the C export's Core entry ABI.

The test observes complete `extern StgClosure ...;` declarations using GHC's
`pprCode (pprCLabel platform (mkClosureLabel name cafInfo))`. The ordinary
pretty-printer can emit an unqualified suffix and produce false matches.
This fixture assertion is not a production C parser or executable descriptor.
All observed associations are explicitly marked `executableAssociation=false`.

The loss occurs at these pinned GHC sites (release source commit
`902339d332fb4ce2b3c87dcac1ee6495d41ad886`):

| Site | Available information |
| --- | --- |
| `GHC.Tc.Gen.Foreign.tcFExport` | Typed `ForeignExport`: stable binder, normalized-FFI coercion, external symbol and calling convention. |
| `GHC.Tc.Utils.Env.mkStableIdFromName` | Stable name derived from the **Haskell** name and wrapper number, not the exported C symbol. |
| `GHC.HsToCore.Foreign.C.dsCFExport` | Actual binder, normalized arguments/result, IO distinction, C symbol and convention used to emit the stub. |
| `GHC.HsToCore.Foreign.Decl.foreignExportsInitialiser` | Exact ordered root binders and `fexports` initializer label. |
| `GHC.Types.ForeignStubs.CStub` | Only rendered code plus initializer/finalizer labels; no typed export records. |
| `GHC.Unit.Module.WholeCoreBindings.IfaceForeign` / `IfaceCStubs` | Raw code/files and labels serialized by `encodeIfaceForeign` and their `Binary` instances. |
| `GHC.Iface.Syntax.IfaceIdDetails` / `IfaceInfoItem` | No foreign-export marker or C-target association. `FCallId` describes imports, not these exported vanilla binders. |

Neither `$fstable` spelling, `mi_exports`, an ordinary global binding, nor an
FFI reimport proves which C entry exports it. Searching raw C for a name does
not account for the rest of a module's initializer behavior.

## Alternative GHC writer metadata patch (unimplemented)

This is a design, **not implemented acquisition or runtime support**. Prefer
persisting data at the actual GHC emitter over reconstructing C semantics.

1. Add an internal typed static-export descriptor alongside `CStub` in
   `GHC.Types.ForeignStubs`. At `dsCFExport`, capture the same stable `Name`,
   external `CLabelString`, `CCallConv`, declared type, normalized type/coercion,
   argument/result types and IO distinction already used by `mkFExportCBits`.
   Dynamic wrappers are a distinct descriptor kind or explicitly unsupported;
   they must not be mislabeled static exports.
2. At `foreignExportsInitialiser`, retain the exact `CStubLabel` and ordered
   exported root Names as a typed registration record. `CStub` concatenation
   preserves record order. Update its constructors and `Monoid`/`Semigroup`
   instances; arbitrary stubs/hooks carry an explicit unknown-coverage marker,
   not an empty list claiming that no obligations exist. Raw C stays unchanged.
   Existing `mg_foreign`/`cg_foreign` transport in `ModGuts`, `CgGuts` and
   `GHC.Iface.Tidy` can carry this metadata without changing `dsForeignsHook`'s
   return type or introducing process-global side tables.
3. Extend `IfaceCStubs` in `GHC.Unit.Module.WholeCoreBindings` with a versioned
   optional `IfaceForeignExports` payload. Convert Names/types/coercions using
   GHC's existing interface encodings at `encodeIfaceForeign`; add the matching
   `Binary`, `NFData` and reconstruction cases. `GHC.Iface.Make.mkFullIface`
   already calls this encoder to fill `mi_sc_foreign`, so metadata travels in
   the same full-Core payload as its actual C. Unknown versus known-empty must
   remain distinct. Changing binary layout requires a distinct interface-format
   version/build identity; never read an old positional record as the new one.
4. THC's `loadInterfaceCore` then exposes the typed records without compiling
   source. Resolve each external Name to exactly one hydrated binder; check
   owner, declared/normalized types, coercion endpoints, argument/result reps,
   convention, IO wrapper and initializer roots. Derive the C closure spelling
   with GHC's C-label printer, never a custom z-encoding. Keep original Core
   bodies/groups and the raw artifacts. Archive acceptance still does not grant
   executable status; every foreign obligation needs a supported link plan.

For Bound, the minimum resulting descriptor is: original `ghc-internal` owner,
`GHC.Internal.Conc.Bound`, the stable exported Core `Name`, C symbol
`forkOS_entry`, `ccall`, C argument `HsStablePtr`, boxed Core argument
`StablePtr (IO ())`, result `IO ()`/C `void`, original
`GHC.Internal.TopHandler.runIO` wrapper, and the `fexports` initializer containing
that exact root. Do not substitute the unboxed worker or omit `runIO`.

Required producer checks are the two-name counterexample, multiple exports of
one Haskell function, pure versus IO exports, normalized newtypes/coercions,
dynamic wrappers, malformed/unknown records and complete record roundtrips
through a fresh interface reader. This patch needs a separately identified GHC
build; the current private full-Core compiler has not been patched or rebuilt.

## Bounded fallback for existing interfaces

If rebuilding the writer is deferred, a possible **unimplemented** alternative
is a closed recognizer for the pinned static `HsStablePtr -> void` export form:

1. Require the exact GHC version, target ABI, one original `fexports`
   initializer, no finalizers and no extra foreign files.
2. Parse the complete header as exactly one `extern void IDENT(HsStablePtr a1);`
   declaration. Reject directives, attributes, extra tokens and declarations.
3. Generate candidate C closure labels from hydrated external Names. Require
   exactly one full declaration/registration match, never a substring/prefix.
   Validate its actual normalized `StablePtr (IO ()) -> IO ()` type and reps.
4. Recognize the **entire** C body: the exact lock/apply/box-StablePtr/runIO/
   inCall/status/unlock expression tree, one registration list containing the
   same closure, and the initializer body referring to that list. Every token,
   symbol, argument position and statement must be accounted for. Whitespace
   normalization must not erase directives/comments or alter token boundaries.
5. Emit an explicitly versioned recognized-stub descriptor linked to the
   retained raw artifacts, never claim it came from typed `.hi` metadata.
   Reject ambiguity, added side effects, changed wrappers and any unmatched
   artifact. Negative mutations of each checked position are acceptance tests.

This can be narrower than a compiler rebuild but creates a second maintained
description of GHC's generated C grammar. The typed writer path is the preferred
general solution. Neither path by itself implements callback execution.

## Thread identity constraint

THC uses **Java thread ID as guest thread identity**.
This work does not replace it with logical TSO IDs. Native GHC's `rts_inCall`
creates a fresh TSO; a same-Java-thread THC callback therefore cannot silently
claim identical native `myThreadId` semantics. The future callback bridge must
explicitly settle that target-platform difference, masking and exception-entry
policy before admitting affected paths. It must not quietly change thread
identity or pretend that preserving export metadata solves callback semantics.
