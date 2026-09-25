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

Schema 2 is deliberately **archive-only**. Older schema-1 readers reject it;
the current checked ZIP reader validates and transports its metadata, but the
runtime refuses it before combining modules, pruning bindings, or executing any
guest code. Diagnostic mode does not bypass this boundary. A schema-1 document
cannot hide a `foreign` field. The strict Core auditor continues to reject
schema 2 rather than silently treating foreign registration as implemented.
This slice does not change the ordinary source-plugin foreign-output contract.

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

A future executable route needs an explicit callback/registration ABI and
context lifetime model, with verified guest closure re-entry, roots, exception
and thread behavior. Whether that uses adapted Sulong stubs or a different
bridge requires a separate implemented slice. This archive work neither drops
initializers nor claims `forkOS` support.

## Controls

The Haskell producer compares every serialized field against the actual binary
interface, using a real foreign export and a TH-added C file. A separate private
registration exercises production helper acquisition and checked ZIP transport.
Kotlin verifies exact metadata preservation and rejection in AST and bytecode,
with diagnostic mode both enabled and disabled. Malformed/downgraded archives,
finalizer-only records and foreign-file contents have separate controls.
The original 21-row opaque/private/CBV fixture remains executable schema 1.
