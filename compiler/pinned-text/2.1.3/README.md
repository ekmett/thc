# Original text 2.1.3 C leaves

`cbits/utils.c`, `cbits/measure_off.c`, `cbits/reverse.c` and `LICENSE` are unchanged files from
the text 2.1.3 source distributed with the pinned GHC 9.14.1 source tree.
The C files retain Andrew Lelechenko's copyright notice; `LICENSE` contains
the upstream redistribution terms. `scripts/build-cbits.py` checks their
SHA-256 identities and records them in the generated cbits manifest.

The Linux x86-64 adapter compiles the original source with
`__STDC_NO_ATOMICS__=1`, selecting its existing SSE/word/tail implementation.
This avoids the source's host-CPUID/AVX-512 dispatch in Sulong; no algorithm
is rewritten and no AVX acceleration claim is made. The independent native
oracle calls the installed original text library with its own build choices.

The same text 2.1.3 ABI is admitted for the source-build unit suffix `inplace`
and installed hexadecimal suffixes such as `text-2.1.3-e182`. The complete
original unit is retained in Core and fixture receipts; other releases and
unrecognized suffix spellings reject. The fixture verifies the selected
package release and records its actual installed registration identity.

`openbsd-memchr.c` is the unchanged portable implementation from OpenBSD
`lib/libc/string/memchr.c`, revision 1.8 (2015-08-31), retrieved from
https://raw.githubusercontent.com/openbsd/src/master/lib/libc/string/memchr.c .
Its complete BSD-3-Clause notice is retained. The adapter renames both this
definition and text's call to `thc_text_memchr` and omits OpenBSD's host symbol
annotation. The build verifies a local LLVM definition and no unresolved
`memchr`; neither the implementation nor its byte-search loop is rewritten.
The generated runtime resources carry both redistribution notices.
