# Exact UTF-8 inventory-probe paths — 2026-09-26

The inventory helper previously unpacked Aeson's UTF-8 output as Latin-1-like
`Char8` characters, then encoded those characters again through a UTF-8 Handle.
Non-ASCII interface paths were corrupted. The driver's exact inventory check
correctly rejected that evidence and fell back to full Core acquisition, so an
unchanged warm request hydrated the module again.

The probe now forces Aeson's original bytes and writes them directly, as the
complete-Core helper already does. The schema, field order, fingerprints,
complete-Core flags, inventory matching and fresh-probe rules are unchanged.
Serialization completes inside the existing exception boundary before any
success output. No path normalization or relaxed identity check was introduced.

## Reproduction and verification

The genuine installed-cache fixture now places its interface and source files
under a directory containing Greek, CJK and supplementary Unicode characters.
Before the fix, the unchanged-warm assertion failed: the counting trace was
`probe, load, probe, load`. That failure and its original trace were retained.
After the fix, every warm-cache control again requires exactly one fresh probe
and zero hydration; all mutation, omitted-provider, corrupt-cache, source and
failed-refresh-preservation controls pass on the Unicode paths.

A separate frozen-helper comparison uses the same 512-interface inventory and
one genuine interface copied to a Unicode path. ASCII request responses remain
byte-identical (SHA-256
`44b39030c8d9483c929963e92ff78d4959cbdb338dc6ce30989c1afb6883ea32`).
The old helper corrupts the Unicode path; the corrected response preserves the
exact requested path and every other field, including fingerprints and order.
Its bytes match the expected single UTF-8 encoding plus the original newline.

Strict development builds pass all ten renderer tests and nine focused driver
controls. Fresh real-interface production passes 21 native rows and the full
cache gates. All 30 selected runtime tests pass in default and dense modes,
including original native results, strict admission, first-installed AST and
bytecode calls, and code retention. This is a cache-correctness fix that removes
unnecessary cold work; no whole-application speedup is claimed.
