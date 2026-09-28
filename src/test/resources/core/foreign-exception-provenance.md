# Foreign exception metadata declaration

`foreign-exception-descriptor.json` is the unchanged `foreignCall` object for
`thc_exception_v1_text` from the pinned GHC 9.14.1 post-Tidy export of
`src/runtime/THC/Internal/Exception.hs`. Every occurrence in that export is
structurally identical. The declaration is `ccall safe`, with an opaque StablePtr
address, Int32 selector, Int64 index and erased state token; its result is the
state/Int64 tuple. No target, convention, safety, arity or representation was
invented or changed.

- Source SHA-256: `89ae756d7a22ae7af2751141d34502496cce35a43bf58d603bd40ef578dc30aa`.
- Complete exported module SHA-256: `8045a7929bf21a5803b88ddf2f31aa42c85069a45366b323847ef9f7e6b3620c`.
- Retained descriptor SHA-256: `137ac18f6daf09ea50fc62d9b90cd0d7604f5f963f90eeb3529291e54951413e`.

Regenerate with `cabal run exe:thc-fixtures -- foreign-exceptions`, using the
complete-Core provider and configured source described in
`docs/foreign-exceptions.md`. The source object is in
`build/foreign-exceptions/runtime-core/THC.Internal.Exception.json`; select its
nested `foreignCall` objects whose `target.symbol` is `thc_exception_v1_text`,
require structural equality, and copy that one object unchanged.

Portable auditor tests assemble synthetic callers around this real declaration
to check the ABI. They do not execute foreign exceptions or manufacture a
Haskell dictionary. The dedicated original-Core runtime tests separately cover
actual catch, inspection, cleanup and rethrow behavior.
