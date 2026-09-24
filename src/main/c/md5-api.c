// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

#include <limits.h>
#include <stddef.h>
#include <stdint.h>
#include "md5.h"
_Static_assert(CHAR_BIT == 8 && sizeof(void *) == 8, "64-bit target with eight-bit bytes required");
_Static_assert(sizeof(int) == 4, "32-bit CInt required");
_Static_assert(sizeof(struct MD5Context) == 88 && _Alignof(struct MD5Context) == 4, "GHC MD5 layout");
_Static_assert(offsetof(struct MD5Context, buf) == 0 && offsetof(struct MD5Context, bytes) == 16 &&
               offsetof(struct MD5Context, in) == 24, "GHC MD5 fields");
/* Byte buffers are byte-addressed foreign objects, not named C structs. Keep
 * the casts inside C so Sulong never interprets the host buffer as struct fields. */
#include "md5.c"
void thc_md5_init(unsigned char *base, int64_t offset) {
    __hsbase_MD5Init((struct MD5Context *)(base + offset));
}
void thc_md5_update(unsigned char *context, int64_t context_offset,
                    unsigned char const *input, int64_t input_offset, int length) {
    __hsbase_MD5Update((struct MD5Context *)(context + context_offset), input + input_offset, length);
}
void thc_md5_final(unsigned char *output, int64_t output_offset,
                   unsigned char *context, int64_t context_offset) {
    __hsbase_MD5Final(output + output_offset, (struct MD5Context *)(context + context_offset));
}
