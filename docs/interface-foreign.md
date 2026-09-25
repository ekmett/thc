# Installed Core and foreign artifacts

Complete Core acquisition and native foreign-export registration are different
operations. The selected GHC interface loader preserves original Core together
with `IfaceForeign`; it does not compile, link, initialize or register its C.

Ordinary modules with no foreign products remain Core schema 1, unchanged.
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

## Why compiling the stubs through Sulong is insufficient

The real GHC 9.14.1 `GHC.Internal.Conc.Bound` stub exports `forkOS_entry`. Its C
calls `rts_lock`, `rts_apply`, `rts_inCall`, `rts_checkSchedStatus` and `rts_unlock`;
it references `ghc_hs_iface->runIO_closure` and a native `StgClosure`. Its module
initializer calls `registerForeignExports` with native closure addresses.

THC's existing Sulong integration executes bounded original MD5 C over managed
buffer views. It does not implement GHC's closure ABI, RTS capabilities,
stable-pointer representation, callbacks into THC, foreign-export rooting or
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
the original GMP, Haskell2010 and NoImplicitPrelude library configuration. It
recompiles only `GHC.Internal.Conc.Bound` and
`GHC.Internal.System.Posix.Internals`, and only when their required annotation
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
foreign products. A private acquisition view changes only ghc-internal's
interface search directory; native libraries, ABI fields and dependency IDs
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
