/* SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause */
#include <stdint.h>

static int32_t (*retained)(int32_t);
void thc_callback_retain(int32_t (*callback)(int32_t)) { retained = callback; }
int32_t thc_callback_invoke(int32_t input) { return retained(input); }
void thc_callback_clear(void) { retained = 0; }
int32_t thc_callback_add(int32_t input) { return input + 19; }

typedef int32_t *(*pointer_callback)(int32_t *);
pointer_callback thc_callback_echo(pointer_callback callback) {
    /* Exercise returned native bits, not an optimizer-preserved argument carrier. */
    pointer_callback result;
    volatile const unsigned char *source = (volatile const unsigned char *)&callback;
    volatile unsigned char *target = (volatile unsigned char *)&result;
    for (unsigned i = 0; i < sizeof(result); ++i) target[i] = source[i];
    return result;
}
