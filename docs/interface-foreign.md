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

## Package-owned C, C++ and CAPI calls

The `thc-package-c-ffi-v1` profile acquires ordinary local and Cabal-store
packages without a package-name whitelist. It compiles the configured C/C++ sources
and GHC's genuine retained CAPI wrappers with Clang, then links their LLVM into
the Core bundle. Native Cabal compilation still uses the selected GHC and its
configured native compiler. LLVM acquisition is a sensible second compilation,
not a requirement to reproduce the native object's exact bytes.

Initial support is static, unsafe `ccall`/`capi`, scalar arguments, `Addr#`,
`ByteArray#` and `MutableByteArray#`, and a scalar, address or void result. Pure source
imports and IO imports both retain GHC's actual State-token worker ABI. Pointer
results retain the runtime's pointer ownership/lifetime boundary. Callbacks,
safe/interruptible calls, additional foreign-file products,
initializers/finalizers and extra native libraries remain outside this profile.
Ordinary memory helpers supplied by Sulong/libc are allowed. C++ `.cc`, `.cpp`
and `.cxx` sources retain their actual Cabal compiler arguments, including
`-optcxx` options, and replay through GHC's C++ compiler phase. The LLVM link
still rejects constructors/destructors and unresolved C++ runtime dependencies;
this does not admit arbitrary C++ programs. Assembly sources remain unsupported.

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

The checksum source provider recognizes retained `zlib.h` imports for `adler32`
and `crc32`, compiles unchanged upstream zlib 1.2.11 source with the package's
configured header, and rejects a header-version or source-hash mismatch. It
links a provider only when that symbol remains unresolved after package source
linking, preserving package-owned definitions. Source/header observations and
provider hashes remain in the component identity and `buildInputs`. This is
managed LLVM over the original buffers: no native zlib call, extra buffer copy,
or heap pinning is introduced. Other zlib versions and operations remain outside
this bounded provider.

The unchanged `digest-0.0.2.1` package now captures its two original C++ CRC32C
units and links the checksum providers. All six typed foreign adapters match
270 native Haskell observations over empty inputs, offsets, partial loops,
unsigned seeds and long blocks in interpreted and first-installed compiled
execution, in both handoff modes. This checks the common foreign adapters;
it does not claim whole Pandoc or whole-package Core execution.
`erf-2.0.0.0` has source-pure imports whose emitted State-threaded calls are
`safe`, and needs a libm provider. Those obligations are not satisfied by
pointer-variant support, and safe calls are not relabeled unsafe.

Prepare the original checksum fixture with the pinned toolchain and an unchanged
acquired source tree, then run its two explicit test forks:

```sh
THC_DIGEST_SOURCE=/path/to/digest-0.0.2.1 cabal run exe:thc-fixtures -- package-native-originals
./gradlew --continue packageNativeOriginalsDefault packageNativeOriginalsDense
```

The Haskell producer retains original source hashes, compiler calls, typed Core,
LLVM acquisition, native observations, and changed-source/header negative
controls under `build/original-native`. The JVM rejects stale fixture inputs.

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
for ordinary package C code. It accepts static, unsafe `ccall` and `capi`
entries with machine-word and 8/16/32/64-bit signed/unsigned integers,
`FloatRep`, `DoubleRep`, `AddrRep`, `ByteArray#` and `MutableByteArray#`
arguments, and numeric, opaque pointer or void results. Source-level pure imports still use
GHC's emitted State-threaded foreign worker. A CAPI value import is executed
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

This slice does not support safe/interruptible calls, general retained buffers,
callbacks, foreign exports, initialization/finalization or arbitrary extra
native libraries. Within one call, aliases share their allocation transport and
a small C bridge produces Sulong's allocation-relative pointer, preserving C
pointer equality, distances, and backward access from an interior address.
Read-only and writable arguments to the same allocation share identity,
including an ordinary heap allocation's permitted raw byte-array aliases; a
permitted writable alias permits writes to that shared storage, while genuinely
immutable allocations remain read-only. The original Hashable XXH3 end-to-end
fixture remains integration work; focused C buffer tests alone are not evidence
that Hashable or Pandoc runs.

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
`System.Posix.Files.PosixString`, and only when their required annotation
is absent. Cabal's saved configuration supplies CPP flags, language settings and
the original dependency IDs. The selected compiler performs real code generation
with `-fwrite-if-simplified-core`; the `-fno-code` interface path loses annotations
and is not used here.

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
Kotlin verifies exact metadata preservation, unreachable archive admission and
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
