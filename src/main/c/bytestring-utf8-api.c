/* SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause */
#include <limits.h>
#include <stddef.h>
_Static_assert(CHAR_BIT == 8 && sizeof(size_t) == 8 && sizeof(int) == 4,
               "ByteString UTF-8 calls require the installed CSize/CInt ABI");
/* The build records __STDC_NO_ATOMICS__=1, selecting the upstream fallback.
 * Keep the original algorithm and embedded BSD license byte-for-byte. */
#include "../../../compiler/pinned-bytestring/0.12.2.0/cbits/is-valid-utf8.c"
