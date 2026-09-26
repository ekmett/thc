// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

#include <limits.h>
#include <stdint.h>
#include <sys/types.h>
_Static_assert(CHAR_BIT == 8 && sizeof(size_t) == 8 && sizeof(ssize_t) == 8,
               "text adapter requires the 64-bit size_t/ssize_t ABI");
#include <string.h>
/* Keep this dependency in the same bitcode module. Sulong's native libc
 * memchr cannot consume an ordinary managed heap buffer. Rename both the
 * original portable implementation and text's call so native interposition
 * cannot silently project or copy that buffer. DEF_STRONG is OpenBSD's
 * host-libc symbol annotation, not part of the implementation. */
#define memchr thc_text_memchr
#define DEF_STRONG(name)
#include "../../../compiler/pinned-text/2.1.3/openbsd-memchr.c"
#undef DEF_STRONG
/* Compile the unchanged text implementations. The build selects their
 * supported non-atomic/SSE configuration, avoiding a host CPUID/AVX dispatcher
 * inside Sulong. The byte buffer remains the caller's original allocation. */
#include "../../../compiler/pinned-text/2.1.3/cbits/utils.c"
#include "../../../compiler/pinned-text/2.1.3/cbits/measure_off.c"
#undef memchr
