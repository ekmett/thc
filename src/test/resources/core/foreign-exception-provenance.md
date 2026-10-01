# Foreign exception ABI test declaration

`foreign-exception-descriptor.json` retains the GHC `foreignCall` declaration
for `thc_exception_v1_text` in `THC.Internal.Exception`. It describes `ccall
safe`, an opaque StablePtr address, Int32 selector, Int64 index and erased state
token, returning the state/Int64 tuple.

Retained descriptor SHA-256:
`137ac18f6daf09ea50fc62d9b90cd0d7604f5f963f90eeb3529291e54951413e`.

Regenerate with `cabal run exe:thc-fixtures -- foreign-exceptions`, using the
provider described in `docs/foreign-exceptions.md`. Locate the runtime module
through the generated manifest's CBD references. For inspection, use
`thc-compact decode`; select the `foreignCall` objects targeting
`thc_exception_v1_text`, require structural equality, and retain that object.
Readable inspection output is optional; fixture producers and consumers use CBD.

Portable auditor tests check the declaration's ABI without executing it.
Original-Core runtime tests cover catch, inspection, cleanup and rethrow.
