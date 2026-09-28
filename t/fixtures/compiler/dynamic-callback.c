/* SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause */
#include <stdint.h>

static int32_t (*retained)(int32_t);
void thc_callback_retain(int32_t (*callback)(int32_t)) { retained = callback; }
int32_t thc_callback_invoke(int32_t input) { return retained(input); }
void thc_callback_clear(void) { retained = 0; }
int32_t thc_callback_add(int32_t input) { return input + 19; }
