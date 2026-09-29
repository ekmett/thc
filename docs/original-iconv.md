# Original locale/iconv boundary

This bounded Linux GNU LP64 path admits exactly the four original
`ghc-internal` static, unsafe `ccall` targets from
`GHC.Internal.IO.Encoding.Iconv` in GHC9.14.1:

| Target | Arguments before State# | Result after State# |
| --- | --- | --- |
| `localeEncoding` | none | AddrRep |
| `hs_iconv_open` | AddrRep, AddrRep | Int64Rep |
| `hs_iconv_close` | Int64Rep | Int32Rep |
| `hs_iconv` | Int64Rep, four AddrRep | Word64Rep |

These are the original declarations, not public aliases for arbitrary foreign
imports. The closed descriptor validator checks owner, target, convention,
safety, saturation, exact argument/result representations, false unlifted CBV
flags, State and stored operand proofs. AST and bytecode operations have typed
address/long lanes and validate State before effects. Terminal operations and
general native pointers are outside this addition.

## Native transport and lifetime

`src/main/c/iconv-api.c` is a transport adapter, not a conversion algorithm.
Sulong executes its LLVM bitcode; its calls to the host libc `iconv_open`,
`iconv` and `iconv_close` do the conversion. The adapter copies checked managed
byte regions into **malloc-owned native memory** before calling libc. It never
passes a Java buffer interop object to libc as a native pointer. Temporary
buffers are freed on normal return, including native errno failures. No Java
Charset conversion is involved.

The guest CLong handle is a context-owned registry token. Native descriptors
remain opaque, persist across conversion calls, and are closed by explicit
close or orderly, uncancelled context finalization while LLVM calls remain legal. Closed,
unknown and cross-context tokens fault before native access. Registry tokens
are not file descriptor numbers and never become libc file descriptors.

Guest `throwTo` delivery is masked during these callback-free foreign calls.
Host cancellation (`Context.close(true)` or interruption of LLVM execution) is
not covered: it can bypass C cleanup or locale restoration, and later context
cleanup may no longer be able to call LLVM. Cancellation-safe native resource
ownership remains follow-up work.

Pointer cells retain managed addresses. The complete input/output ranges,
mutable eight-byte aligned pointer/count cells, canonical bounded counts and
disjointness are checked before native conversion changes state. Pointer-bearing
byte regions, immutable outputs, overlapping cells/buffers, and counts outside
managed capacity are rejected. Copies do not expose guest allocations to native
code, so the C-side capacities cannot change during conversion. Concurrent
unsynchronized guest mutation of the same cells/buffers is not a supported
transactional protocol.

Consumed input, produced output, both cursors and both remaining counts are
written back even when libc returns `(size_t)-1`. Native errno is captured
immediately before any cleanup/locale restoration and enters the same
context/Java-thread slot read by original `__hscore_get_errno`; success preserves
that slot. `E2BIG`, `EINVAL` and `EILSEQ` retain libc's exact partial-progress
semantics. Null input (or a cell containing null) performs the native reset;
null output is admitted only for a reset that discards the shift sequence.

## Locale policy and platform boundary

GHC RTS startup calls `setlocale(LC_CTYPE, "")`. The adapter instead initializes
one native `locale_t` per THC context with `newlocale(LC_CTYPE_MASK, "", 0)` and
copies `nl_langinfo_l(CODESET, locale)` into a retained immutable managed string.
It activates that locale on the executing native thread for open and conversion,
then restores the previous locale. Conversion needs this too: transliteration
can consult LC_CTYPE after open. This avoids process-global locale mutation.

For a valid unchanged process locale environment, this matches the selected
Linux GHC startup policy. It is explicitly a first-use environment snapshot:
later process-global `setlocale` changes are not reflected. Invalid environment
locale initialization faults rather than silently inheriting an embedding JVM
locale. The supported target uses glibc iconv and `HAVE_LANGINFO_H`, not an
alternate libcharset/libiconv configuration. Darwin and other libc/platform
variants are not claimed; the lazy iconv loader rejects them without changing
the existing MD5 path.
